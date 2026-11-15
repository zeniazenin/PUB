package app.pub.core;

import com.google.gson.*;
import java.math.BigDecimal;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Contract of an app's private file folder (capability storage.files). Scripts name files only by relative paths
 * inside their own folder; the host resolves every location. UiPacket validates each request envelope with
 * {@link #validate} (unknown fields or operations fail the packet, like hardware requests). Arguments are validated
 * when the host performs the request ({@link #parse}): an invalid one becomes an error reply, so a script can recover.
 * Replies are built here so their size bounds are testable: every reply event fits {@link #MAX_REPLY_CHARS}, below
 * the runtime's {@link ScriptEngine#MAX_MESSAGE} event limit.
 */
public final class FilesContract {
    public static final String CAPABILITY = "storage.files";
    public static final String EFFECT = "files.request";
    public static final String EVENT = "files";
    /** The read-mostly folder at the root of every app's folder that lists the app's camera photos. */
    public static final String PHOTOS = "photos";
    public static final Set<String> OPERATIONS = Set.of("list", "stat", "read", "write", "mkdir", "delete", "move", "copy", "usage");
    /** Stable codes of {@code ok:false} replies. */
    public static final Set<String> CODES = Set.of("not_found", "exists", "not_empty", "is_directory", "not_directory",
        "invalid_path", "invalid_arguments", "quota_exceeded", "read_only", "unavailable", "busy");
    public static final int MAX_NAME_CHARS = 64, MAX_DEPTH = 8, MAX_PATH_CHARS = 256;
    /** Bytes one read reply or one write request carries. */
    public static final int MAX_CHUNK_BYTES = 12_000;
    public static final int MAX_LIST_ENTRIES = 500;
    /** Per-app quotas of the folder (photos have their own). Entries count files and folders. */
    public static final long QUOTA_BYTES = 256L * 1024 * 1024;
    public static final int QUOTA_ENTRIES = 5_000;
    /** Requests of one app session that may wait for their replies; more are answered with busy. */
    public static final int MAX_PENDING = 8;
    /** Upper bound of every reply event's JSON text, leaving room below ScriptEngine.MAX_MESSAGE. */
    public static final int MAX_REPLY_CHARS = 30_000;
    /** Characters UTF-8 text in one read reply may take after JSON string escaping. */
    public static final int MAX_TEXT_CHARS = 24_000;
    private static final int MAX_MESSAGE_CHARS = 200;
    private static final int MAX_BASE64_CHARS = (MAX_CHUNK_BYTES + 2) / 3 * 4;
    /** Bounds list offsets; a folder never holds more than the entry quota plus the photos. */
    private static final int MAX_LIST_OFFSET = 2 * QUOTA_ENTRIES;
    private static final Pattern REQUEST_ID = Pattern.compile("[a-zA-Z0-9_-]{1,64}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9 _()\\-][A-Za-z0-9 ._()\\-]{0,63}");
    private static final Set<String> ENVELOPE = Set.of("type", "id", "operation", "args");
    private static final Map<String, Set<String>> ARGUMENTS = Map.of(
        "list", Set.of("path", "recursive", "offset"),
        "stat", Set.of("path"),
        "read", Set.of("path", "offset", "length", "encoding"),
        "write", Set.of("path", "data", "encoding", "append", "createParents"),
        "mkdir", Set.of("path"),
        "delete", Set.of("path", "recursive"),
        "move", Set.of("from", "to", "overwrite"),
        "copy", Set.of("from", "to", "overwrite"),
        "usage", Set.of());
    private FilesContract() {}

    /** An error the script receives as an {@code ok:false} reply with a stable {@link #CODES code}. */
    public static final class Failure extends RuntimeException {
        public final String code;
        public Failure(String code, String message) {
            super(message, null, false, false);
            if (!CODES.contains(code)) throw new IllegalArgumentException("Unknown files error code");
            this.code = code;
        }
    }

    /** A validated request. Paths are normalized and relative; "" is the app's folder itself. */
    public static final class Request {
        public final String id, operation;
        /** list, stat, read, write, mkdir and delete; null otherwise. */
        public final String path;
        /** move and copy; null otherwise. */
        public final String from, to;
        public final boolean recursive, append, createParents, overwrite;
        /** Byte offset for read, number of entries to skip for list. */
        public final long offset;
        /** Bytes to read at most. */
        public final int length;
        /** "utf8" or "base64" for read and write; null otherwise. */
        public final String encoding;
        /** Decoded write data, at most {@link #MAX_CHUNK_BYTES}; null otherwise. Never modified by the host. */
        public final byte[] data;
        private Request(Draft d) {
            id = d.id; operation = d.operation; path = d.path; from = d.from; to = d.to;
            recursive = d.recursive; append = d.append; createParents = d.createParents; overwrite = d.overwrite;
            offset = d.offset; length = d.length; encoding = d.encoding; data = d.data;
        }
    }
    private static final class Draft {
        String id, operation, path, from, to, encoding;
        boolean recursive, append, createParents, overwrite;
        long offset;
        int length;
        byte[] data;
    }

    /** Envelope check for UiPacket: exactly type, id, operation and an object of bounded size as args. */
    public static void validate(JsonObject effect) {
        if (!effect.keySet().equals(ENVELOPE)) throw new IllegalArgumentException("Unexpected files fields");
        if (!EFFECT.equals(text(effect, "type", 32))) throw new IllegalArgumentException("Invalid files effect type");
        if (!REQUEST_ID.matcher(text(effect, "id", 64)).matches()) throw new IllegalArgumentException("Invalid request id");
        if (!OPERATIONS.contains(text(effect, "operation", 32))) throw new IllegalArgumentException("Unsupported files operation");
        if (!effect.get("args").isJsonObject() || effect.get("args").toString().length() > ScriptEngine.MAX_MESSAGE) throw new IllegalArgumentException("Invalid files arguments");
    }

    /**
     * Validates the envelope (IllegalArgumentException, as in {@link #validate}) and then the arguments of the
     * operation: unknown or mistyped arguments throw a {@link Failure} with invalid_arguments, bad paths invalid_path.
     */
    public static Request parse(JsonObject effect) {
        validate(effect);
        Draft d = new Draft();
        d.id = effect.get("id").getAsString();
        d.operation = effect.get("operation").getAsString();
        JsonObject args = effect.getAsJsonObject("args");
        Set<String> allowed = ARGUMENTS.get(d.operation);
        for (String key : args.keySet()) {
            if (!allowed.contains(key)) throw invalid("Unknown argument '" + key.substring(0, Math.min(32, key.length())) + "' for " + d.operation);
        }
        switch (d.operation) {
            case "list":
                d.path = path(args, "path", false, true);
                d.recursive = flag(args, "recursive", false);
                d.offset = integer(args, "offset", 0, 0, MAX_LIST_OFFSET);
                break;
            case "stat":
                d.path = path(args, "path", true, true);
                break;
            case "read":
                d.path = path(args, "path", true, false);
                d.offset = integer(args, "offset", 0, 0, QUOTA_BYTES);
                d.length = (int) integer(args, "length", MAX_CHUNK_BYTES, 1, MAX_CHUNK_BYTES);
                d.encoding = encoding(args);
                break;
            case "write":
                d.path = path(args, "path", true, false);
                d.encoding = encoding(args);
                JsonElement data = args.get("data");
                if (data == null || !data.isJsonPrimitive() || !data.getAsJsonPrimitive().isString()) throw invalid("'data' must be a string");
                d.data = "utf8".equals(d.encoding) ? utf8Bytes(data.getAsString()) : base64Bytes(data.getAsString());
                d.append = flag(args, "append", false);
                d.createParents = flag(args, "createParents", true);
                break;
            case "mkdir":
                d.path = path(args, "path", true, false);
                break;
            case "delete":
                d.path = path(args, "path", true, false);
                d.recursive = flag(args, "recursive", false);
                break;
            case "move":
            case "copy":
                d.from = path(args, "from", true, false);
                d.to = path(args, "to", true, false);
                if (d.from.equals(d.to)) throw invalid("'from' and 'to' are the same path");
                if (d.to.startsWith(d.from + "/")) throw invalid("A folder cannot be moved or copied into itself");
                d.overwrite = flag(args, "overwrite", false);
                break;
            default: // usage takes no arguments
        }
        return new Request(d);
    }

    /**
     * Normalizes a script path: "" or "." is the app's folder; otherwise '/'-separated names without a leading or
     * trailing '/', each 1-64 characters of ASCII letters, digits, space, '.', '_', '-', '(' and ')' that does not
     * start with '.' (so never '.' or '..'; dot names are reserved for the host), at most 8 names and 256 characters.
     */
    public static String normalize(String path) {
        if (path.isEmpty() || path.equals(".")) return "";
        if (path.length() > MAX_PATH_CHARS) throw new Failure("invalid_path", "Paths are at most " + MAX_PATH_CHARS + " characters");
        String[] names = path.split("/", -1);
        if (names.length > MAX_DEPTH) throw new Failure("invalid_path", "Paths are at most " + MAX_DEPTH + " folders deep");
        for (String name : names) {
            if (name.isEmpty()) throw new Failure("invalid_path", "Use a relative path with single '/' separators and no leading or trailing '/'");
            if (!validName(name)) throw new Failure("invalid_path", "Names are 1-64 ASCII letters, digits, spaces, '.', '_', '-', '(' or ')' and cannot start with '.'");
        }
        return path;
    }
    /** One path name a script may use; the host's own names start with '.' and never match. */
    public static boolean validName(String name) { return NAME.matcher(name).matches(); }
    /** True for the photos folder and anything inside it. */
    public static boolean inPhotos(String path) { return path.equals(PHOTOS) || path.startsWith(PHOTOS + "/"); }
    public static String parent(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? "" : path.substring(0, slash); }
    public static String name(String path) { return path.substring(path.lastIndexOf('/') + 1); }
    /** Sorts by path name by name, so a folder's contents follow it directly ('/' orders before every name character). */
    public static final Comparator<String> PATH_ORDER = (a, b) -> {
        int shared = Math.min(a.length(), b.length());
        for (int i = 0; i < shared; i++) {
            char x = a.charAt(i), y = b.charAt(i);
            if (x != y) return (x == '/' ? -1 : x) - (y == '/' ? -1 : y);
        }
        return a.length() - b.length();
    };

    public static JsonObject ok(Request request) {
        JsonObject reply = new JsonObject();
        reply.addProperty("type", EVENT);
        reply.addProperty("id", request.id);
        reply.addProperty("operation", request.operation);
        reply.addProperty("ok", true);
        return reply;
    }
    /** An error reply; [id] and [operation] come from a validated envelope and are echoed unchanged. */
    public static JsonObject failure(String id, String operation, String code, String message) {
        if (!CODES.contains(code)) throw new IllegalArgumentException("Unknown files error code");
        JsonObject reply = new JsonObject();
        reply.addProperty("type", EVENT);
        reply.addProperty("id", id);
        reply.addProperty("operation", operation);
        reply.addProperty("ok", false);
        reply.addProperty("code", code);
        String text = message == null || message.isBlank() ? "The file request could not be completed" : message;
        reply.addProperty("message", text.length() > MAX_MESSAGE_CHARS ? text.substring(0, MAX_MESSAGE_CHARS) : text);
        return reply;
    }
    public static JsonObject failure(Request request, Failure failure) { return failure(request.id, request.operation, failure.code, failure.getMessage()); }

    /** One listing entry. Folder sizes are 0; modified is milliseconds since 1970. */
    public static JsonObject entry(String path, boolean directory, long size, long modified) {
        JsonObject entry = new JsonObject();
        entry.addProperty("path", path);
        entry.addProperty("name", path.isEmpty() ? "" : name(path));
        entry.addProperty("type", directory ? "directory" : "file");
        entry.addProperty("size", directory ? 0 : Math.max(0, size));
        entry.addProperty("modified", Math.max(0, modified));
        return entry;
    }

    /**
     * The list reply for entries already sorted by {@link #PATH_ORDER}: from the request offset, at most
     * {@link #MAX_LIST_ENTRIES} entries and only as many as fit the reply size. {@code total} counts all entries;
     * {@code truncated} replies carry {@code nextOffset} to continue with.
     */
    public static JsonObject listReply(Request request, List<JsonObject> entries) {
        JsonObject reply = ok(request);
        int start = (int) Math.min(request.offset, entries.size());
        // Room for the fixed fields added below: "entries":[],"total":N,"truncated":false,"nextOffset":N.
        int budget = MAX_REPLY_CHARS - reply.toString().length() - 80;
        JsonArray page = new JsonArray();
        int used = 0, next = start;
        while (next < entries.size() && page.size() < MAX_LIST_ENTRIES) {
            JsonObject entry = entries.get(next);
            int cost = entry.toString().length() + 1;
            if (used + cost > budget) break;
            page.add(entry);
            used += cost;
            next++;
        }
        reply.add("entries", page);
        reply.addProperty("total", entries.size());
        reply.addProperty("truncated", next < entries.size());
        if (next < entries.size()) reply.addProperty("nextOffset", next);
        return reply;
    }

    /**
     * The read reply for {@code count} bytes read at the request offset of a file of {@code size} bytes. base64
     * returns them all; utf8 returns the longest prefix of whole characters that fits {@link #MAX_TEXT_CHARS}
     * escaped characters, so bytesRead can be smaller than count. Throws invalid_arguments when no character at
     * the offset can be returned (the bytes are not UTF-8 text, or length cuts the first character).
     */
    public static JsonObject readReply(Request request, byte[] bytes, int count, long size) {
        if (count < 0 || count > Math.min(bytes.length, MAX_CHUNK_BYTES)) throw new IllegalArgumentException("Invalid read size");
        String data;
        int used;
        if ("base64".equals(request.encoding)) {
            data = Base64.getEncoder().encodeToString(Arrays.copyOf(bytes, count));
            used = count;
        } else {
            TextChunk chunk = utf8Prefix(bytes, count, request.offset + count >= size, MAX_TEXT_CHARS);
            if (chunk.bytes == 0 && count > 0) throw invalid(chunk.invalid
                ? "The bytes at this offset are not UTF-8 text. Read them with encoding 'base64'."
                : "'length' is too small for the next character");
            data = chunk.text;
            used = chunk.bytes;
        }
        JsonObject reply = ok(request);
        reply.addProperty("data", data);
        reply.addProperty("offset", request.offset);
        reply.addProperty("bytesRead", used);
        reply.addProperty("size", size);
        reply.addProperty("eof", request.offset + used >= size);
        return reply;
    }

    /** Whole UTF-8 characters taken from the start of a buffer. */
    public static final class TextChunk {
        public final String text;
        /** UTF-8 bytes that make up {@link #text}. */
        public final int bytes;
        /** True when the chunk stopped at bytes that are not valid UTF-8 (rather than at the size limit or a cut). */
        public final boolean invalid;
        TextChunk(String text, int bytes, boolean invalid) { this.text = text; this.bytes = bytes; this.invalid = invalid; }
    }

    /**
     * The longest prefix of {@code bytes[0, count)} made of whole, valid UTF-8 characters (RFC 3629: no overlong
     * forms, surrogates or values above U+10FFFF) whose JSON string escaping takes at most {@code maxChars}
     * characters. A character cut off by {@code count} is left for the next read, unless {@code endOfData} says no
     * bytes follow, in which case it is invalid. Never splits a character.
     */
    public static TextChunk utf8Prefix(byte[] bytes, int count, boolean endOfData, int maxChars) {
        StringBuilder text = new StringBuilder();
        int i = 0, chars = 0;
        boolean invalid = false;
        while (i < count) {
            int first = bytes[i] & 0xFF, extra, point;
            if (first < 0x80) { extra = 0; point = first; }
            else if (first >= 0xC2 && first <= 0xDF) { extra = 1; point = first & 0x1F; }
            else if (first >= 0xE0 && first <= 0xEF) { extra = 2; point = first & 0x0F; }
            else if (first >= 0xF0 && first <= 0xF4) { extra = 3; point = first & 0x07; }
            else { invalid = true; break; }
            int available = Math.min(extra, count - 1 - i);
            boolean continuation = true;
            for (int k = 1; k <= available; k++) {
                int next = bytes[i + k] & 0xFF;
                if ((next & 0xC0) != 0x80) { continuation = false; break; }
                point = (point << 6) | (next & 0x3F);
            }
            if (continuation && available < extra) { invalid = endOfData; break; }
            if (!continuation || (extra == 2 && (point < 0x800 || (point >= 0xD800 && point <= 0xDFFF)))
                || (extra == 3 && (point < 0x10000 || point > 0x10FFFF))) { invalid = true; break; }
            int escaped = escapedLength(point);
            if (chars + escaped > maxChars) break;
            text.appendCodePoint(point);
            chars += escaped;
            i += extra + 1;
        }
        return new TextChunk(text.toString(), i, invalid);
    }

    /**
     * Characters a code point takes inside a JSON string written by Gson, counted conservatively: quote, backslash
     * and the short control escapes take 2; other controls, U+2028/U+2029 and the HTML-sensitive characters Gson can
     * escape (&lt; &gt; &amp; = ') take 6; supplementary characters are 2 UTF-16 units.
     */
    public static int escapedLength(int point) {
        if (point == '"' || point == '\\' || point == '\t' || point == '\b' || point == '\n' || point == '\r' || point == '\f') return 2;
        if (point < 0x20 || point == 0x2028 || point == 0x2029 || point == '<' || point == '>' || point == '&' || point == '=' || point == '\'') return 6;
        return point >= 0x10000 ? 2 : 1;
    }

    /** Strict decoders of write data, shared with {@link PhoneFilesContract}; both throw invalid_arguments. */
    static byte[] utf8Bytes(String text) {
        if (text.length() > MAX_CHUNK_BYTES) throw invalid("'data' is over " + MAX_CHUNK_BYTES + " bytes; write it in chunks");
        byte[] bytes;
        try {
            var buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            bytes = Arrays.copyOf(buffer.array(), buffer.limit());
        } catch (CharacterCodingException e) { throw invalid("'data' contains an unpaired surrogate character"); }
        if (bytes.length > MAX_CHUNK_BYTES) throw invalid("'data' is over " + MAX_CHUNK_BYTES + " bytes; write it in chunks");
        return bytes;
    }
    static byte[] base64Bytes(String text) {
        if (text.length() > MAX_BASE64_CHARS) throw invalid("'data' is over " + MAX_CHUNK_BYTES + " bytes; write it in chunks");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(text); }
        catch (IllegalArgumentException e) { throw invalid("'data' is not valid base64"); }
        if (bytes.length > MAX_CHUNK_BYTES) throw invalid("'data' is over " + MAX_CHUNK_BYTES + " bytes; write it in chunks");
        return bytes;
    }
    private static String path(JsonObject args, String key, boolean required, boolean allowRoot) {
        JsonElement value = args.get(key);
        if (value == null) {
            if (required) throw invalid("'" + key + "' is required");
            return "";
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid("'" + key + "' must be a string");
        String path = normalize(value.getAsString());
        if (path.isEmpty() && !allowRoot) throw new Failure("invalid_path", "'" + key + "' must name a file or folder inside the app's folder");
        return path;
    }
    private static boolean flag(JsonObject args, String key, boolean fallback) {
        JsonElement value = args.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw invalid("'" + key + "' must be true or false");
        return value.getAsBoolean();
    }
    private static long integer(JsonObject args, String key, long fallback, long min, long max) {
        JsonElement value = args.get(key);
        if (value == null) return fallback;
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new NumberFormatException();
            BigDecimal number = value.getAsBigDecimal();
            if (number.compareTo(BigDecimal.valueOf(min)) < 0 || number.compareTo(BigDecimal.valueOf(max)) > 0
                || (number.signum() != 0 && number.stripTrailingZeros().scale() > 0)) throw new NumberFormatException();
            return number.longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw invalid("'" + key + "' must be a whole number from " + min + " to " + max);
        }
    }
    private static String encoding(JsonObject args) {
        JsonElement value = args.get("encoding");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
            || !Set.of("utf8", "base64").contains(value.getAsString())) throw invalid("'encoding' must be 'utf8' or 'base64'");
        return value.getAsString();
    }
    private static Failure invalid(String message) { return new Failure("invalid_arguments", message); }
    private static String text(JsonObject o, String key, int limit) {
        JsonElement v = o.get(key);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString() || v.getAsString().length() > limit) throw new IllegalArgumentException("Invalid " + key);
        return v.getAsString();
    }
}

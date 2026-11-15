package app.pub.core;

import com.google.gson.*;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Contract of the files hardware capability: documents and folders on the phone that the user picks in Android's
 * system file picker (the Storage Access Framework), including what other apps offer through document providers
 * (Downloads, shared storage, Drive and similar). The user chooses every file and folder. Scripts never browse the
 * phone, never see content URIs, file system paths or provider names, and name what was picked only by an opaque
 * random handle ({@link #validHandle}); inside a picked folder they use relative paths ({@link #normalizePath}).
 *
 * Requests are ordinary {@code hardware.request} effects with capability "files": {@link HardwareContract#validate}
 * checks the envelope and operation inside UiPacket. Arguments are checked strictly when the host performs the request
 * ({@link #parse}); an invalid one becomes an error event (invalid_arguments or invalid_path) so a script can recover.
 * Replies are hardware events {@code {type:"hardware",id,capability:"files",event,data}}; the builders here keep every
 * data object within {@link #MAX_DATA_CHARS}, inside the runtime's event limit.
 */
public final class PhoneFilesContract {
    public static final String CAPABILITY = "files";
    public static final Set<String> OPERATIONS = Set.of("pick", "grants", "release", "list", "stat", "read", "write", "mkdir",
        "delete", "rename", "import", "export");
    /** Picker modes: one file, several files, a folder, or a new document the user names and places. */
    public static final Set<String> MODES = Set.of("open", "openMultiple", "folder", "create");
    /** Codes of the error events this capability adds; the broker's own (gesture_required, blocked, denied, busy, revoked) also apply. */
    public static final Set<String> CODES = Set.of("invalid_arguments", "invalid_path", "not_found", "exists", "not_empty",
        "is_directory", "not_directory", "read_only", "unavailable", "revoked", "quota_exceeded", "grant_limit", "busy");
    /** Event names of successful replies. A cancelled picker answers {@link #CANCELLED} with empty data. */
    public static final String PICKED = "picked", CANCELLED = "cancelled", GRANTS = "grants", RELEASED = "released",
        ENTRY = "entry", ENTRIES = "entries", DATA = "data", WRITTEN = "written", CREATED = "created", DELETED = "deleted",
        RENAMED = "renamed", IMPORTED = "imported", EXPORTED = "exported", ERROR = "error";
    public static final String FOLDER_MIME = "vnd.android.document/directory";
    public static final String DEFAULT_MIME = "application/octet-stream";
    /** Files and folders one app (or one preview) may hold at a time. */
    public static final int MAX_GRANTS = 32;
    /**
     * Distinct documents PUB keeps persisted access to for all saved apps together. Android limits persisted URI grants
     * per package (512 on current versions, 128 on old ones); PUB stops well below that and says so.
     */
    public static final int MAX_HOST_GRANTS = 256;
    /** Files one openMultiple pick returns at most. */
    public static final int MAX_PICK = 20;
    public static final int MAX_MIME_TYPES = 8;
    /** Names and relative paths inside a picked folder. */
    public static final int MAX_NAME_CHARS = 255, MAX_DEPTH = 8, MAX_PATH_CHARS = 1024;
    public static final long MAX_OFFSET = 1L << 40;
    /** Children one listing reads from a folder at most; larger folders are reported as incomplete. */
    public static final int MAX_FOLDER_CHILDREN = 10_000;
    /** Requests of one session that may wait for their replies; more are answered with busy. */
    public static final int MAX_PENDING = 8;
    /** Upper bound of every reply's data object, leaving room for the hardware event envelope below the event limit. */
    public static final int MAX_DATA_CHARS = FilesContract.MAX_REPLY_CHARS - 300;
    private static final int MAX_MESSAGE_CHARS = 200;
    private static final int MAX_MIME_CHARS = 127;
    private static final Pattern HANDLE = Pattern.compile("[0-9a-f]{24}");
    private static final String MIME_NAME = "[a-z0-9][a-z0-9!#$&^_.+-]{0,126}";
    private static final Pattern MIME = Pattern.compile(MIME_NAME + "/" + MIME_NAME);
    private static final Pattern MIME_FILTER = Pattern.compile("\\*/\\*|" + MIME_NAME + "/(\\*|" + MIME_NAME + ")");
    private static final Map<String, Set<String>> ARGUMENTS = Map.ofEntries(
        Map.entry("pick", Set.of("mode", "mimeTypes", "write", "max", "name", "mimeType")),
        Map.entry("grants", Set.of()),
        Map.entry("release", Set.of("handle")),
        Map.entry("list", Set.of("handle", "path", "offset")),
        Map.entry("stat", Set.of("handle", "path")),
        Map.entry("read", Set.of("handle", "path", "offset", "length", "encoding")),
        Map.entry("write", Set.of("handle", "path", "data", "encoding", "append")),
        Map.entry("mkdir", Set.of("handle", "path")),
        Map.entry("delete", Set.of("handle", "path", "recursive")),
        Map.entry("rename", Set.of("handle", "path", "name")),
        Map.entry("import", Set.of("handle", "path", "to", "overwrite")),
        Map.entry("export", Set.of("from", "handle", "path", "createName", "mimeType", "overwrite")));
    private static final Map<String, Set<String>> PICK_ARGUMENTS = Map.of(
        "open", Set.of("mode", "mimeTypes", "write"),
        "openMultiple", Set.of("mode", "mimeTypes", "write", "max"),
        "folder", Set.of("mode", "write"),
        "create", Set.of("mode", "name", "mimeType"));
    private PhoneFilesContract() {}

    /** An error the script receives as an error event with a stable {@link #CODES code}. */
    public static final class Failure extends RuntimeException {
        public final String code;
        public Failure(String code, String message) {
            super(message, null, false, false);
            if (!CODES.contains(code)) throw new IllegalArgumentException("Unknown files error code");
            this.code = code;
        }
        /** The same error from the private files contract (used by import and export). */
        public static Failure of(FilesContract.Failure failure) { return new Failure(failure.code, failure.getMessage()); }
    }

    /** A validated request. Absent strings are null except {@link #path}, which is "" for the picked folder itself. */
    public static final class Request {
        public final String id, operation;
        /** pick: the picker mode, its MIME filters (["*&#47;*"] by default), write access and the openMultiple limit. */
        public final String mode;
        public final List<String> mimeTypes;
        public final boolean write;
        public final int max;
        /** pick create: the suggested document name; rename: the new name. */
        public final String name;
        /** pick create and export with createName: the new document's MIME type, null to derive it from the name. */
        public final String mimeType;
        /** The handle the request works on; null for pick, grants and export with createName. */
        public final String handle;
        /** Relative path inside a picked folder ("" for the folder itself); only folder handles take a non-empty path. */
        public final String path;
        /** Byte offset for read, entries to skip for list. */
        public final long offset;
        public final int length;
        public final String encoding;
        /** Decoded write data, at most {@link FilesContract#MAX_CHUNK_BYTES}; never modified by the host. */
        public final byte[] data;
        public final boolean append, recursive, overwrite;
        /** import: the destination in the app's private folder. */
        public final String to;
        /** export: the source in the app's private folder, and the name of a new document the user places. */
        public final String from, createName;
        private Request(Draft d) {
            id = d.id; operation = d.operation; mode = d.mode; mimeTypes = d.mimeTypes; write = d.write; max = d.max;
            name = d.name; mimeType = d.mimeType; handle = d.handle; path = d.path; offset = d.offset; length = d.length;
            encoding = d.encoding; data = d.data; append = d.append; recursive = d.recursive; overwrite = d.overwrite;
            to = d.to; from = d.from; createName = d.createName;
        }
        /** True when the request opens the system picker: pick, and export to a new document. */
        public boolean opensPicker() { return operation.equals("pick") || (operation.equals("export") && createName != null); }
    }
    private static final class Draft {
        String id, operation, mode, name, mimeType, handle, path = "", encoding, to, from, createName;
        List<String> mimeTypes = List.of();
        boolean write, append, recursive, overwrite;
        int max, length;
        long offset;
        byte[] data;
    }

    /** True when a validated files request opens the system picker; decided from the envelope before arguments are parsed. */
    public static boolean opensPicker(JsonObject effect) {
        String operation = effect.get("operation").getAsString();
        return operation.equals("pick") || (operation.equals("export") && effect.getAsJsonObject("args").has("createName"));
    }

    /**
     * Validates the envelope (IllegalArgumentException, as in {@link HardwareContract#validate}) and then the arguments
     * of the operation: unknown, missing or mistyped arguments throw a {@link Failure} with invalid_arguments, bad paths
     * and names invalid_path.
     */
    public static Request parse(JsonObject effect) {
        HardwareContract.validate(effect);
        if (!CAPABILITY.equals(effect.get("capability").getAsString())) throw new IllegalArgumentException("Not a files request");
        Draft d = new Draft();
        d.id = effect.get("id").getAsString();
        d.operation = effect.get("operation").getAsString();
        JsonObject args = effect.getAsJsonObject("args");
        Set<String> allowed = ARGUMENTS.get(d.operation);
        for (String key : args.keySet()) {
            if (!allowed.contains(key)) throw invalid("Unknown argument '" + clip(key) + "' for " + d.operation);
        }
        switch (d.operation) {
            case "pick":
                d.mode = string(args, "mode", true, 16);
                if (!MODES.contains(d.mode)) throw invalid("'mode' must be 'open', 'openMultiple', 'folder' or 'create'");
                for (String key : args.keySet()) {
                    if (!PICK_ARGUMENTS.get(d.mode).contains(key)) throw invalid("'" + key + "' is not used with mode '" + d.mode + "'");
                }
                if (d.mode.equals("create")) {
                    d.name = name(args, "name");
                    d.mimeType = mimeType(args);
                    d.write = true;
                } else {
                    d.write = flag(args, "write", false);
                    if (!d.mode.equals("folder")) d.mimeTypes = mimeTypes(args);
                    d.max = d.mode.equals("openMultiple") ? (int) integer(args, "max", MAX_PICK, 1, MAX_PICK) : 1;
                }
                break;
            case "grants":
                break;
            case "release":
                d.handle = handle(args, true);
                break;
            case "list":
                d.handle = handle(args, true);
                d.path = path(args, "path", false);
                d.offset = integer(args, "offset", 0, 0, MAX_FOLDER_CHILDREN);
                break;
            case "stat":
                d.handle = handle(args, true);
                d.path = path(args, "path", false);
                break;
            case "read":
                d.handle = handle(args, true);
                d.path = path(args, "path", false);
                d.offset = integer(args, "offset", 0, 0, MAX_OFFSET);
                d.length = (int) integer(args, "length", FilesContract.MAX_CHUNK_BYTES, 1, FilesContract.MAX_CHUNK_BYTES);
                d.encoding = encoding(args);
                break;
            case "write":
                d.handle = handle(args, true);
                d.path = path(args, "path", false);
                d.encoding = encoding(args);
                JsonElement data = args.get("data");
                if (data == null || !data.isJsonPrimitive() || !data.getAsJsonPrimitive().isString()) throw invalid("'data' must be a string");
                try {
                    d.data = "utf8".equals(d.encoding) ? FilesContract.utf8Bytes(data.getAsString()) : FilesContract.base64Bytes(data.getAsString());
                } catch (FilesContract.Failure failure) { throw Failure.of(failure); }
                d.append = flag(args, "append", false);
                break;
            case "mkdir":
                d.handle = handle(args, true);
                d.path = path(args, "path", true);
                break;
            case "delete":
                d.handle = handle(args, true);
                d.path = path(args, "path", false);
                d.recursive = flag(args, "recursive", false);
                break;
            case "rename":
                d.handle = handle(args, true);
                d.path = path(args, "path", true);
                d.name = name(args, "name");
                break;
            case "import":
                d.handle = handle(args, true);
                d.path = path(args, "path", false);
                d.to = privatePath(args, "to");
                d.overwrite = flag(args, "overwrite", false);
                break;
            case "export":
                d.from = privatePath(args, "from");
                d.handle = handle(args, false);
                if (args.has("createName")) {
                    if (d.handle != null || args.has("path") || args.has("overwrite")) throw invalid("Use either 'createName' (a new document the user places) or 'handle', not both");
                    d.createName = name(args, "createName");
                    d.mimeType = mimeType(args);
                    d.write = true;
                } else {
                    if (d.handle == null) throw invalid("'handle' or 'createName' is required");
                    if (args.has("mimeType")) throw invalid("'mimeType' is only used with 'createName'");
                    d.path = path(args, "path", false);
                    d.overwrite = flag(args, "overwrite", false);
                }
                break;
            default:
                throw new IllegalArgumentException("Unsupported files operation");
        }
        return new Request(d);
    }

    /**
     * Normalizes a path inside a picked folder: "" or "." is the folder itself; otherwise '/'-separated names
     * ({@link #validName}) without a leading or trailing '/', at most 8 names and 1024 characters. The host finds each
     * name among the folder's children as the provider lists them, so no path can leave the picked folder.
     */
    public static String normalizePath(String path) {
        if (path.isEmpty() || path.equals(".")) return "";
        if (path.length() > MAX_PATH_CHARS) throw new Failure("invalid_path", "Paths are at most " + MAX_PATH_CHARS + " characters");
        String[] names = path.split("/", -1);
        if (names.length > MAX_DEPTH) throw new Failure("invalid_path", "Paths are at most " + MAX_DEPTH + " folders deep");
        for (String name : names) {
            if (name.isEmpty()) throw new Failure("invalid_path", "Use a relative path with single '/' separators and no leading or trailing '/'");
            if (!validName(name)) throw new Failure("invalid_path", "Names are 1-" + MAX_NAME_CHARS + " characters without '/' or control characters, and cannot be '.' or '..'");
        }
        return path;
    }

    /**
     * One name in a picked folder: 1-255 characters, never "." or "..", without '/', control characters, line or
     * paragraph separators or unpaired surrogates. Unlike private files, names may use any language and punctuation,
     * because they are the names of the user's own documents.
     */
    public static boolean validName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME_CHARS || name.equals(".") || name.equals("..")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || Character.isISOControl(c) || c == ' ' || c == ' ') return false;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= name.length() || !Character.isLowSurrogate(name.charAt(i + 1))) return false;
                i++;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    /** The path of [name] inside the folder at [parent], or null when it cannot be addressed by a path. */
    public static String child(String parent, String name) {
        if (!validName(name)) return null;
        String path = parent.isEmpty() ? name : parent + "/" + name;
        int depth = path.split("/", -1).length;
        return depth > MAX_DEPTH || path.length() > MAX_PATH_CHARS ? null : path;
    }
    public static String parent(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? "" : path.substring(0, slash); }
    public static String name(String path) { return path.substring(path.lastIndexOf('/') + 1); }

    /** A provider's display name made safe to show: control and separator characters become U+FFFD, at most 255 characters. */
    public static String displayName(String raw) {
        if (raw == null || raw.isEmpty()) return "Untitled";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length() && out.length() < MAX_NAME_CHARS; i++) {
            char c = raw.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < raw.length() && Character.isLowSurrogate(raw.charAt(i + 1))) {
                if (out.length() + 2 > MAX_NAME_CHARS) break;
                out.append(c).append(raw.charAt(++i));
            } else if (Character.isSurrogate(c) || Character.isISOControl(c) || c == ' ' || c == ' ') out.append('�');
            else out.append(c);
        }
        return out.toString();
    }

    /** A provider's MIME type normalized to a bounded lowercase type, or the folder or default type. */
    public static String mimeType(String raw, boolean folder) {
        if (folder) return FOLDER_MIME;
        String type = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        return type.length() <= MAX_MIME_CHARS && MIME.matcher(type).matches() ? type : DEFAULT_MIME;
    }

    public static boolean validHandle(String handle) { return handle != null && HANDLE.matcher(handle).matches(); }
    /** A new random handle: 96 bits, so handles cannot be guessed (and each is only valid for the app that holds it). */
    public static String newHandle(SecureRandom random) {
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
        return out.toString();
    }

    /** One picked file or folder as a script sees it. size is omitted when unknown (and in grants listings). */
    public static JsonObject item(String handle, String name, String mimeType, long size, boolean folder, boolean canWrite) {
        JsonObject item = new JsonObject();
        item.addProperty("handle", handle);
        item.addProperty("name", displayName(name));
        item.addProperty("kind", folder ? "folder" : "file");
        item.addProperty("mimeType", mimeType(mimeType, folder));
        if (!folder && size >= 0) item.addProperty("size", size);
        item.addProperty("canRead", true);
        item.addProperty("canWrite", canWrite);
        return item;
    }

    /** One file or folder found by stat or list; path is omitted when the name cannot be addressed ({@link #child}). */
    public static JsonObject entry(String name, String path, boolean folder, String mimeType, long size, long modified, boolean canWrite) {
        JsonObject entry = new JsonObject();
        entry.addProperty("name", displayName(name));
        if (path != null) entry.addProperty("path", path);
        entry.addProperty("kind", folder ? "folder" : "file");
        entry.addProperty("mimeType", mimeType(mimeType, folder));
        if (!folder && size >= 0) entry.addProperty("size", size);
        if (modified > 0) entry.addProperty("modified", modified);
        entry.addProperty("canWrite", canWrite);
        return entry;
    }

    /** Data of a picked event: the new items and whether more files were picked than could be kept. */
    public static JsonObject picked(List<JsonObject> items, boolean truncated) {
        JsonObject data = new JsonObject();
        JsonArray array = new JsonArray();
        items.forEach(array::add);
        data.add("items", array);
        data.addProperty("truncated", truncated);
        return data;
    }

    /** Data of a grants event: every item the app holds and the per-app limit. */
    public static JsonObject grants(List<JsonObject> items) {
        JsonObject data = picked(items, false);
        data.remove("truncated");
        data.addProperty("max", MAX_GRANTS);
        return data;
    }

    /**
     * Data of an entries event for entries already in listing order: from [offset], at most
     * {@link FilesContract#MAX_LIST_ENTRIES} and only as many as fit. total counts every entry read; incomplete says the
     * folder has more children than one listing reads.
     */
    public static JsonObject entries(List<JsonObject> entries, long offset, boolean incomplete) {
        JsonObject data = new JsonObject();
        int start = (int) Math.min(offset, entries.size());
        int budget = MAX_DATA_CHARS - 120;
        JsonArray page = new JsonArray();
        int used = 0, next = start;
        while (next < entries.size() && page.size() < FilesContract.MAX_LIST_ENTRIES) {
            int cost = entries.get(next).toString().length() + 1;
            if (used + cost > budget) break;
            page.add(entries.get(next));
            used += cost;
            next++;
        }
        data.add("entries", page);
        data.addProperty("total", entries.size());
        data.addProperty("truncated", next < entries.size());
        if (next < entries.size()) data.addProperty("nextOffset", next);
        if (incomplete) data.addProperty("incomplete", true);
        return data;
    }

    /**
     * Data of a read event for [count] bytes read at the request offset. [size] is the document size or -1 when the
     * provider does not say; [endOfData] says no bytes follow the ones read. utf8 returns only whole characters (see
     * {@link FilesContract#utf8Prefix}), so bytesRead can be smaller than count.
     */
    public static JsonObject read(Request request, byte[] bytes, int count, long size, boolean endOfData) {
        if (count < 0 || count > Math.min(bytes.length, FilesContract.MAX_CHUNK_BYTES)) throw new IllegalArgumentException("Invalid read size");
        String text;
        int used;
        if ("base64".equals(request.encoding)) {
            text = Base64.getEncoder().encodeToString(Arrays.copyOf(bytes, count));
            used = count;
        } else {
            FilesContract.TextChunk chunk = FilesContract.utf8Prefix(bytes, count, endOfData, FilesContract.MAX_TEXT_CHARS);
            if (chunk.bytes == 0 && count > 0) throw invalid(chunk.invalid
                ? "The bytes at this offset are not UTF-8 text. Read them with encoding 'base64'."
                : "'length' is too small for the next character");
            text = chunk.text;
            used = chunk.bytes;
        }
        JsonObject data = new JsonObject();
        data.addProperty("data", text);
        data.addProperty("offset", request.offset);
        data.addProperty("bytesRead", used);
        if (size >= 0) data.addProperty("size", size);
        data.addProperty("eof", (endOfData && used == count) || (size >= 0 && request.offset + used >= size));
        return data;
    }

    /** Data of an error event. Messages are fixed host text (never provider errors, URIs or paths of the phone). */
    public static JsonObject error(String code, String message) {
        if (!CODES.contains(code)) throw new IllegalArgumentException("Unknown files error code");
        JsonObject data = new JsonObject();
        data.addProperty("code", code);
        String text = message == null || message.isBlank() ? "The file request could not be completed" : message;
        data.addProperty("message", text.length() > MAX_MESSAGE_CHARS ? text.substring(0, MAX_MESSAGE_CHARS) : text);
        return data;
    }
    public static JsonObject error(Failure failure) { return error(failure.code, failure.getMessage()); }

    /** True when a reply's data object stays within {@link #MAX_DATA_CHARS}. */
    public static boolean fits(JsonObject data) { return data.toString().length() <= MAX_DATA_CHARS; }

    private static String handle(JsonObject args, boolean required) {
        JsonElement value = args.get("handle");
        if (value == null) {
            if (required) throw invalid("'handle' is required");
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || !validHandle(value.getAsString())) {
            throw invalid("'handle' must be a handle from a picked or grants event");
        }
        return value.getAsString();
    }
    private static String path(JsonObject args, String key, boolean required) {
        JsonElement value = args.get(key);
        if (value == null) {
            if (required) throw invalid("'" + key + "' is required");
            return "";
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid("'" + key + "' must be a string");
        String path = normalizePath(value.getAsString());
        if (path.isEmpty() && required) throw new Failure("invalid_path", "'" + key + "' must name a file or folder inside the picked folder");
        return path;
    }
    private static String privatePath(JsonObject args, String key) {
        JsonElement value = args.get(key);
        if (value == null) throw invalid("'" + key + "' is required");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid("'" + key + "' must be a string");
        String path;
        try { path = FilesContract.normalize(value.getAsString()); } catch (FilesContract.Failure failure) { throw Failure.of(failure); }
        if (path.isEmpty()) throw new Failure("invalid_path", "'" + key + "' must name a file in the app's private folder");
        if (key.equals("to") && FilesContract.inPhotos(path)) throw new Failure("read_only", "Nothing can be imported into the photos folder");
        return path;
    }
    private static String name(JsonObject args, String key) {
        String name = string(args, key, true, MAX_PATH_CHARS);
        if (!validName(name)) throw new Failure("invalid_path", "'" + key + "' must be 1-" + MAX_NAME_CHARS + " characters without '/' or control characters");
        return name;
    }
    private static String mimeType(JsonObject args) {
        if (!args.has("mimeType")) return null;
        String type = string(args, "mimeType", true, MAX_MIME_CHARS).toLowerCase(Locale.ROOT);
        if (!MIME.matcher(type).matches()) throw invalid("'mimeType' must be a MIME type such as 'text/plain'");
        return type;
    }
    private static List<String> mimeTypes(JsonObject args) {
        JsonElement value = args.get("mimeTypes");
        if (value == null) return List.of("*/*");
        if (!value.isJsonArray() || value.getAsJsonArray().isEmpty() || value.getAsJsonArray().size() > MAX_MIME_TYPES) {
            throw invalid("'mimeTypes' must be a list of 1-" + MAX_MIME_TYPES + " MIME types such as 'text/*'");
        }
        List<String> types = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw invalid("'mimeTypes' must contain strings");
            String type = item.getAsString().toLowerCase(Locale.ROOT);
            if (type.length() > MAX_MIME_CHARS || !MIME_FILTER.matcher(type).matches()) throw invalid("'mimeTypes' entries must look like 'text/plain', 'image/*' or '*/*'");
            if (!types.contains(type)) types.add(type);
        }
        return List.copyOf(types);
    }
    private static String string(JsonObject args, String key, boolean required, int limit) {
        JsonElement value = args.get(key);
        if (value == null) {
            if (required) throw invalid("'" + key + "' is required");
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().length() > limit) {
            throw invalid("'" + key + "' must be a string of at most " + limit + " characters");
        }
        return value.getAsString();
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
    private static String clip(String key) { return key.substring(0, Math.min(32, key.length())); }
    private static Failure invalid(String message) { return new Failure("invalid_arguments", message); }
}

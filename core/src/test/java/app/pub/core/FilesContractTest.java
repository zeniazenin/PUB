package app.pub.core;

import com.google.gson.*;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Boundary tests for untrusted generated file requests: envelope, paths, every operation's arguments and reply sizes. */
public class FilesContractTest {
    private static final String LONG_ID = "r".repeat(64);
    private JsonObject request(String operation, JsonObject args) {
        JsonObject request = new JsonObject();
        request.addProperty("type", "files.request");
        request.addProperty("id", "req_1");
        request.addProperty("operation", operation);
        request.add("args", args);
        return request;
    }
    private JsonObject args(Object... pairs) {
        JsonObject args = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value instanceof String) args.addProperty((String) pairs[i], (String) value);
            else if (value instanceof Boolean) args.addProperty((String) pairs[i], (Boolean) value);
            else if (value instanceof Number) args.addProperty((String) pairs[i], (Number) value);
            else args.add((String) pairs[i], (JsonElement) value);
        }
        return args;
    }
    private FilesContract.Request parse(String operation, Object... pairs) { return FilesContract.parse(request(operation, args(pairs))); }
    private String code(String operation, Object... pairs) {
        try { parse(operation, pairs); return "ok"; } catch (FilesContract.Failure failure) { return failure.code; }
    }
    private String packet(JsonObject effect) {
        JsonObject root = new JsonObject();
        JsonObject ui = new JsonObject(); ui.addProperty("type", "text"); ui.addProperty("text", "Files app");
        root.add("ui", ui);
        JsonArray effects = new JsonArray(); effects.add(effect); root.add("effects", effects);
        return root.toString();
    }

    @Test public void filesCapabilityIsDeclarableAndRequestsAreUiPacketEffects() {
        assertTrue(HardwareContract.CAPABILITIES.contains("storage.files"));
        String bundle = "{\"name\":\"Notes\",\"description\":\"Notes in files\",\"source\":\"function dispatch(e){return '{}';}\",\"capabilities\":[\"storage.files\",\"storage.project\"]}";
        assertEquals(Set.of("storage.files", "storage.project"), ProjectBundle.parse(bundle).capabilities);
        for (String operation : FilesContract.OPERATIONS) {
            UiPacket packet = UiPacket.parse(packet(request(operation, new JsonObject())));
            assertEquals(operation, packet.effects.get(0).getAsJsonObject().get("operation").getAsString());
        }
    }

    @Test public void envelopeRejectsHostFieldsBadIdsUnknownOperationsAndNonObjectArguments() {
        JsonObject extra = request("list", new JsonObject()); extra.addProperty("appId", "another-app");
        assertThrows(IllegalArgumentException.class, () -> UiPacket.parse(packet(extra)));
        JsonObject capability = request("list", new JsonObject()); capability.addProperty("capability", "storage.files");
        assertThrows(IllegalArgumentException.class, () -> FilesContract.validate(capability));
        for (String id : new String[]{"", "../x", "a b", "x".repeat(65)}) {
            JsonObject bad = request("list", new JsonObject()); bad.addProperty("id", id);
            assertThrows(id, IllegalArgumentException.class, () -> FilesContract.validate(bad));
        }
        JsonObject numericId = request("list", new JsonObject()); numericId.addProperty("id", 7);
        assertThrows(IllegalArgumentException.class, () -> FilesContract.validate(numericId));
        for (String operation : new String[]{"open", "chmod", "symlink", "LIST", "export"}) {
            assertThrows(operation, IllegalArgumentException.class, () -> FilesContract.validate(request(operation, new JsonObject())));
        }
        JsonObject noArgs = request("usage", new JsonObject()); noArgs.remove("args");
        assertThrows(IllegalArgumentException.class, () -> FilesContract.validate(noArgs));
        for (JsonElement args : new JsonElement[]{JsonNull.INSTANCE, new JsonArray(), new JsonPrimitive("photos")}) {
            JsonObject bad = request("list", new JsonObject()); bad.add("args", args);
            assertThrows(IllegalArgumentException.class, () -> FilesContract.validate(bad));
        }
        JsonObject huge = request("write", args("path", "a.txt", "encoding", "utf8", "data", "x".repeat(ScriptEngine.MAX_MESSAGE)));
        assertThrows(IllegalArgumentException.class, () -> FilesContract.validate(huge));
        JsonObject id = request("stat", args("path", "a")); id.addProperty("id", LONG_ID);
        assertEquals(LONG_ID, FilesContract.parse(id).id);
    }

    @Test public void pathsAreRelativeBoundedAndCannotEscapeOrUseHostNames() {
        assertEquals("", FilesContract.normalize(""));
        assertEquals("", FilesContract.normalize("."));
        for (String valid : new String[]{"notes.txt", "Notes/Trip (2026)/day-1_a.json", "a b", "README", "x.", "photos/x.jpg",
            "n".repeat(64), String.join("/", Collections.nCopies(8, "d")), ("a".repeat(63) + "/").repeat(4).substring(0, 255) + "b"}) {
            assertEquals(valid, FilesContract.normalize(valid));
        }
        assertEquals(256, FilesContract.normalize(("a".repeat(63) + "/").repeat(4).substring(0, 255) + "b").length());
        for (String invalid : new String[]{"/notes.txt", "notes/", "a//b", "..", "../x", "a/../b", "a/./b", "./a", ".hidden", "a/.b",
            "a\\b", "C:", "a:b", "a*b", "a?b", "é.txt", "заметки", "a\u0000b", "a\nb", "a\tb", "~",
            "n".repeat(65), String.join("/", Collections.nCopies(9, "d")), "a".repeat(64) + "/" + "b".repeat(64) + "/" + "c".repeat(64) + "/" + "d".repeat(64)}) {
            FilesContract.Failure failure = assertThrows(invalid, FilesContract.Failure.class, () -> FilesContract.normalize(invalid));
            assertEquals("invalid_path", failure.code);
            assertEquals("invalid_path", code("stat", "path", invalid));
        }
        assertTrue(FilesContract.inPhotos("photos")); assertTrue(FilesContract.inPhotos("photos/a.jpg"));
        assertFalse(FilesContract.inPhotos("photos2")); assertFalse(FilesContract.inPhotos("Photos/a.jpg")); assertFalse(FilesContract.inPhotos("a/photos"));
        assertEquals("a/b", FilesContract.parent("a/b/c.txt")); assertEquals("", FilesContract.parent("c.txt"));
        assertEquals("c.txt", FilesContract.name("a/b/c.txt"));
    }

    @Test public void listAndStatArgumentsHaveDefaultsAndStrictTypes() {
        FilesContract.Request list = parse("list");
        assertEquals("", list.path); assertFalse(list.recursive); assertEquals(0, list.offset);
        FilesContract.Request deep = parse("list", "path", "a/b", "recursive", true, "offset", 500);
        assertEquals("a/b", deep.path); assertTrue(deep.recursive); assertEquals(500, deep.offset);
        assertEquals("", parse("list", "path", ".").path);
        assertEquals("invalid_arguments", code("list", "recursive", "yes"));
        assertEquals("invalid_arguments", code("list", "offset", -1));
        assertEquals("invalid_arguments", code("list", "offset", 1.5));
        assertEquals("invalid_arguments", code("list", "offset", 10_001));
        assertEquals("invalid_arguments", code("list", "path", 3));
        assertEquals("invalid_arguments", code("list", "filter", "*.txt"));
        assertEquals("", parse("stat", "path", "").path);
        assertEquals("invalid_arguments", code("stat"));
        assertEquals("invalid_arguments", code("stat", "path", "a", "recursive", true));
    }

    @Test public void readArgumentsBoundOffsetLengthAndEncoding() {
        FilesContract.Request read = parse("read", "path", "a.txt", "encoding", "utf8");
        assertEquals(0, read.offset); assertEquals(FilesContract.MAX_CHUNK_BYTES, read.length); assertEquals("utf8", read.encoding);
        FilesContract.Request chunk = parse("read", "path", "a.bin", "encoding", "base64", "offset", 24_000, "length", 1);
        assertEquals(24_000, chunk.offset); assertEquals(1, chunk.length);
        assertEquals(FilesContract.QUOTA_BYTES, parse("read", "path", "a", "encoding", "utf8", "offset", FilesContract.QUOTA_BYTES).offset);
        assertEquals(1000, parse("read", "path", "a", "encoding", "utf8", "offset", new JsonPrimitive(1e3)).offset);
        assertEquals("invalid_arguments", code("read", "path", "a.txt"));
        assertEquals("invalid_arguments", code("read", "path", "a.txt", "encoding", "latin1"));
        assertEquals("invalid_arguments", code("read", "path", "a.txt", "encoding", "utf8", "length", 0));
        assertEquals("invalid_arguments", code("read", "path", "a.txt", "encoding", "utf8", "length", 12_001));
        assertEquals("invalid_arguments", code("read", "path", "a.txt", "encoding", "utf8", "offset", -5));
        assertEquals("invalid_arguments", code("read", "path", "a.txt", "encoding", "utf8", "offset", "0"));
        assertEquals("invalid_arguments", code("read", "path", "a.txt", "encoding", "utf8", "offset", FilesContract.QUOTA_BYTES + 1));
        assertEquals("invalid_arguments", code("read", "encoding", "utf8"));
        assertEquals("invalid_path", code("read", "path", "", "encoding", "utf8"));
    }

    @Test public void writeArgumentsDecodeDataWithinTheChunkLimit() {
        FilesContract.Request text = parse("write", "path", "n.txt", "data", "é€🎸", "encoding", "utf8");
        assertArrayEquals("é€🎸".getBytes(StandardCharsets.UTF_8), text.data);
        assertFalse(text.append); assertTrue(text.createParents);
        assertEquals(12_000, parse("write", "path", "a", "data", "x".repeat(12_000), "encoding", "utf8").data.length);
        assertEquals("invalid_arguments", code("write", "path", "a", "data", "x".repeat(12_001), "encoding", "utf8"));
        assertEquals("invalid_arguments", code("write", "path", "a", "data", "€".repeat(4_001), "encoding", "utf8"));
        assertEquals(12_000, parse("write", "path", "a", "data", "€".repeat(4_000), "encoding", "utf8").data.length);
        assertEquals("invalid_arguments", code("write", "path", "a", "data", "broken \uD83C", "encoding", "utf8"));
        byte[] binary = new byte[12_000]; new Random(7).nextBytes(binary);
        String encoded = Base64.getEncoder().encodeToString(binary);
        assertEquals(16_000, encoded.length());
        assertArrayEquals(binary, parse("write", "path", "a.bin", "data", encoded, "encoding", "base64").data);
        assertArrayEquals(new byte[]{1, 2}, parse("write", "path", "a.bin", "data", "AQI", "encoding", "base64").data);
        assertEquals("invalid_arguments", code("write", "path", "a", "data", Base64.getEncoder().encodeToString(new byte[12_001]), "encoding", "base64"));
        assertEquals("invalid_arguments", code("write", "path", "a", "data", "not base64!", "encoding", "base64"));
        assertEquals("invalid_arguments", code("write", "path", "a", "data", "AQ I=", "encoding", "base64"));
        assertEquals("invalid_arguments", code("write", "path", "a", "encoding", "utf8"));
        assertEquals("invalid_arguments", code("write", "path", "a", "data", new JsonArray(), "encoding", "utf8"));
        assertEquals("invalid_arguments", code("write", "path", "a", "data", "x"));
        assertEquals("invalid_arguments", code("write", "path", "a", "data", "x", "encoding", "utf8", "append", 1));
        assertEquals("invalid_arguments", code("write", "path", "a", "data", "x", "encoding", "utf8", "mode", "w"));
        FilesContract.Request append = parse("write", "path", "log.txt", "data", "", "encoding", "utf8", "append", true, "createParents", false);
        assertTrue(append.append); assertFalse(append.createParents); assertEquals(0, append.data.length);
        assertEquals("invalid_path", code("write", "path", ".", "data", "x", "encoding", "utf8"));
    }

    @Test public void folderAndUsageOperationsValidateTheirArguments() {
        assertEquals("a/b", parse("mkdir", "path", "a/b").path);
        assertEquals("invalid_path", code("mkdir", "path", ""));
        FilesContract.Request delete = parse("delete", "path", "a");
        assertFalse(delete.recursive);
        assertTrue(parse("delete", "path", "a", "recursive", true).recursive);
        assertEquals("invalid_path", code("delete", "path", "."));
        for (String operation : new String[]{"move", "copy"}) {
            FilesContract.Request move = parse(operation, "from", "a/x.txt", "to", "b/x.txt");
            assertEquals("a/x.txt", move.from); assertEquals("b/x.txt", move.to); assertFalse(move.overwrite); assertNull(move.path);
            assertTrue(parse(operation, "from", "a", "to", "b", "overwrite", true).overwrite);
            assertEquals("invalid_arguments", code(operation, "from", "a", "to", "a"));
            assertEquals("invalid_arguments", code(operation, "from", "a", "to", "a/b"));
            assertEquals("ok", code(operation, "from", "a/b", "to", "a"));
            assertEquals("ok", code(operation, "from", "a", "to", "ab"));
            assertEquals("invalid_arguments", code(operation, "from", "a"));
            assertEquals("invalid_path", code(operation, "from", "", "to", "b"));
            assertEquals("invalid_path", code(operation, "from", "a", "to", "../b"));
        }
        assertEquals("ok", code("usage"));
        assertEquals("invalid_arguments", code("usage", "path", "a"));
    }

    @Test public void repliesEchoTheRequestAndUseStableErrorCodes() {
        JsonObject effect = request("stat", args("path", "a")); effect.addProperty("id", LONG_ID);
        FilesContract.Request stat = FilesContract.parse(effect);
        JsonObject ok = FilesContract.ok(stat);
        assertEquals(Set.of("type", "id", "operation", "ok"), ok.keySet());
        assertEquals("files", ok.get("type").getAsString()); assertEquals(LONG_ID, ok.get("id").getAsString()); assertTrue(ok.get("ok").getAsBoolean());
        JsonObject error = FilesContract.failure(stat, new FilesContract.Failure("not_found", "No such file: a"));
        assertEquals(Set.of("type", "id", "operation", "ok", "code", "message"), error.keySet());
        assertFalse(error.get("ok").getAsBoolean()); assertEquals("not_found", error.get("code").getAsString()); assertEquals("stat", error.get("operation").getAsString());
        assertEquals(200, FilesContract.failure("x", "read", "unavailable", "m".repeat(500)).get("message").getAsString().length());
        assertFalse(FilesContract.failure("x", "read", "unavailable", null).get("message").getAsString().isBlank());
        assertThrows(IllegalArgumentException.class, () -> new FilesContract.Failure("permission_denied", "x"));
        assertThrows(IllegalArgumentException.class, () -> FilesContract.failure("x", "read", "eperm", "x"));
        assertEquals(11, FilesContract.CODES.size());
        JsonObject entry = FilesContract.entry("a/b.txt", false, 12, 1_700_000_000_000L);
        assertEquals("b.txt", entry.get("name").getAsString()); assertEquals("file", entry.get("type").getAsString()); assertEquals(12, entry.get("size").getAsLong());
        assertEquals(0, FilesContract.entry("a", true, 99, 1).get("size").getAsLong());
        assertEquals("directory", FilesContract.entry("", true, 0, 0).get("type").getAsString());
    }

    @Test public void readRepliesStayWithinTheEventLimit() {
        JsonObject effect = request("read", args("path", "a.bin", "encoding", "base64")); effect.addProperty("id", LONG_ID);
        FilesContract.Request base64 = FilesContract.parse(effect);
        byte[] binary = new byte[FilesContract.MAX_CHUNK_BYTES]; new Random(3).nextBytes(binary);
        JsonObject reply = FilesContract.readReply(base64, binary, binary.length, Long.MAX_VALUE / 4);
        assertTrue(reply.toString().length() <= FilesContract.MAX_REPLY_CHARS);
        assertArrayEquals(binary, Base64.getDecoder().decode(reply.get("data").getAsString()));
        assertEquals(12_000, reply.get("bytesRead").getAsInt()); assertFalse(reply.get("eof").getAsBoolean());

        // Worst case for text: every byte needs a six-character JSON escape.
        effect = request("read", args("path", "a.txt", "encoding", "utf8")); effect.addProperty("id", LONG_ID);
        FilesContract.Request text = FilesContract.parse(effect);
        for (byte fill : new byte[]{0x01, '"', '<', 'a'}) {
            byte[] bytes = new byte[FilesContract.MAX_CHUNK_BYTES]; Arrays.fill(bytes, fill);
            JsonObject chunk = FilesContract.readReply(text, bytes, bytes.length, bytes.length);
            int read = chunk.get("bytesRead").getAsInt();
            assertTrue(chunk.toString().length() <= FilesContract.MAX_REPLY_CHARS);
            assertTrue(chunk.toString().length() < ScriptEngine.MAX_MESSAGE);
            assertEquals(new String(bytes, 0, read, StandardCharsets.UTF_8), chunk.get("data").getAsString());
            assertEquals(read == bytes.length, chunk.get("eof").getAsBoolean());
            assertTrue(read >= FilesContract.MAX_TEXT_CHARS / 6);
        }
        // Mixed multi-byte text: whole characters only, with the actual byte count.
        byte[] mixed = ("x" + "aé€🎸".repeat(2_000)).getBytes(StandardCharsets.UTF_8);
        JsonObject chunk = FilesContract.readReply(text, mixed, FilesContract.MAX_CHUNK_BYTES, mixed.length);
        int read = chunk.get("bytesRead").getAsInt();
        assertEquals(11_997, read); // The four-byte character at 11 997 would end after the 12 000-byte chunk.
        assertArrayEquals(Arrays.copyOf(mixed, read), chunk.get("data").getAsString().getBytes(StandardCharsets.UTF_8));
        assertFalse(chunk.get("eof").getAsBoolean());
        // Past the end: empty data at end of file.
        JsonObject end = FilesContract.readReply(parse("read", "path", "a", "encoding", "utf8", "offset", 10), new byte[0], 0, 10);
        assertEquals("", end.get("data").getAsString()); assertEquals(0, end.get("bytesRead").getAsInt()); assertTrue(end.get("eof").getAsBoolean());
        // Binary bytes are refused as text; a too-short length cannot cut a character.
        FilesContract.Failure binaryText = assertThrows(FilesContract.Failure.class, () -> FilesContract.readReply(text, new byte[]{(byte) 0xFF, 'a'}, 2, 2));
        assertEquals("invalid_arguments", binaryText.code);
        FilesContract.Request one = parse("read", "path", "a", "encoding", "utf8", "length", 1);
        assertEquals("invalid_arguments", assertThrows(FilesContract.Failure.class, () -> FilesContract.readReply(one, new byte[]{(byte) 0xC3}, 1, 2)).code);
    }

    @Test public void listRepliesAreCappedSizedAndResumable() {
        // Entries reduced to their path, so the count cap rather than the size cap ends the first page.
        List<JsonObject> small = new ArrayList<>();
        for (int i = 0; i < 600; i++) { JsonObject entry = new JsonObject(); entry.addProperty("path", String.format("f%03d", i)); small.add(entry); }
        JsonObject first = FilesContract.listReply(parse("list"), small);
        assertEquals(FilesContract.MAX_LIST_ENTRIES, first.getAsJsonArray("entries").size());
        assertTrue(first.get("truncated").getAsBoolean()); assertEquals(500, first.get("nextOffset").getAsInt()); assertEquals(600, first.get("total").getAsInt());
        JsonObject rest = FilesContract.listReply(parse("list", "offset", 500), small);
        assertEquals(100, rest.getAsJsonArray("entries").size()); assertFalse(rest.get("truncated").getAsBoolean()); assertFalse(rest.has("nextOffset"));
        assertEquals("f500", rest.getAsJsonArray("entries").get(0).getAsJsonObject().get("path").getAsString());
        assertEquals(0, FilesContract.listReply(parse("list", "offset", 9_000), small).getAsJsonArray("entries").size());

        // Longest names: fewer entries per reply, still below the limit, and every entry is returned exactly once.
        List<JsonObject> large = new ArrayList<>();
        String folder = "d".repeat(64) + "/" + "e".repeat(64) + "/" + "f".repeat(60);
        for (int i = 0; i < 500; i++) large.add(FilesContract.entry(folder + "/" + String.format("%04d", i) + "n".repeat(60), true, 0, Long.MAX_VALUE / 2));
        JsonObject effect = request("list", args("recursive", true)); effect.addProperty("id", LONG_ID);
        Set<String> seen = new HashSet<>();
        long offset = 0;
        int replies = 0;
        while (true) {
            effect.getAsJsonObject("args").addProperty("offset", offset);
            JsonObject page = FilesContract.listReply(FilesContract.parse(effect), large);
            assertTrue(page.toString().length() <= FilesContract.MAX_REPLY_CHARS);
            page.getAsJsonArray("entries").forEach(e -> assertTrue(seen.add(e.getAsJsonObject().get("path").getAsString())));
            replies++;
            if (!page.get("truncated").getAsBoolean()) break;
            assertTrue(page.get("nextOffset").getAsLong() > offset);
            offset = page.get("nextOffset").getAsLong();
        }
        assertEquals(500, seen.size()); assertTrue(replies > 1);
    }

    @Test public void pathOrderKeepsFolderContentsTogether() {
        List<String> paths = new ArrayList<>(List.of("b", "a b", "a/z", "a", "a/b/c", "a-b", "a/b", "photos/x.jpg", "photos", "A"));
        paths.sort(FilesContract.PATH_ORDER);
        assertEquals(List.of("A", "a", "a/b", "a/b/c", "a/z", "a b", "a-b", "b", "photos", "photos/x.jpg"), paths);
    }
}

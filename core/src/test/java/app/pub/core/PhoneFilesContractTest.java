package app.pub.core;

import com.google.gson.*;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import static org.junit.Assert.*;

/** Boundary tests for untrusted requests to the files capability (documents the user picks): arguments, paths and reply sizes. */
public class PhoneFilesContractTest {
    private static final String HANDLE = "0123456789abcdef01234567";
    private JsonObject request(String operation, JsonObject args) {
        JsonObject request = new JsonObject();
        request.addProperty("type", "hardware.request");
        request.addProperty("id", "req_1");
        request.addProperty("capability", "files");
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
    private PhoneFilesContract.Request parse(String operation, Object... pairs) { return PhoneFilesContract.parse(request(operation, args(pairs))); }
    private String code(String operation, Object... pairs) {
        try { parse(operation, pairs); return "ok"; } catch (PhoneFilesContract.Failure failure) { return failure.code; }
    }
    private static JsonArray array(String... values) { JsonArray a = new JsonArray(); for (String v : values) a.add(v); return a; }
    private String packet(JsonObject effect) {
        JsonObject root = new JsonObject();
        JsonObject ui = new JsonObject(); ui.addProperty("type", "text"); ui.addProperty("text", "Files app");
        root.add("ui", ui);
        JsonArray effects = new JsonArray(); effects.add(effect); root.add("effects", effects);
        return root.toString();
    }

    @Test public void filesIsAHardwareCapabilityWhoseRequestsAreUiPacketEffects() {
        assertTrue(HardwareContract.CAPABILITIES.contains("files"));
        assertEquals(PhoneFilesContract.OPERATIONS, HardwareContract.OPERATIONS.get("files"));
        String bundle = "{\"name\":\"Editor\",\"description\":\"Edits picked files\",\"source\":\"function dispatch(e){return '{}';}\",\"capabilities\":[\"files\",\"storage.files\"]}";
        assertEquals(Set.of("files", "storage.files"), ProjectBundle.parse(bundle).capabilities);
        for (String operation : PhoneFilesContract.OPERATIONS) {
            UiPacket packet = UiPacket.parse(packet(request(operation, new JsonObject())));
            assertEquals(operation, packet.effects.get(0).getAsJsonObject().get("operation").getAsString());
        }
        for (String operation : new String[]{"open", "browse", "path", "uri", "PICK", "move"}) {
            assertThrows(operation, IllegalArgumentException.class, () -> HardwareContract.validate(request(operation, new JsonObject())));
        }
        JsonObject extra = request("grants", new JsonObject()); extra.addProperty("uri", "content://x");
        assertThrows(IllegalArgumentException.class, () -> UiPacket.parse(packet(extra)));
    }

    @Test public void fileWritesMayCarryEscapedTextButEnvelopesStayBounded() {
        // 8000 newlines escape to 16,000 characters plus quotes: over the 20,000 devices get only with other arguments.
        JsonObject write = request("write", args("handle", HANDLE, "encoding", "utf8", "data", "\n".repeat(11_000)));
        HardwareContract.validate(write);
        JsonObject usb = request("write", args("data", "\n".repeat(11_000))); usb.addProperty("capability", "usb");
        assertThrows(IllegalArgumentException.class, () -> HardwareContract.validate(usb));
        JsonObject huge = request("write", args("handle", HANDLE, "encoding", "utf8", "data", "x".repeat(ScriptEngine.MAX_MESSAGE)));
        assertThrows(IllegalArgumentException.class, () -> HardwareContract.validate(huge));
        assertThrows(IllegalArgumentException.class, () -> PhoneFilesContract.parse(huge));
        assertEquals("invalid_arguments", code("write", "handle", HANDLE, "encoding", "utf8", "data", "x".repeat(12_001)));
    }

    @Test public void pickModesTakeOnlyTheirOwnArguments() {
        PhoneFilesContract.Request open = parse("pick", "mode", "open");
        assertEquals(List.of("*/*"), open.mimeTypes); assertFalse(open.write); assertEquals(1, open.max); assertTrue(open.opensPicker());
        PhoneFilesContract.Request multiple = parse("pick", "mode", "openMultiple", "mimeTypes", array("Text/*", "application/pdf", "text/*"), "max", 5, "write", true);
        assertEquals(List.of("text/*", "application/pdf"), multiple.mimeTypes); assertEquals(5, multiple.max); assertTrue(multiple.write);
        assertEquals(PhoneFilesContract.MAX_PICK, parse("pick", "mode", "openMultiple").max);
        PhoneFilesContract.Request folder = parse("pick", "mode", "folder", "write", true);
        assertTrue(folder.write); assertTrue(folder.mimeTypes.isEmpty());
        PhoneFilesContract.Request create = parse("pick", "mode", "create", "name", "Grocery list (May).txt", "mimeType", "text/plain");
        assertEquals("Grocery list (May).txt", create.name); assertEquals("text/plain", create.mimeType); assertTrue(create.write);
        assertNull(parse("pick", "mode", "create", "name", "Résumé.md").mimeType);

        assertEquals("invalid_arguments", code("pick"));
        assertEquals("invalid_arguments", code("pick", "mode", "camera"));
        assertEquals("invalid_arguments", code("pick", "mode", "open", "max", 3));
        assertEquals("invalid_arguments", code("pick", "mode", "folder", "mimeTypes", array("*/*")));
        assertEquals("invalid_arguments", code("pick", "mode", "create", "write", false, "name", "a.txt"));
        assertEquals("invalid_arguments", code("pick", "mode", "create"));
        assertEquals("invalid_path", code("pick", "mode", "create", "name", "../a.txt"));
        assertEquals("invalid_path", code("pick", "mode", "create", "name", ".."));
        assertEquals("invalid_arguments", code("pick", "mode", "create", "name", "a.txt", "mimeType", "text/*"));
        assertEquals("invalid_arguments", code("pick", "mode", "openMultiple", "max", 21));
        assertEquals("invalid_arguments", code("pick", "mode", "openMultiple", "max", 0));
        assertEquals("invalid_arguments", code("pick", "mode", "openMultiple", "max", 2.5));
        assertEquals("invalid_arguments", code("pick", "mode", "open", "mimeTypes", array()));
        assertEquals("invalid_arguments", code("pick", "mode", "open", "mimeTypes", array("a/b", "a/c", "a/d", "a/e", "a/f", "a/g", "a/h", "a/i", "a/j")));
        for (String bad : new String[]{"text", "*/plain", "text/", "text/plain; charset=utf-8", "../x", "content://x/y"}) {
            assertEquals(bad, "invalid_arguments", code("pick", "mode", "open", "mimeTypes", array(bad)));
        }
        assertEquals("invalid_arguments", code("pick", "mode", "open", "write", "yes"));
        assertEquals("invalid_arguments", code("pick", "mode", "open", "uri", "content://x"));
    }

    @Test public void handlesAreOpaqueRandomIdsAndRequired() {
        SecureRandom random = new SecureRandom();
        Set<String> handles = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String handle = PhoneFilesContract.newHandle(random);
            assertTrue(handle, PhoneFilesContract.validHandle(handle));
            handles.add(handle);
        }
        assertEquals(100, handles.size());
        assertEquals(HANDLE, parse("stat", "handle", HANDLE).handle);
        for (String operation : new String[]{"release", "list", "stat", "mkdir", "delete", "rename", "import"}) {
            assertEquals(operation, "invalid_arguments", code(operation));
        }
        for (String bad : new String[]{"", "0123456789ABCDEF01234567", "0123456789abcdef0123456", "content://a/b", "../0123456789abcdef0123"}) {
            assertEquals(bad, "invalid_arguments", code("stat", "handle", bad));
        }
        assertEquals("invalid_arguments", code("stat", "handle", 12));
        assertEquals("ok", code("grants"));
        assertEquals("invalid_arguments", code("grants", "handle", HANDLE));
    }

    @Test public void folderPathsAllowRealDocumentNamesButNeverLeaveTheFolder() {
        assertEquals("", PhoneFilesContract.normalizePath(""));
        assertEquals("", PhoneFilesContract.normalizePath("."));
        for (String ok : new String[]{"Résumé 2024.pdf", "Mom's recipes/Crème brûlée.md", "a, b & c [draft] #1.txt", "日本語/ノート.txt", " spaced ", "a.b.c", "emoji 😀.txt"}) {
            assertEquals(ok, ok, PhoneFilesContract.normalizePath(ok));
        }
        for (String bad : new String[]{"/a", "a/", "a//b", "..", "a/../b", "./a", "a/.", "a\u0000b", "line\nbreak", "tab\tname", "a b",
            "lone\uD800", "x".repeat(256), "a/b/c/d/e/f/g/h/i", ("x".repeat(200) + "/").repeat(6) + "x"}) {
            assertThrows(bad, PhoneFilesContract.Failure.class, () -> PhoneFilesContract.normalizePath(bad));
        }
        assertEquals("a/b", PhoneFilesContract.child("a", "b"));
        assertEquals("b", PhoneFilesContract.child("", "b"));
        assertNull(PhoneFilesContract.child("a", "x/y"));
        assertNull(PhoneFilesContract.child("a", ".."));
        assertNull(PhoneFilesContract.child("a/b/c/d/e/f/g/h", "i"));
        assertEquals("a/b", PhoneFilesContract.parent("a/b/c"));
        assertEquals("c", PhoneFilesContract.name("a/b/c"));
        assertEquals("invalid_path", code("list", "handle", HANDLE, "path", "../up"));
        assertEquals("invalid_path", code("mkdir", "handle", HANDLE, "path", ""));
        assertEquals("invalid_arguments", code("mkdir", "handle", HANDLE));
        assertEquals("invalid_path", code("rename", "handle", HANDLE, "path", ".", "name", "b"));
        assertEquals("invalid_path", code("rename", "handle", HANDLE, "path", "a", "name", "x/y"));
        assertEquals("b c", parse("rename", "handle", HANDLE, "path", "a", "name", "b c").name);
        assertEquals("", parse("stat", "handle", HANDLE).path);
        assertEquals("", parse("list", "handle", HANDLE, "path", ".").path);
    }

    @Test public void readWriteAndDeleteArgumentsAreBounded() {
        PhoneFilesContract.Request read = parse("read", "handle", HANDLE, "encoding", "base64");
        assertEquals(0, read.offset); assertEquals(FilesContract.MAX_CHUNK_BYTES, read.length);
        assertEquals(PhoneFilesContract.MAX_OFFSET, parse("read", "handle", HANDLE, "encoding", "utf8", "offset", PhoneFilesContract.MAX_OFFSET).offset);
        assertEquals("invalid_arguments", code("read", "handle", HANDLE));
        assertEquals("invalid_arguments", code("read", "handle", HANDLE, "encoding", "latin1"));
        assertEquals("invalid_arguments", code("read", "handle", HANDLE, "encoding", "utf8", "length", 12_001));
        assertEquals("invalid_arguments", code("read", "handle", HANDLE, "encoding", "utf8", "offset", -1));
        assertEquals("invalid_arguments", code("read", "handle", HANDLE, "encoding", "utf8", "offset", PhoneFilesContract.MAX_OFFSET + 1));
        PhoneFilesContract.Request write = parse("write", "handle", HANDLE, "path", "notes/a.txt", "data", "héllo", "encoding", "utf8", "append", true);
        assertArrayEquals("héllo".getBytes(StandardCharsets.UTF_8), write.data); assertTrue(write.append); assertEquals("notes/a.txt", write.path);
        assertArrayEquals(new byte[]{1, 2, 3}, parse("write", "handle", HANDLE, "data", "AQID", "encoding", "base64").data);
        assertEquals("invalid_arguments", code("write", "handle", HANDLE, "data", "not base64!", "encoding", "base64"));
        assertEquals("invalid_arguments", code("write", "handle", HANDLE, "data", "\uD800", "encoding", "utf8"));
        assertEquals("invalid_arguments", code("write", "handle", HANDLE, "encoding", "utf8"));
        assertEquals("invalid_arguments", code("write", "handle", HANDLE, "data", 5, "encoding", "utf8"));
        assertTrue(parse("delete", "handle", HANDLE, "path", "old", "recursive", true).recursive);
        assertEquals("invalid_arguments", code("delete", "handle", HANDLE, "recursive", "yes"));
        assertEquals(50, parse("list", "handle", HANDLE, "offset", 50).offset);
        assertEquals("invalid_arguments", code("list", "handle", HANDLE, "offset", PhoneFilesContract.MAX_FOLDER_CHILDREN + 1));
        assertFalse(parse("read", "handle", HANDLE, "encoding", "utf8").opensPicker());
    }

    @Test public void importAndExportUsePrivatePathsAndExportPicksANewDocumentOnlyByName() {
        PhoneFilesContract.Request imported = parse("import", "handle", HANDLE, "path", "Inbox/Report 1.pdf", "to", "imports/report.pdf", "overwrite", true);
        assertEquals("imports/report.pdf", imported.to); assertEquals("Inbox/Report 1.pdf", imported.path); assertTrue(imported.overwrite);
        assertEquals("invalid_arguments", code("import", "handle", HANDLE));
        assertEquals("invalid_path", code("import", "handle", HANDLE, "to", "Résumé.pdf"));
        assertEquals("invalid_path", code("import", "handle", HANDLE, "to", ""));
        assertEquals("read_only", code("import", "handle", HANDLE, "to", "photos/a.jpg"));

        PhoneFilesContract.Request toHandle = parse("export", "from", "photos/12345678-1234-1234-1234-123456789abc.jpg", "handle", HANDLE, "path", "Backups/p.jpg", "overwrite", true);
        assertEquals(HANDLE, toHandle.handle); assertNull(toHandle.createName); assertFalse(toHandle.opensPicker());
        PhoneFilesContract.Request saveAs = parse("export", "from", "notes/a.txt", "createName", "My notes.txt", "mimeType", "text/plain");
        assertEquals("My notes.txt", saveAs.createName); assertEquals("text/plain", saveAs.mimeType); assertTrue(saveAs.opensPicker());
        assertEquals("invalid_arguments", code("export", "from", "a.txt"));
        assertEquals("invalid_arguments", code("export", "from", "a.txt", "handle", HANDLE, "createName", "b.txt"));
        assertEquals("invalid_arguments", code("export", "from", "a.txt", "createName", "b.txt", "path", "x"));
        assertEquals("invalid_arguments", code("export", "from", "a.txt", "handle", HANDLE, "mimeType", "text/plain"));
        assertEquals("invalid_path", code("export", "from", "../a.txt", "handle", HANDLE));
        assertEquals("invalid_path", code("export", "from", "a.txt", "createName", "a/b.txt"));

        assertTrue(PhoneFilesContract.opensPicker(request("pick", new JsonObject())));
        assertTrue(PhoneFilesContract.opensPicker(request("export", args("createName", "x"))));
        assertFalse(PhoneFilesContract.opensPicker(request("export", args("handle", HANDLE))));
        assertFalse(PhoneFilesContract.opensPicker(request("read", new JsonObject())));
    }

    @Test public void itemsAndEntriesCarryNamesAndTypesOnly() {
        JsonObject item = PhoneFilesContract.item(HANDLE, "Report.PDF", "Application/PDF", 1234, false, true);
        assertEquals(Set.of("handle", "name", "kind", "mimeType", "size", "canRead", "canWrite"), item.keySet());
        assertEquals("application/pdf", item.get("mimeType").getAsString());
        assertEquals("file", item.get("kind").getAsString());
        JsonObject folder = PhoneFilesContract.item(HANDLE, "Documents", null, 4096, true, false);
        assertFalse(folder.has("size")); assertEquals("folder", folder.get("kind").getAsString());
        assertEquals(PhoneFilesContract.FOLDER_MIME, folder.get("mimeType").getAsString());
        assertFalse(PhoneFilesContract.item(HANDLE, "x", "text/plain", -1, false, false).has("size"));
        assertEquals(PhoneFilesContract.DEFAULT_MIME, PhoneFilesContract.mimeType("text/plain; charset=x", false));
        assertEquals(PhoneFilesContract.DEFAULT_MIME, PhoneFilesContract.mimeType(null, false));

        assertEquals("Untitled", PhoneFilesContract.displayName(""));
        assertEquals("a�b�c�", PhoneFilesContract.displayName("a\nb c\uD800"));
        assertEquals(255, PhoneFilesContract.displayName("x".repeat(400)).length());
        assertEquals(254, PhoneFilesContract.displayName("x".repeat(254) + "😀").length()); // never splits a pair

        JsonObject entry = PhoneFilesContract.entry("a.txt", "notes/a.txt", false, "text/plain", 5, 1700000000000L, true);
        assertEquals(Set.of("name", "path", "kind", "mimeType", "size", "modified", "canWrite"), entry.keySet());
        JsonObject unaddressable = PhoneFilesContract.entry("a/b", null, false, "text/plain", -1, 0, false);
        assertEquals(Set.of("name", "kind", "mimeType", "canWrite"), unaddressable.keySet());
    }

    @Test public void entryListsPageAndStayWithinTheEventLimit() {
        List<JsonObject> entries = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            String name = String.format(Locale.ROOT, "%04d %s", i, "\"quoted\\".repeat(30));
            entries.add(PhoneFilesContract.entry(name, PhoneFilesContract.child("folder", name), false, "text/plain", i, 1700000000000L, true));
        }
        long offset = 0;
        int seen = 0, pages = 0;
        while (true) {
            JsonObject data = PhoneFilesContract.entries(entries, offset, false);
            assertTrue(PhoneFilesContract.fits(data));
            assertEquals(1200, data.get("total").getAsInt());
            int count = data.getAsJsonArray("entries").size();
            assertTrue(count > 0 && count <= FilesContract.MAX_LIST_ENTRIES);
            seen += count; pages++;
            if (!data.get("truncated").getAsBoolean()) { assertFalse(data.has("nextOffset")); break; }
            offset = data.get("nextOffset").getAsLong();
            assertEquals(seen, offset);
        }
        assertEquals(1200, seen);
        assertTrue(pages > 3);
        assertTrue(PhoneFilesContract.entries(entries, 5000, true).get("incomplete").getAsBoolean());
        assertEquals(0, PhoneFilesContract.entries(entries, 5000, false).getAsJsonArray("entries").size());

        List<JsonObject> items = new ArrayList<>();
        for (int i = 0; i < PhoneFilesContract.MAX_GRANTS; i++) {
            items.add(PhoneFilesContract.item(HANDLE, "\"\\".repeat(200), "application/" + "x".repeat(115), Long.MAX_VALUE, false, true));
        }
        assertTrue(PhoneFilesContract.fits(PhoneFilesContract.picked(items, true)));
        assertTrue(PhoneFilesContract.fits(PhoneFilesContract.grants(items)));
        assertEquals(PhoneFilesContract.MAX_GRANTS, PhoneFilesContract.grants(items).get("max").getAsInt());
    }

    @Test public void readDataReturnsWholeCharactersAndEndOfFile() {
        PhoneFilesContract.Request utf8 = parse("read", "handle", HANDLE, "encoding", "utf8", "offset", 10);
        byte[] text = "abcé".getBytes(StandardCharsets.UTF_8); // 5 bytes
        JsonObject whole = PhoneFilesContract.read(utf8, text, 5, 15, true);
        assertEquals("abcé", whole.get("data").getAsString());
        assertEquals(5, whole.get("bytesRead").getAsInt()); assertTrue(whole.get("eof").getAsBoolean()); assertEquals(15, whole.get("size").getAsLong());
        JsonObject cut = PhoneFilesContract.read(utf8, text, 4, -1, false);
        assertEquals("abc", cut.get("data").getAsString()); assertEquals(3, cut.get("bytesRead").getAsInt());
        assertFalse(cut.get("eof").getAsBoolean()); assertFalse(cut.has("size"));
        assertThrows(PhoneFilesContract.Failure.class, () -> PhoneFilesContract.read(utf8, new byte[]{(byte) 0xFF}, 1, -1, false));
        PhoneFilesContract.Request base64 = parse("read", "handle", HANDLE, "encoding", "base64");
        JsonObject binary = PhoneFilesContract.read(base64, new byte[]{(byte) 0xFF, 0}, 2, -1, true);
        assertEquals("/wA=", binary.get("data").getAsString()); assertTrue(binary.get("eof").getAsBoolean());
        byte[] max = new byte[FilesContract.MAX_CHUNK_BYTES];
        Arrays.fill(max, (byte) '"');
        assertTrue(PhoneFilesContract.fits(PhoneFilesContract.read(utf8, max, max.length, -1, false)));
        assertTrue(PhoneFilesContract.fits(PhoneFilesContract.read(base64, max, max.length, -1, false)));
    }

    @Test public void errorsUseStableCodesAndShortMessages() {
        JsonObject error = PhoneFilesContract.error("revoked", "x".repeat(500));
        assertEquals("revoked", error.get("code").getAsString());
        assertEquals(200, error.get("message").getAsString().length());
        assertThrows(IllegalArgumentException.class, () -> PhoneFilesContract.error("content://leak", "m"));
        assertThrows(IllegalArgumentException.class, () -> new PhoneFilesContract.Failure("teapot", "m"));
        assertTrue(PhoneFilesContract.CODES.containsAll(FilesContract.CODES));
        assertEquals("not_found", PhoneFilesContract.Failure.of(new FilesContract.Failure("not_found", "gone")).code);
    }
}

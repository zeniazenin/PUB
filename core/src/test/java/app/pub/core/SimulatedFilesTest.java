package app.pub.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

/** The in-memory folder behavior checks and the visual review use: same replies and rules as the real one. */
public class SimulatedFilesTest {
    private final long[] now = {1_000};
    private final SimulatedFiles files = new SimulatedFiles(() -> now[0]);
    private int serial;

    private static JsonObject args(Object... pairs) {
        JsonObject args = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value instanceof JsonElement) args.add((String) pairs[i], (JsonElement) value);
            else if (value instanceof String) args.addProperty((String) pairs[i], (String) value);
            else if (value instanceof Boolean) args.addProperty((String) pairs[i], (Boolean) value);
            else args.addProperty((String) pairs[i], (Number) value);
        }
        return args;
    }
    private JsonObject effect(String operation, Object... pairs) {
        JsonObject effect = new JsonObject();
        effect.addProperty("type", FilesContract.EFFECT);
        effect.addProperty("id", "r" + (++serial));
        effect.addProperty("operation", operation);
        effect.add("args", args(pairs));
        return effect;
    }
    private JsonObject phone(String operation, Object... pairs) {
        JsonObject effect = new JsonObject();
        effect.addProperty("type", "hardware.request");
        effect.addProperty("id", "p" + (++serial));
        effect.addProperty("capability", PhoneFilesContract.CAPABILITY);
        effect.addProperty("operation", operation);
        effect.add("args", args(pairs));
        return effect;
    }
    /** Sends one request as its own step and returns its reply. */
    private JsonObject send(JsonObject effect) {
        files.beginStep();
        JsonArray effects = new JsonArray();
        effects.add(effect);
        files.accept(effects);
        JsonObject reply = files.next();
        assertNotNull(reply);
        assertNull(files.next());
        assertEquals(effect.get("id"), reply.get("id"));
        assertTrue(reply.toString().length() <= FilesContract.MAX_REPLY_CHARS);
        return reply;
    }
    private JsonObject call(String operation, Object... pairs) {
        JsonObject reply = send(effect(operation, pairs));
        assertEquals(FilesContract.EVENT, reply.get("type").getAsString());
        assertEquals(operation, reply.get("operation").getAsString());
        return reply;
    }
    private JsonObject ok(String operation, Object... pairs) {
        JsonObject reply = call(operation, pairs);
        assertTrue(reply.toString(), reply.get("ok").getAsBoolean());
        return reply;
    }
    private String code(String operation, Object... pairs) {
        JsonObject reply = call(operation, pairs);
        assertFalse(reply.toString(), reply.get("ok").getAsBoolean());
        return reply.get("code").getAsString();
    }
    private List<String> paths(String path, boolean recursive) {
        List<String> paths = new ArrayList<>();
        for (JsonElement entry : ok("list", "path", path, "recursive", recursive).getAsJsonArray("entries")) paths.add(entry.getAsJsonObject().get("path").getAsString());
        return paths;
    }
    private String text(String path) { return ok("read", "path", path, "encoding", "utf8").get("data").getAsString(); }

    @Test public void startsEmptyWithAnEmptyPhotosFolder() {
        assertEquals(List.of("photos"), paths("", false));
        assertEquals(List.of(), paths("photos", false));
        assertEquals("directory", ok("stat", "path", "").getAsJsonObject("entry").get("type").getAsString());
        assertEquals("directory", ok("stat", "path", "photos").getAsJsonObject("entry").get("type").getAsString());
        assertEquals("not_found", code("read", "path", "index.json", "encoding", "utf8"));
        assertEquals("not_found", code("list", "path", "entries"));
        assertEquals("not_found", code("stat", "path", "photos/a.jpg"));
        JsonObject usage = ok("usage");
        assertEquals(0, usage.get("bytes").getAsLong());
        assertEquals(0, usage.get("files").getAsInt());
        assertEquals(FilesContract.QUOTA_BYTES, usage.get("quotaBytes").getAsLong());
        assertEquals(0, usage.get("photos").getAsInt());
    }

    @Test public void writesAppendsAndReadsBackFilesLargerThanOneRequest() {
        assertEquals(5, ok("write", "path", "notes/a.txt", "data", "Hello", "encoding", "utf8").get("size").getAsInt());
        assertEquals(11, ok("write", "path", "notes/a.txt", "data", " world", "encoding", "utf8", "append", true).get("size").getAsInt());
        JsonObject read = ok("read", "path", "notes/a.txt", "encoding", "utf8");
        assertEquals("Hello world", read.get("data").getAsString());
        assertTrue(read.get("eof").getAsBoolean());
        assertEquals(List.of("notes", "notes/a.txt", "photos"), paths("", true));

        // Three full requests make one 36,000-byte file, read back in pieces.
        byte[] all = new byte[3 * FilesContract.MAX_CHUNK_BYTES];
        new Random(7).nextBytes(all);
        for (int i = 0; i < 3; i++) {
            byte[] piece = java.util.Arrays.copyOfRange(all, i * FilesContract.MAX_CHUNK_BYTES, (i + 1) * FilesContract.MAX_CHUNK_BYTES);
            ok("write", "path", "big.bin", "data", Base64.getEncoder().encodeToString(piece), "encoding", "base64", "append", i > 0);
        }
        java.io.ByteArrayOutputStream back = new java.io.ByteArrayOutputStream();
        long offset = 0;
        while (true) {
            JsonObject piece = ok("read", "path", "big.bin", "offset", offset, "encoding", "base64");
            back.writeBytes(Base64.getDecoder().decode(piece.get("data").getAsString()));
            offset += piece.get("bytesRead").getAsLong();
            if (piece.get("eof").getAsBoolean()) break;
        }
        assertArrayEquals(all, back.toByteArray());
        assertEquals(36_011, ok("usage").get("bytes").getAsLong());
        assertEquals(3, ok("usage").get("files").getAsInt());

        // A replacing write changes the size; the modified time comes from the clock.
        now[0] = 5_000;
        ok("write", "path", "notes/a.txt", "data", "new", "encoding", "utf8");
        JsonObject entry = ok("stat", "path", "notes/a.txt").getAsJsonObject("entry");
        assertEquals(3, entry.get("size").getAsLong());
        assertEquals(5_000, entry.get("modified").getAsLong());
    }

    @Test public void listsInPathOrderWithOneLevelOrAllAndPages() {
        ok("write", "path", "b.txt", "data", "b", "encoding", "utf8");
        ok("write", "path", "a b.txt", "data", "ab", "encoding", "utf8");
        ok("write", "path", "a/x.txt", "data", "x", "encoding", "utf8");
        ok("mkdir", "path", "a/y");
        assertEquals(List.of("a", "a b.txt", "b.txt", "photos"), paths("", false));
        assertEquals(List.of("a", "a/x.txt", "a/y", "a b.txt", "b.txt", "photos"), paths("", true));
        assertEquals(List.of("a/x.txt", "a/y"), paths("a", false));
        JsonObject page = ok("list", "path", "", "offset", 2);
        assertEquals(4, page.get("total").getAsInt());
        assertEquals(2, page.getAsJsonArray("entries").size());
        assertEquals("not_directory", code("list", "path", "b.txt"));
    }

    @Test public void followsTheRealFolderRules() {
        ok("write", "path", "f.txt", "data", "file", "encoding", "utf8");
        ok("write", "path", "d/inner.txt", "data", "x", "encoding", "utf8");
        assertEquals("read_only", code("write", "path", "photos/x.jpg", "data", "x", "encoding", "utf8"));
        assertFalse(ok("mkdir", "path", "photos").get("created").getAsBoolean());
        assertEquals("read_only", code("delete", "path", "photos"));
        assertEquals("is_directory", code("read", "path", "photos", "encoding", "utf8"));
        assertEquals("not_found", code("write", "path", "missing/a.txt", "data", "x", "encoding", "utf8", "createParents", false));
        assertEquals("not_directory", code("write", "path", "f.txt/a.txt", "data", "x", "encoding", "utf8"));
        assertEquals("is_directory", code("write", "path", "d", "data", "x", "encoding", "utf8"));
        assertEquals("exists", code("mkdir", "path", "f.txt"));
        assertFalse(ok("mkdir", "path", "d").get("created").getAsBoolean());
        assertEquals("not_empty", code("delete", "path", "d"));
        assertEquals(2, ok("delete", "path", "d", "recursive", true).get("deleted").getAsInt());
        assertEquals("not_found", code("delete", "path", "d"));
        assertEquals("invalid_path", code("write", "path", "../x", "data", "x", "encoding", "utf8"));
        assertEquals("invalid_arguments", code("write", "path", "x", "data", "x", "encoding", "utf8", "mode", "fast"));
        assertEquals("invalid_arguments", code("read", "path", "f.txt", "encoding", "latin1"));
        assertEquals(4, ok("usage").get("bytes").getAsLong());
        assertEquals(1, ok("usage").get("files").getAsInt());
    }

    @Test public void movesAndCopiesFilesAndFolders() {
        ok("write", "path", "a/one.txt", "data", "one", "encoding", "utf8");
        ok("write", "path", "a/sub/two.txt", "data", "two", "encoding", "utf8");
        ok("write", "path", "loose.txt", "data", "loose", "encoding", "utf8");

        assertEquals("archive/loose.txt", ok("move", "from", "loose.txt", "to", "archive/loose.txt").getAsJsonObject("entry").get("path").getAsString());
        assertEquals("not_found", code("stat", "path", "loose.txt"));
        ok("copy", "from", "a", "to", "b");
        assertEquals(List.of("a", "a/one.txt", "a/sub", "a/sub/two.txt", "archive", "archive/loose.txt",
            "b", "b/one.txt", "b/sub", "b/sub/two.txt", "photos"), paths("", true));
        assertEquals("two", text("b/sub/two.txt"));
        assertEquals(17, ok("usage").get("bytes").getAsLong());
        assertEquals(10, ok("usage").get("files").getAsInt());

        ok("move", "from", "b", "to", "c/b");
        assertEquals("two", text("c/b/sub/two.txt"));
        assertEquals("not_found", code("stat", "path", "b"));

        assertEquals("exists", code("copy", "from", "a/one.txt", "to", "archive/loose.txt"));
        ok("copy", "from", "a/one.txt", "to", "archive/loose.txt", "overwrite", true);
        assertEquals("one", text("archive/loose.txt"));
        assertEquals(15, ok("usage").get("bytes").getAsLong());
        assertEquals("not_directory", code("copy", "from", "a", "to", "archive/loose.txt", "overwrite", true));
        assertEquals("is_directory", code("move", "from", "a/one.txt", "to", "c", "overwrite", true));
        assertEquals("read_only", code("move", "from", "a/one.txt", "to", "photos/one.txt"));
        assertEquals("invalid_arguments", code("move", "from", "a", "to", "a/inside"));
        ok("copy", "from", "photos", "to", "photo copies");
        assertEquals("directory", ok("stat", "path", "photo copies").getAsJsonObject("entry").get("type").getAsString());

        // A moved folder's contents must still fit the depth limit.
        ok("write", "path", "d1/d2/d3/d4/d5/d6/d7/deep.txt", "data", "x", "encoding", "utf8");
        assertEquals("invalid_path", code("move", "from", "d1", "to", "x/d1"));
    }

    @Test public void holdsAtMostItsCapAndChangesNothingBeyondIt() {
        String piece = "x".repeat(8_000);
        int written = 0;
        String failure = null;
        while (failure == null) {
            JsonObject reply = call("write", "path", "log.txt", "data", piece, "encoding", "utf8", "append", true);
            if (reply.get("ok").getAsBoolean()) written += piece.length();
            else failure = reply.get("code").getAsString();
        }
        assertEquals("quota_exceeded", failure);
        assertTrue(written <= SimulatedFiles.MAX_BYTES && written > SimulatedFiles.MAX_BYTES - piece.length());
        assertEquals(written, ok("stat", "path", "log.txt").getAsJsonObject("entry").get("size").getAsLong());
        ok("delete", "path", "log.txt");
        assertEquals(0, ok("usage").get("bytes").getAsLong());
    }

    @Test public void answersInRequestOrderWithTheHostsPendingLimitAndAStepBudget() {
        JsonArray effects = new JsonArray();
        for (int i = 0; i < FilesContract.MAX_PENDING + 2; i++) effects.add(effect("write", "path", "f" + i + ".txt", "data", "x", "encoding", "utf8"));
        effects.add(phone("pick", "mode", "open"));
        JsonObject camera = new JsonObject();
        camera.addProperty("type", "hardware.request"); camera.addProperty("id", "cam"); camera.addProperty("capability", "camera");
        camera.addProperty("operation", "capture"); camera.add("args", new JsonObject());
        effects.add(camera);
        files.accept(effects);
        for (int i = 0; i < FilesContract.MAX_PENDING; i++) assertTrue(files.next().get("ok").getAsBoolean());
        assertEquals("busy", files.next().get("code").getAsString());
        assertEquals("busy", files.next().get("code").getAsString());
        JsonObject pick = files.next();
        assertEquals("error", pick.get("event").getAsString());
        assertEquals("busy", pick.getAsJsonObject("data").get("code").getAsString());
        assertNull("Other hardware is never answered", files.next());

        // One step hands out at most MAX_REPLIES_PER_STEP replies; the next step drops what a looping app left.
        files.beginStep();
        for (int i = 0; i < SimulatedFiles.MAX_REPLIES_PER_STEP; i++) {
            JsonArray one = new JsonArray();
            one.add(effect("stat", "path", ""));
            files.accept(one);
            assertNotNull(files.next());
        }
        JsonArray more = new JsonArray();
        more.add(effect("stat", "path", ""));
        files.accept(more);
        assertNull(files.next());
        files.beginStep();
        assertNull(files.next());
    }

    @Test public void phoneFilesHoldNothingAndPickersAreCancelled() {
        JsonObject picked = send(phone("pick", "mode", "openMultiple", "mimeTypes", array("text/*")));
        assertEquals("hardware", picked.get("type").getAsString());
        assertEquals(PhoneFilesContract.CAPABILITY, picked.get("capability").getAsString());
        assertEquals(PhoneFilesContract.CANCELLED, picked.get("event").getAsString());
        assertEquals(new JsonObject(), picked.get("data"));
        assertEquals(PhoneFilesContract.CANCELLED, send(phone("export", "from", "a.txt", "createName", "a.txt")).get("event").getAsString());
        JsonObject grants = send(phone("grants"));
        assertEquals(PhoneFilesContract.GRANTS, grants.get("event").getAsString());
        assertEquals(0, grants.getAsJsonObject("data").getAsJsonArray("items").size());
        assertEquals(PhoneFilesContract.MAX_GRANTS, grants.getAsJsonObject("data").get("max").getAsInt());
        JsonObject read = send(phone("read", "handle", "0123456789abcdef01234567", "encoding", "utf8"));
        assertEquals(PhoneFilesContract.ERROR, read.get("event").getAsString());
        assertEquals("not_found", read.getAsJsonObject("data").get("code").getAsString());
        JsonObject invalid = send(phone("pick", "mode", "everything"));
        assertEquals("invalid_arguments", invalid.getAsJsonObject("data").get("code").getAsString());
    }

    private static JsonArray array(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) array.add(new JsonPrimitive(value));
        return array;
    }
}

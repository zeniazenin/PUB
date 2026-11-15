package app.pub.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;
import java.security.SecureRandom;
import java.util.*;
import static app.pub.core.PhoneFileGrants.READ;
import static app.pub.core.PhoneFileGrants.WRITE;
import static org.junit.Assert.*;

/** The stored grants file of an app's phone files (strict, bounded) and the host-wide URI holder count. */
public class PhoneFileGrantsTest {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static PhoneFileGrants.Grant grant(String uri, boolean folder, boolean write) {
        return PhoneFileGrants.grant(PhoneFilesContract.newHandle(RANDOM), uri, folder, write, "Report.pdf", "application/pdf", 1_731_600_000_000L);
    }
    private static String uri(int i) { return "content://com.android.externalstorage.documents/document/primary%3ADocuments%2Ff" + i + ".txt"; }
    private static void rejects(String json) { assertThrows(json, IllegalArgumentException.class, () -> PhoneFileGrants.parse(json)); }

    @Test public void grantsRoundTripInAFixedForm() {
        List<PhoneFileGrants.Grant> grants = List.of(grant(uri(1), false, false), grant("content://com.android.externalstorage.documents/tree/primary%3ADocuments", true, true));
        String json = PhoneFileGrants.toJson(grants);
        assertEquals(grants, PhoneFileGrants.parse(json));
        assertEquals(json, PhoneFileGrants.toJson(PhoneFileGrants.parse(json)));
        JsonObject first = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("grants").get(0).getAsJsonObject();
        assertEquals(List.of("handle", "uri", "kind", "write", "name", "mimeType", "added"), new ArrayList<>(first.keySet()));
        PhoneFileGrants.Grant folder = PhoneFileGrants.parse(json).get(1);
        assertTrue(folder.folder); assertEquals(PhoneFilesContract.FOLDER_MIME, folder.mimeType); assertEquals(READ | WRITE, folder.flags());
        assertEquals(READ, grants.get(0).flags());
        assertEquals(READ | WRITE, grants.get(0).withWrite(true).flags());
        assertTrue(PhoneFileGrants.parse("{\"version\":1,\"grants\":[]}").isEmpty());
    }

    @Test public void grantValuesAreValidatedAndNormalized() {
        PhoneFileGrants.Grant odd = PhoneFileGrants.grant(PhoneFilesContract.newHandle(RANDOM), uri(2), false, false, "a\nb", "TEXT/Plain", 1);
        assertEquals("a�b", odd.name); assertEquals("text/plain", odd.mimeType);
        assertEquals(PhoneFilesContract.DEFAULT_MIME, PhoneFileGrants.grant(PhoneFilesContract.newHandle(RANDOM), uri(3), false, false, "x", "nonsense", 1).mimeType);
        for (String bad : new String[]{null, "", "content://", "file:///sdcard/a.txt", "https://example.com/a", "content://a b", "content://é", "content://" + "a".repeat(2040)}) {
            assertFalse(String.valueOf(bad), PhoneFileGrants.validUri(bad));
            assertThrows(IllegalArgumentException.class, () -> PhoneFileGrants.grant(PhoneFilesContract.newHandle(RANDOM), bad, false, false, "x", "text/plain", 1));
        }
        assertThrows(IllegalArgumentException.class, () -> PhoneFileGrants.grant("not-a-handle", uri(4), false, false, "x", "text/plain", 1));
        assertThrows(IllegalArgumentException.class, () -> PhoneFileGrants.grant(PhoneFilesContract.newHandle(RANDOM), uri(4), false, false, "x", "text/plain", 0));
    }

    @Test public void storedFilesAreStrict() {
        String valid = PhoneFileGrants.toJson(List.of(grant(uri(1), false, true)));
        JsonObject root = JsonParser.parseString(valid).getAsJsonObject();
        JsonObject entry = root.getAsJsonArray("grants").get(0).getAsJsonObject();
        rejects(valid.replace("\"version\":1", "\"version\":2"));
        rejects(valid.replace("{\"version\":1,", "{\"version\":1,\"version\":1,"));
        rejects(valid.replace("\"kind\":\"file\"", "\"kind\":\"link\""));
        rejects(valid.replace("\"write\":true", "\"write\":\"true\""));
        rejects(valid.replace("\"added\":", "\"added\":-"));
        rejects(valid.replace("\"handle\":\"", "\"extra\":1,\"handle\":\""));
        rejects(valid.replace("\"uri\":\"content://", "\"uri\":\"file://"));
        rejects(valid.replace("\"name\":\"Report.pdf\"", "\"name\":\"Report\\npdf\""));
        rejects(valid.replace("\"mimeType\":\"application/pdf\"", "\"mimeType\":\"Application/PDF\""));
        rejects(valid + "{}");
        rejects("{\"version\":1}");
        rejects("{\"grants\":[]}");
        rejects("[]");
        rejects("");
        rejects(null);
        JsonObject duplicateField = entry.deepCopy();
        String withDuplicate = valid.replace("\"write\":true", "\"write\":true,\"write\":false");
        rejects(withDuplicate);
        JsonArray twice = new JsonArray(); twice.add(entry); twice.add(duplicateField);
        root.add("grants", twice);
        rejects(root.toString()); // same handle and URI twice
        try { PhoneFileGrants.parse(valid.replace("content://", "content:// ")); fail(); }
        catch (IllegalArgumentException error) { assertFalse("Errors never echo stored URIs", error.getMessage().contains("content")); }
    }

    @Test public void atMostThirtyTwoGrantsFitTheFileBound() {
        List<PhoneFileGrants.Grant> grants = new ArrayList<>();
        for (int i = 0; i < PhoneFilesContract.MAX_GRANTS; i++) {
            String uri = "content://p/" + i + "/" + "a".repeat(PhoneFileGrants.MAX_URI_CHARS - 20);
            grants.add(PhoneFileGrants.grant(PhoneFilesContract.newHandle(RANDOM), uri, false, true, "\"".repeat(400), "application/" + "x".repeat(115), Long.MAX_VALUE));
        }
        String json = PhoneFileGrants.toJson(grants);
        assertTrue(json.length() <= PhoneFileGrants.MAX_JSON_CHARS);
        assertEquals(grants, PhoneFileGrants.parse(json));
        List<PhoneFileGrants.Grant> tooMany = new ArrayList<>(grants);
        tooMany.add(grant(uri(99), false, false));
        assertThrows(IllegalArgumentException.class, () -> PhoneFileGrants.toJson(tooMany));
        JsonObject root = JsonParser.parseString(PhoneFileGrants.toJson(grants.subList(0, 1))).getAsJsonObject();
        JsonArray array = new JsonArray();
        for (int i = 0; i <= PhoneFilesContract.MAX_GRANTS; i++) {
            JsonObject g = root.getAsJsonArray("grants").get(0).getAsJsonObject().deepCopy();
            g.addProperty("handle", PhoneFilesContract.newHandle(RANDOM)); g.addProperty("uri", uri(i));
            array.add(g);
        }
        root.add("grants", array);
        rejects(root.toString());
    }

    @Test public void permissionsAreReleasedOnlyWhenNoSavedAppHoldsTheUri() {
        PhoneFileGrants.Holds holds = new PhoneFileGrants.Holds();
        String uri = uri(1);
        assertEquals(0, holds.hold("app-a", uri, READ | WRITE));
        assertEquals(0, holds.hold("app-b", uri, READ));
        assertEquals(1, holds.size()); assertEquals(Set.of("app-a", "app-b"), holds.owners(uri)); assertEquals(READ | WRITE, holds.flags(uri));
        // app-a leaves: app-b still reads, so only write access is no longer needed.
        assertEquals(WRITE, holds.release("app-a", uri));
        assertEquals(READ, holds.flags(uri));
        assertEquals(0, holds.release("app-a", uri)); // not a holder any more
        assertEquals(READ, holds.release("app-b", uri));
        assertFalse(holds.contains(uri)); assertEquals(0, holds.size());
        assertEquals(0, holds.release("app-b", uri));

        assertEquals(0, holds.hold("app-a", uri, READ | WRITE));
        assertEquals("Holding the same URI again with fewer bits frees the rest", WRITE, holds.hold("app-a", uri, READ));
        assertEquals(0, holds.hold("app-b", uri(2), READ | WRITE));
        assertEquals(2, holds.size());
        assertEquals(Set.of(uri, uri(2)), holds.uris());
        assertEquals(WRITE, holds.excess(uri, READ | WRITE));
        assertEquals(READ | WRITE, holds.excess(uri(3), READ | WRITE));
        assertEquals(0, holds.excess(uri(2), READ | WRITE));
        assertThrows(IllegalArgumentException.class, () -> holds.hold("app-c", uri, WRITE));
        assertThrows(IllegalArgumentException.class, () -> holds.hold("app-c", uri, READ | 64));
    }
}

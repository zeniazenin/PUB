package app.pub.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;

/**
 * What PUB keeps about the phone files a saved app was given (capability files): one strict file per saved app,
 * filesDir/app-grants/&lt;app id&gt;.json, mapping each opaque handle to the document's content URI, its kind, whether
 * write access was granted and the name and type shown to the app. Only the host reads it; scripts get the handles,
 * names and types, never the URIs. {@link Holds} counts which apps hold each URI, so Android's persisted permission
 * for a document is released only when no saved app needs it any more.
 *
 * <pre>{"version":1,"grants":[{"handle":"…","uri":"content://…","kind":"file"|"folder","write":false,
 *   "name":"Report.pdf","mimeType":"application/pdf","added":1731600000000}]}</pre>
 */
public final class PhoneFileGrants {
    public static final int VERSION = 1;
    /** Permission bits, equal to Android's Intent.FLAG_GRANT_READ_URI_PERMISSION and FLAG_GRANT_WRITE_URI_PERMISSION. */
    public static final int READ = 1, WRITE = 2;
    public static final int MAX_URI_CHARS = 2048;
    /** Every grant at its maximum, with escaping, stays below this. */
    public static final int MAX_JSON_CHARS = PhoneFilesContract.MAX_GRANTS * (MAX_URI_CHARS + 6 * PhoneFilesContract.MAX_NAME_CHARS + 400) + 100;
    private static final Set<String> FIELDS = Set.of("version", "grants");
    private static final Set<String> GRANT_FIELDS = Set.of("handle", "uri", "kind", "write", "name", "mimeType", "added");
    private PhoneFileGrants() {}

    /** One file or folder an app holds. */
    public static final class Grant {
        public final String handle, uri, name, mimeType;
        public final boolean folder, write;
        public final long added;
        private Grant(String handle, String uri, boolean folder, boolean write, String name, String mimeType, long added) {
            this.handle = handle; this.uri = uri; this.folder = folder; this.write = write; this.name = name; this.mimeType = mimeType; this.added = added;
        }
        /** The Android permission bits this grant needs: read, plus write when it was granted. */
        public int flags() { return READ | (write ? WRITE : 0); }
        public Grant withWrite(boolean write) { return new Grant(handle, uri, folder, write, name, mimeType, added); }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Grant)) return false;
            Grant g = (Grant) other;
            return handle.equals(g.handle) && uri.equals(g.uri) && folder == g.folder && write == g.write && name.equals(g.name)
                && mimeType.equals(g.mimeType) && added == g.added;
        }
        @Override public int hashCode() { return Objects.hash(handle, uri, folder, write, name, mimeType, added); }
    }

    /**
     * A validated grant. [name] and [mimeType] are normalized for display ({@link PhoneFilesContract#displayName},
     * {@link PhoneFilesContract#mimeType}); the handle and URI must already be valid.
     */
    public static Grant grant(String handle, String uri, boolean folder, boolean write, String name, String mimeType, long added) {
        if (!PhoneFilesContract.validHandle(handle) || !validUri(uri) || added <= 0) throw new IllegalArgumentException("Invalid file grant");
        return new Grant(handle, uri, folder, write, PhoneFilesContract.displayName(name), PhoneFilesContract.mimeType(mimeType, folder), added);
    }

    /** A content URI as Android encodes it: printable ASCII without spaces, at most {@link #MAX_URI_CHARS}. */
    public static boolean validUri(String uri) {
        if (uri == null || uri.length() > MAX_URI_CHARS || !uri.startsWith("content://") || uri.length() == "content://".length()) return false;
        for (int i = 0; i < uri.length(); i++) {
            char c = uri.charAt(i);
            if (c <= 0x20 || c >= 0x7F) return false;
        }
        return true;
    }

    /** Stored form in a fixed field order; round-trips through {@link #parse}. */
    public static String toJson(List<Grant> grants) {
        check(grants);
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonArray array = new JsonArray();
        for (Grant g : grants) {
            JsonObject o = new JsonObject();
            o.addProperty("handle", g.handle);
            o.addProperty("uri", g.uri);
            o.addProperty("kind", g.folder ? "folder" : "file");
            o.addProperty("write", g.write);
            o.addProperty("name", g.name);
            o.addProperty("mimeType", g.mimeType);
            o.addProperty("added", g.added);
            array.add(o);
        }
        root.add("grants", array);
        return root.toString();
    }

    /** Parses the stored form written by {@link #toJson}; anything else (unknown, duplicate or invalid fields) is rejected. */
    public static List<Grant> parse(String json) {
        if (json == null || json.length() > MAX_JSON_CHARS) throw invalid();
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw invalid();
            reader.beginObject();
            Set<String> fields = new HashSet<>();
            List<Grant> grants = null;
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (!fields.add(field)) throw invalid();
                if (field.equals("version")) {
                    if (reader.peek() != JsonToken.NUMBER || !String.valueOf(VERSION).equals(reader.nextString())) throw invalid();
                } else if (field.equals("grants")) {
                    if (reader.peek() != JsonToken.BEGIN_ARRAY) throw invalid();
                    grants = new ArrayList<>();
                    reader.beginArray();
                    while (reader.hasNext()) {
                        if (grants.size() >= PhoneFilesContract.MAX_GRANTS) throw invalid();
                        grants.add(grant(reader));
                    }
                    reader.endArray();
                } else throw invalid();
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || !fields.equals(FIELDS)) throw invalid();
            check(grants);
            return List.copyOf(grants);
        } catch (IOException | RuntimeException error) {
            // Never echo stored content (URIs) in errors.
            throw invalid();
        }
    }

    private static Grant grant(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) throw invalid();
        reader.beginObject();
        Set<String> fields = new HashSet<>();
        String handle = null, uri = null, kind = null, name = null, mimeType = null;
        boolean write = false;
        long added = 0;
        while (reader.hasNext()) {
            String field = reader.nextName();
            if (!fields.add(field)) throw invalid();
            switch (field) {
                case "handle": handle = string(reader); break;
                case "uri": uri = string(reader); break;
                case "kind": kind = string(reader); break;
                case "name": name = string(reader); break;
                case "mimeType": mimeType = string(reader); break;
                case "write":
                    if (reader.peek() != JsonToken.BOOLEAN) throw invalid();
                    write = reader.nextBoolean();
                    break;
                case "added":
                    if (reader.peek() != JsonToken.NUMBER) throw invalid();
                    String raw = reader.nextString();
                    if (!raw.matches("[1-9][0-9]{0,18}")) throw invalid();
                    added = Long.parseLong(raw);
                    break;
                default: throw invalid();
            }
        }
        reader.endObject();
        if (!fields.equals(GRANT_FIELDS) || !Set.of("file", "folder").contains(kind)) throw invalid();
        boolean folder = kind.equals("folder");
        // Stored values must already be in their normalized form.
        if (name.isEmpty() || !name.equals(PhoneFilesContract.displayName(name))
            || !mimeType.equals(PhoneFilesContract.mimeType(mimeType, folder))) throw invalid();
        return grant(handle, uri, folder, write, name, mimeType, added);
    }

    private static String string(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw invalid();
        return reader.nextString();
    }

    /** At most {@link PhoneFilesContract#MAX_GRANTS}, with unique handles and unique URIs. */
    private static void check(List<Grant> grants) {
        if (grants == null || grants.size() > PhoneFilesContract.MAX_GRANTS) throw invalid();
        Set<String> handles = new HashSet<>(), uris = new HashSet<>();
        for (Grant g : grants) if (g == null || !handles.add(g.handle) || !uris.add(g.uri)) throw invalid();
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Saved file access is invalid."); }

    /**
     * Which saved apps hold each document URI, and with which permission bits. PUB persists one Android permission per
     * URI for all apps together, so it takes a holder's bits when it adds one and releases bits only when no remaining
     * holder needs them. Not thread-safe; the grant store guards it.
     */
    public static final class Holds {
        private final Map<String, Map<String, Integer>> byUri = new HashMap<>();

        /** Distinct URIs held by any app. */
        public int size() { return byUri.size(); }
        public boolean contains(String uri) { return byUri.containsKey(uri); }
        public Set<String> uris() { return Set.copyOf(byUri.keySet()); }
        public Set<String> owners(String uri) { return Set.copyOf(byUri.getOrDefault(uri, Map.of()).keySet()); }

        /** The union of every holder's bits for [uri] (0 when nobody holds it). */
        public int flags(String uri) {
            int flags = 0;
            for (int f : byUri.getOrDefault(uri, Map.of()).values()) flags |= f;
            return flags;
        }

        /** Records that [owner] holds [uri] with [flags] (replacing its earlier bits) and returns the bits no holder needs any more. */
        public int hold(String owner, String uri, int flags) {
            if ((flags & READ) == 0 || (flags & ~(READ | WRITE)) != 0) throw new IllegalArgumentException("Invalid permission bits");
            int before = flags(uri);
            byUri.computeIfAbsent(uri, key -> new HashMap<>()).put(owner, flags);
            return before & ~flags(uri);
        }

        /** Removes [owner]'s hold of [uri] and returns the bits no remaining holder needs (release them; 0 when all are still needed). */
        public int release(String owner, String uri) {
            Map<String, Integer> owners = byUri.get(uri);
            if (owners == null || !owners.containsKey(owner)) return 0;
            int before = flags(uri);
            owners.remove(owner);
            if (owners.isEmpty()) byUri.remove(uri);
            return before & ~flags(uri);
        }

        /** The bits of a persisted permission [persisted] that no holder of [uri] needs (orphans to release at startup). */
        public int excess(String uri, int persisted) { return persisted & ~flags(uri); }
    }
}

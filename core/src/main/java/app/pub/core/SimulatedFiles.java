package app.pub.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * The files an app reaches during behavior checks and the visual review, where nothing may touch the phone's storage:
 * <ul>
 *   <li>Private files (storage.files): an in-memory folder that starts empty, with the always-present, empty photos
 *   folder. It answers every {@link FilesContract} operation with the same reply shapes, codes, path rules and
 *   quotas as the real folder, so an app can save something and then list and read it within one check. It holds at
 *   most {@link #MAX_BYTES}.</li>
 *   <li>Phone files (hardware capability files): nothing is picked. A picker request (pick, export to a new document)
 *   answers cancelled, as if the user closed the picker; grants answers no items; every other request answers
 *   not_found, as for a document that is no longer available.</li>
 * </ul>
 * Other effects are ignored. Replies wait in request order and are handed out one at a time by {@link #next}: the host
 * sends one, waits for the UI packet that answers it, gives that packet's effects to {@link #accept}, and so on until
 * none waits. At most {@link #MAX_REPLIES_PER_STEP} replies are handed out per step ({@link #beginStep}), so an app that
 * keeps requesting cannot stall a check. Not thread-safe; one instance serves one session.
 */
public final class SimulatedFiles {
    public static final int MAX_REPLIES_PER_STEP = 64;
    /** Data the simulated folder holds at most; beyond it writes answer quota_exceeded. */
    public static final long MAX_BYTES = 4L * 1024 * 1024;
    private static final String PICK_AGAIN = "This file is no longer available. Pick it again.";

    private static final class Node {
        final boolean directory;
        final byte[] data;
        final long modified;
        Node(boolean directory, byte[] data, long modified) { this.directory = directory; this.data = data; this.modified = modified; }
        long size() { return directory ? 0 : data.length; }
    }
    private static final class Destination {
        final List<String> parents;
        final boolean replaced;
        final long replacedBytes;
        Destination(List<String> parents, boolean replaced, long replacedBytes) {
            this.parents = parents; this.replaced = replaced; this.replacedBytes = replacedBytes;
        }
    }

    private final LongSupplier clock;
    /** Every file and folder by path; PATH_ORDER keeps a folder's contents directly after it. */
    private final TreeMap<String, Node> nodes = new TreeMap<>(FilesContract.PATH_ORDER);
    private final ArrayDeque<JsonObject> waiting = new ArrayDeque<>();
    private long bytes;
    private int delivered;

    public SimulatedFiles() { this(System::currentTimeMillis); }
    /** [clock] gives the modification times, in milliseconds since 1970. */
    public SimulatedFiles(LongSupplier clock) { this.clock = clock; }

    /**
     * Performs the file requests among a validated packet's effects (UiPacket already checked their envelopes) and
     * queues their replies in request order. When {@link FilesContract#MAX_PENDING} replies already wait, a request is
     * answered busy, as by the host.
     */
    public void accept(JsonArray effects) {
        for (JsonElement item : effects) {
            JsonObject effect = item.getAsJsonObject();
            String type = effect.get("type").getAsString();
            boolean privateFiles = FilesContract.EFFECT.equals(type);
            boolean phoneFiles = "hardware.request".equals(type) && PhoneFilesContract.CAPABILITY.equals(effect.get("capability").getAsString());
            if (!privateFiles && !phoneFiles) continue;
            if (waiting.size() >= FilesContract.MAX_PENDING) waiting.add(busy(effect, privateFiles));
            else waiting.add(privateFiles ? perform(effect) : phone(effect));
        }
    }

    /** The next reply to send, or null when none waits or this step's replies are used up. */
    public JsonObject next() {
        if (waiting.isEmpty() || delivered >= MAX_REPLIES_PER_STEP) return null;
        delivered++;
        return waiting.poll();
    }

    /** Starts a new step's reply budget; replies a looping app left waiting are dropped. */
    public void beginStep() {
        delivered = 0;
        waiting.clear();
    }

    private JsonObject busy(JsonObject effect, boolean privateFiles) {
        String id = effect.get("id").getAsString(), operation = effect.get("operation").getAsString();
        String message = "Too many file requests are waiting. Send more after their replies arrive.";
        return privateFiles ? FilesContract.failure(id, operation, "busy", message)
            : hardware(effect, PhoneFilesContract.ERROR, PhoneFilesContract.error("busy", message));
    }

    private JsonObject phone(JsonObject effect) {
        try {
            PhoneFilesContract.Request request = PhoneFilesContract.parse(effect);
            if (request.opensPicker()) return hardware(effect, PhoneFilesContract.CANCELLED, new JsonObject());
            if (request.operation.equals("grants")) return hardware(effect, PhoneFilesContract.GRANTS, PhoneFilesContract.grants(List.of()));
            return hardware(effect, PhoneFilesContract.ERROR, PhoneFilesContract.error("not_found", PICK_AGAIN));
        } catch (PhoneFilesContract.Failure failure) {
            return hardware(effect, PhoneFilesContract.ERROR, PhoneFilesContract.error(failure));
        }
    }

    /** The host's hardware event envelope. */
    private static JsonObject hardware(JsonObject effect, String event, JsonObject data) {
        JsonObject reply = new JsonObject();
        reply.addProperty("type", "hardware");
        reply.addProperty("id", effect.get("id").getAsString());
        reply.addProperty("capability", PhoneFilesContract.CAPABILITY);
        reply.addProperty("event", event);
        reply.add("data", data);
        return reply;
    }

    private JsonObject perform(JsonObject effect) {
        FilesContract.Request r;
        try { r = FilesContract.parse(effect); }
        catch (FilesContract.Failure failure) {
            return FilesContract.failure(effect.get("id").getAsString(), effect.get("operation").getAsString(), failure.code, failure.getMessage());
        }
        try {
            switch (r.operation) {
                case "list": return list(r);
                case "stat": {
                    JsonObject entry = stat(r.path);
                    if (entry == null) throw notFound(r.path);
                    JsonObject reply = FilesContract.ok(r);
                    reply.add("entry", entry);
                    return reply;
                }
                case "read": return read(r);
                case "write": return write(r);
                case "mkdir": return mkdir(r);
                case "delete": return delete(r);
                case "move": return move(r);
                case "copy": return copy(r);
                default: return usage(r);
            }
        } catch (FilesContract.Failure failure) {
            return FilesContract.failure(r, failure);
        }
    }

    private JsonObject list(FilesContract.Request r) {
        List<JsonObject> entries = new ArrayList<>();
        if (FilesContract.inPhotos(r.path)) {
            // The photos folder exists and is empty: no camera photos are taken in a check.
            if (!r.path.equals(FilesContract.PHOTOS)) throw notFound(r.path);
        } else {
            if (!r.path.isEmpty()) {
                Node node = nodes.get(r.path);
                if (node == null) throw notFound(r.path);
                if (!node.directory) throw notDirectory(r.path);
            }
            int depth = depth(r.path);
            for (Map.Entry<String, Node> child : descendants(r.path)) {
                if (r.recursive || depth(child.getKey()) == depth + 1) entries.add(entry(child.getKey(), child.getValue()));
            }
            if (r.path.isEmpty()) entries.add(FilesContract.entry(FilesContract.PHOTOS, true, 0, 0));
            entries.sort((a, b) -> FilesContract.PATH_ORDER.compare(a.get("path").getAsString(), b.get("path").getAsString()));
        }
        return FilesContract.listReply(r, entries);
    }

    private JsonObject stat(String path) {
        if (path.equals(FilesContract.PHOTOS)) return FilesContract.entry(FilesContract.PHOTOS, true, 0, 0);
        if (FilesContract.inPhotos(path)) return null;
        if (path.isEmpty()) return FilesContract.entry("", true, 0, 0);
        Node node = nodes.get(path);
        return node == null ? null : entry(path, node);
    }

    private JsonObject read(FilesContract.Request r) {
        if (r.path.equals(FilesContract.PHOTOS)) throw isDirectory(r.path);
        Node node = FilesContract.inPhotos(r.path) ? null : nodes.get(r.path);
        if (node == null) throw notFound(r.path);
        if (node.directory) throw isDirectory(r.path);
        int size = node.data.length;
        int start = (int) Math.min(r.offset, size);
        int count = Math.min(r.length, size - start);
        return FilesContract.readReply(r, Arrays.copyOfRange(node.data, start, start + count), count, size);
    }

    private JsonObject write(FilesContract.Request r) {
        if (FilesContract.inPhotos(r.path)) throw readOnly("The photos folder is read-only. Copy a photo to another folder to keep a changeable copy.");
        List<String> parents = missingParents(FilesContract.parent(r.path), r.createParents);
        Node existing = parents.isEmpty() ? nodes.get(r.path) : null;
        if (existing != null && existing.directory) throw isDirectory(r.path);
        long old = existing == null ? 0 : existing.data.length;
        byte[] data = r.append && existing != null ? concat(existing.data, r.data) : r.data.clone();
        reserve(data.length - old, parents.size() + (existing == null ? 1 : 0));
        create(parents);
        nodes.put(r.path, new Node(false, data, clock.getAsLong()));
        bytes += data.length - old;
        JsonObject reply = FilesContract.ok(r);
        reply.addProperty("size", data.length);
        return reply;
    }

    private JsonObject mkdir(FilesContract.Request r) {
        if (r.path.equals(FilesContract.PHOTOS)) return created(r, false);
        if (FilesContract.inPhotos(r.path)) throw readOnly("The photos folder is read-only.");
        List<String> parents = missingParents(FilesContract.parent(r.path), true);
        Node existing = parents.isEmpty() ? nodes.get(r.path) : null;
        if (existing != null) {
            if (existing.directory) return created(r, false);
            throw new FilesContract.Failure("exists", "A file named " + display(r.path) + " already exists");
        }
        reserve(0, parents.size() + 1);
        List<String> folders = new ArrayList<>(parents);
        folders.add(r.path);
        create(folders);
        return created(r, true);
    }

    private JsonObject delete(FilesContract.Request r) {
        if (r.path.equals(FilesContract.PHOTOS)) throw readOnly("The photos folder cannot be deleted. Delete single photos instead.");
        Node node = FilesContract.inPhotos(r.path) ? null : nodes.get(r.path);
        if (node == null) throw notFound(r.path);
        int removed = 1;
        if (node.directory) {
            List<Map.Entry<String, Node>> contents = descendants(r.path);
            if (!r.recursive && !contents.isEmpty()) throw new FilesContract.Failure("not_empty", display(r.path) + " is not empty. Delete it with recursive: true.");
            for (Map.Entry<String, Node> child : contents) remove(child.getKey());
            removed += contents.size();
        }
        remove(r.path);
        JsonObject reply = FilesContract.ok(r);
        reply.addProperty("deleted", removed);
        return reply;
    }

    private JsonObject move(FilesContract.Request r) {
        if (FilesContract.inPhotos(r.from) || FilesContract.inPhotos(r.to)) throw readOnly("Photos cannot be moved. Copy them instead.");
        Node source = nodes.get(r.from);
        if (source == null) throw notFound(r.from);
        List<Map.Entry<String, Node>> contents = source.directory ? descendants(r.from) : List.of();
        checkFits(contents, r.from, r.to);
        Destination destination = destination(r, source.directory);
        reserve(0, destination.parents.size() - (destination.replaced ? 1 : 0));
        create(destination.parents);
        if (destination.replaced) remove(r.to);
        nodes.remove(r.from);
        nodes.put(r.to, source);
        for (Map.Entry<String, Node> child : contents) {
            nodes.remove(child.getKey());
            nodes.put(r.to + child.getKey().substring(r.from.length()), child.getValue());
        }
        JsonObject reply = FilesContract.ok(r);
        reply.add("entry", entry(r.to, source));
        return reply;
    }

    private JsonObject copy(FilesContract.Request r) {
        if (FilesContract.inPhotos(r.to)) throw readOnly("Nothing can be copied into the photos folder.");
        boolean photos = r.from.equals(FilesContract.PHOTOS);
        Node source = photos || FilesContract.inPhotos(r.from) ? null : nodes.get(r.from);
        if (source == null && !photos) throw notFound(r.from);
        boolean directory = photos || source.directory;
        List<Map.Entry<String, Node>> contents = source != null && source.directory ? descendants(r.from) : List.of();
        checkFits(contents, r.from, r.to);
        long size = directory ? 0 : source.data.length;
        for (Map.Entry<String, Node> child : contents) size += child.getValue().size();
        Destination destination = destination(r, directory);
        reserve(size - destination.replacedBytes, contents.size() + 1 + destination.parents.size() - (destination.replaced ? 1 : 0));
        create(destination.parents);
        if (destination.replaced) remove(r.to);
        long now = clock.getAsLong();
        Node copied = directory ? new Node(true, null, now) : new Node(false, source.data.clone(), source.modified);
        nodes.put(r.to, copied);
        bytes += copied.size();
        for (Map.Entry<String, Node> child : contents) {
            Node original = child.getValue();
            Node duplicate = original.directory ? new Node(true, null, original.modified) : new Node(false, original.data.clone(), original.modified);
            nodes.put(r.to + child.getKey().substring(r.from.length()), duplicate);
            bytes += duplicate.size();
        }
        JsonObject reply = FilesContract.ok(r);
        reply.add("entry", entry(r.to, copied));
        return reply;
    }

    private JsonObject usage(FilesContract.Request r) {
        JsonObject reply = FilesContract.ok(r);
        reply.addProperty("bytes", bytes);
        reply.addProperty("files", nodes.size());
        reply.addProperty("quotaBytes", FilesContract.QUOTA_BYTES);
        reply.addProperty("quotaFiles", FilesContract.QUOTA_ENTRIES);
        reply.addProperty("photos", 0);
        reply.addProperty("photoBytes", 0);
        return reply;
    }

    /** Where a move or copy lands; an existing target is replaced only when both are files and overwrite is set. */
    private Destination destination(FilesContract.Request r, boolean directory) {
        List<String> parents = missingParents(FilesContract.parent(r.to), true);
        Node existing = parents.isEmpty() ? nodes.get(r.to) : null;
        if (existing != null) {
            if (!r.overwrite) throw new FilesContract.Failure("exists", display(r.to) + " already exists. Use overwrite: true to replace a file.");
            if (existing.directory) throw new FilesContract.Failure("is_directory", display(r.to) + " is a folder; folders are never overwritten.");
            if (directory) throw new FilesContract.Failure("not_directory", "A folder cannot replace the file " + display(r.to) + ".");
        }
        return new Destination(parents, existing != null, existing == null ? 0 : existing.data.length);
    }

    /** A moved or copied folder's contents must still satisfy the path limits at their new place. */
    private static void checkFits(List<Map.Entry<String, Node>> contents, String from, String to) {
        for (Map.Entry<String, Node> child : contents) {
            String moved = to + child.getKey().substring(from.length());
            if (depth(moved) > FilesContract.MAX_DEPTH || moved.length() > FilesContract.MAX_PATH_CHARS) {
                throw new FilesContract.Failure("invalid_path", "The folder's contents would be deeper than " + FilesContract.MAX_DEPTH
                    + " levels or longer than " + FilesContract.MAX_PATH_CHARS + " characters at " + display(to) + ".");
            }
        }
    }

    /** The folders still missing on the way to [path], outermost first. A file in the way is not_directory. */
    private List<String> missingParents(String path, boolean create) {
        List<String> missing = new ArrayList<>();
        if (path.isEmpty()) return missing;
        String current = "";
        for (String name : path.split("/")) {
            current = current.isEmpty() ? name : current + "/" + name;
            Node node = missing.isEmpty() ? nodes.get(current) : null;
            if (node == null) {
                if (!create) throw notFound(current);
                missing.add(current);
            } else if (!node.directory) throw notDirectory(current);
        }
        return missing;
    }

    /** Quotas are checked before anything changes; changes that do not grow the folder are always allowed. */
    private void reserve(long addedBytes, int addedEntries) {
        if (addedBytes > 0 && bytes + addedBytes > Math.min(MAX_BYTES, FilesContract.QUOTA_BYTES)) {
            throw new FilesContract.Failure("quota_exceeded", "This app's files would exceed the space available. Delete files first.");
        }
        if (addedEntries > 0 && nodes.size() + addedEntries > FilesContract.QUOTA_ENTRIES) {
            throw new FilesContract.Failure("quota_exceeded", "This app's folder would exceed " + FilesContract.QUOTA_ENTRIES + " files and folders.");
        }
    }

    private void create(List<String> folders) {
        long now = clock.getAsLong();
        for (String folder : folders) nodes.put(folder, new Node(true, null, now));
    }

    private void remove(String path) {
        Node node = nodes.remove(path);
        if (node != null) bytes -= node.size();
    }

    /**
     * Everything below [path] ("" is the whole folder), in path order, as copies: TreeMap reuses its own entries
     * when it removes one, so they must not be kept across changes.
     */
    private List<Map.Entry<String, Node>> descendants(String path) {
        Map<String, Node> below = path.isEmpty() ? nodes : nodes.tailMap(path + "/", true);
        String prefix = path.isEmpty() ? "" : path + "/";
        List<Map.Entry<String, Node>> found = new ArrayList<>();
        for (Map.Entry<String, Node> entry : below.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) break;
            found.add(new AbstractMap.SimpleImmutableEntry<>(entry.getKey(), entry.getValue()));
        }
        return found;
    }

    private static JsonObject entry(String path, Node node) { return FilesContract.entry(path, node.directory, node.size(), node.modified); }
    private static JsonObject created(FilesContract.Request r, boolean created) {
        JsonObject reply = FilesContract.ok(r);
        reply.addProperty("created", created);
        return reply;
    }
    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }
    private static int depth(String path) { return path.isEmpty() ? 0 : (int) path.chars().filter(c -> c == '/').count() + 1; }
    private static String display(String path) { return path.isEmpty() ? "the app's folder" : "'" + path + "'"; }
    private static FilesContract.Failure notFound(String path) { return new FilesContract.Failure("not_found", "Nothing is stored at " + display(path) + "."); }
    private static FilesContract.Failure notDirectory(String path) { return new FilesContract.Failure("not_directory", display(path) + " is a file, not a folder."); }
    private static FilesContract.Failure isDirectory(String path) { return new FilesContract.Failure("is_directory", display(path) + " is a folder."); }
    private static FilesContract.Failure readOnly(String message) { return new FilesContract.Failure("read_only", message); }
}

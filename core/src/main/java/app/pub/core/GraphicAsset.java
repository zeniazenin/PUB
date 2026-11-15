package app.pub.core;

import com.google.gson.*;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.CRC32;

/** Closed native drawing format. No external resources, SVG, code, or dynamic expressions. */
public final class GraphicAsset {
    public static final int MAX_PNG_BYTES = 4 * 1024 * 1024;
    public static final int MAX_IMAGE_DIMENSION = 2048;
    public static final long MAX_IMAGE_PIXELS = 4_000_000L;
    private static final Set<String> COLORS = Set.of("primary", "surface", "onSurface", "outline", "transparent");
    private static final Set<String> STYLE = Set.of("id", "type", "fill", "stroke", "strokeWidth");
    private GraphicAsset() {}

    public static boolean validId(String id) { return id != null && id.matches("[a-zA-Z0-9_-]{1,40}"); }

    public static void validate(JsonObject asset) {
        if (asset == null) throw bad("Missing graphic asset");
        String type = text(asset, "type", 16);
        if (type.equals("vector")) {
            keys(asset, Set.of("type", "width", "height", "shapes"));
            positive(asset, "width", 1024); positive(asset, "height", 1024);
            JsonArray shapes = array(asset, "shapes");
            if (shapes.isEmpty() || shapes.size() > 128) throw bad("Graphic shape limit");
            Set<String> ids = new HashSet<>();
            for (JsonElement element : shapes) {
                if (!element.isJsonObject()) throw bad("Expected a shape");
                JsonObject shape = element.getAsJsonObject();
                String id = text(shape, "id", 40);
                if (!validId(id) || !ids.add(id)) throw bad("Invalid or duplicate shape identifier");
                shape(shape);
            }
        } else if (type.equals("image")) {
            keys(asset, Set.of("type", "prompt", "data"));
            if (text(asset, "prompt", 2000).isBlank()) throw bad("Image prompt is empty");
            if (asset.has("data")) decodePng(text(asset, "data", ((MAX_PNG_BYTES + 2) / 3) * 4));
        } else throw bad("Unknown graphic asset type");
    }

    private static void shape(JsonObject shape) {
        String type = text(shape, "type", 16);
        Set<String> allowed = new HashSet<>(STYLE);
        switch (type) {
            case "rect":
                allowed.addAll(Set.of("x", "y", "width", "height", "rx"));
                coords(shape, "x", "y"); positive(shape, "width", 1024); positive(shape, "height", 1024);
                if (shape.has("rx")) number(shape, "rx", 0, 1024);
                break;
            case "ellipse":
                allowed.addAll(Set.of("cx", "cy", "rx", "ry"));
                coords(shape, "cx", "cy"); positive(shape, "rx", 1024); positive(shape, "ry", 1024);
                break;
            case "line":
                allowed.addAll(Set.of("x1", "y1", "x2", "y2")); coords(shape, "x1", "y1", "x2", "y2");
                break;
            case "path":
                allowed.add("commands");
                JsonArray commands = array(shape, "commands");
                if (commands.isEmpty() || commands.size() > 64) throw bad("Path segment limit");
                for (int i = 0; i < commands.size(); i++) {
                    if (!commands.get(i).isJsonArray()) throw bad("Expected path command array");
                    JsonArray command = commands.get(i).getAsJsonArray();
                    if (command.isEmpty() || !isString(command.get(0))) throw bad("Expected path command");
                    String op = command.get(0).getAsString();
                    int length = switch (op) { case "M", "L" -> 3; case "C" -> 7; case "Q" -> 5; case "Z" -> 1; default -> -1; };
                    if (command.size() != length || (i == 0 && !op.equals("M"))) throw bad("Invalid path command");
                    for (int j = 1; j < command.size(); j++) numeric(command.get(j), -1024, 2048);
                }
                break;
            case "text":
                allowed.addAll(Set.of("x", "y", "text", "size")); coords(shape, "x", "y");
                String text = text(shape, "text", 40);
                if (text.chars().anyMatch(c -> Character.isISOControl(c))) throw bad("Graphic text must be one line");
                positive(shape, "size", 64);
                break;
            default: throw bad("Unknown shape type");
        }
        keys(shape, allowed);
        for (String key : Set.of("fill", "stroke")) if (shape.has(key)) {
            String color = text(shape, key, 16);
            if (!COLORS.contains(color) && !color.matches("#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) throw bad("Invalid graphic color");
        }
        if (shape.has("strokeWidth")) number(shape, "strokeWidth", 0, 20);
    }

    /** Validates bounded PNG structure and returns decoded bytes; actual pixel decode remains native. */
    public static byte[] decodePng(String encoded) {
        if (encoded == null || encoded.length() > ((MAX_PNG_BYTES + 2) / 3) * 4) throw bad("Image size limit");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encoded); } catch (IllegalArgumentException e) { throw bad("Invalid PNG base64"); }
        if (bytes.length < 57 || bytes.length > MAX_PNG_BYTES) throw bad("Invalid PNG size");
        byte[] signature = {(byte)137,80,78,71,13,10,26,10};
        for (int i = 0; i < signature.length; i++) if (bytes[i] != signature[i]) throw bad("Expected PNG signature");
        int offset = 8, chunks = 0;
        boolean header = false, pixels = false, ended = false;
        while (offset < bytes.length) {
            if (bytes.length - offset < 12 || ++chunks > 4096) throw bad("Invalid PNG chunks");
            long lengthLong = uint(bytes, offset);
            if (lengthLong > bytes.length - offset - 12L) throw bad("Truncated PNG chunk");
            int length = (int) lengthLong;
            String kind = new String(bytes, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (!kind.matches("[a-zA-Z]{4}")) throw bad("Invalid PNG chunk type");
            CRC32 crc = new CRC32(); crc.update(bytes, offset + 4, length + 4);
            if (crc.getValue() != uint(bytes, offset + 8 + length)) throw bad("PNG checksum mismatch");
            if (!header && !kind.equals("IHDR")) throw bad("Missing PNG header");
            switch (kind) {
                case "IHDR":
                    if (header || length != 13) throw bad("Invalid PNG header");
                    header = true;
                    long width = uint(bytes, offset + 8), height = uint(bytes, offset + 12);
                    if (width < 1 || height < 1 || width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION || width * height > MAX_IMAGE_PIXELS) throw bad("PNG dimension limit");
                    int depth = bytes[offset + 16] & 255, color = bytes[offset + 17] & 255;
                    boolean validDepth = switch (color) {
                        case 0 -> Set.of(1,2,4,8,16).contains(depth);
                        case 2,4,6 -> depth == 8 || depth == 16;
                        case 3 -> Set.of(1,2,4,8).contains(depth);
                        default -> false;
                    };
                    if (!validDepth || bytes[offset + 18] != 0 || bytes[offset + 19] != 0 || (bytes[offset + 20] & 255) > 1) throw bad("Unsupported PNG header");
                    break;
                case "IDAT": pixels = true; break;
                case "IEND":
                    if (!pixels || length != 0 || offset + 12 != bytes.length) throw bad("Invalid PNG ending");
                    ended = true; break;
                case "PLTE": if (length < 3 || length > 768 || length % 3 != 0 || pixels) throw bad("Invalid PNG palette"); break;
                // No animated PNG or unknown critical extensions in this static format.
                case "acTL", "fcTL", "fdAT": throw bad("Animated PNG is unsupported");
                default: if (Character.isUpperCase(kind.charAt(0))) throw bad("Unsupported PNG chunk");
            }
            offset += length + 12;
        }
        if (!header || !pixels || !ended) throw bad("Incomplete PNG");
        return bytes;
    }

    private static long uint(byte[] bytes, int at) {
        return ((bytes[at] & 255L) << 24) | ((bytes[at+1] & 255L) << 16) | ((bytes[at+2] & 255L) << 8) | (bytes[at+3] & 255L);
    }
    private static void keys(JsonObject object, Set<String> allowed) {
        for (String key : object.keySet()) if (!allowed.contains(key)) throw bad("Unknown graphic field: " + key);
    }
    private static JsonArray array(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonArray()) throw bad("Expected array: " + key);
        return value.getAsJsonArray();
    }
    private static String text(JsonObject object, String key, int max) {
        JsonElement value = object.get(key);
        if (!isString(value) || value.getAsString().length() > max) throw bad("Invalid graphic text: " + key);
        return value.getAsString();
    }
    private static boolean isString(JsonElement value) { return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(); }
    private static void coords(JsonObject object, String... keys) { for (String key : keys) number(object, key, -1024, 2048); }
    private static void positive(JsonObject object, String key, double max) { if (number(object, key, 0, max) <= 0) throw bad("Expected positive dimension: " + key); }
    private static double number(JsonObject object, String key, double min, double max) { return numeric(object.get(key), min, max); }
    private static double numeric(JsonElement value, double min, double max) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw bad("Expected graphic number");
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || number < min || number > max) throw bad("Graphic coordinate limit");
        return number;
    }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}

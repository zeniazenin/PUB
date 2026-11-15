package app.pub.core;

import com.google.gson.*;
import java.util.Set;

/** A small versioned native renderer contract. Unknown nodes and effects fail closed. */
public final class UiPacket {
    public final JsonObject ui;
    public final JsonArray effects;
    private static final Set<String> TYPES = Set.of("column", "text", "meter", "choices", "button", "input", "image", "graphic");
    private static final Set<String> GRAPHIC_FIELDS = Set.of("type", "asset", "description", "highlight");
    private static final Set<String> EFFECTS = Set.of("microphone.start", "microphone.stop", "storage.save", "hardware.request");
    private UiPacket(JsonObject ui, JsonArray effects) { this.ui = ui; this.effects = effects; }
    public static UiPacket parse(String input) {
        if (input.length() > ScriptEngine.MAX_MESSAGE) throw new IllegalArgumentException("Packet too large");
        checkJsonDepth(input);
        JsonObject root = JsonParser.parseString(input).getAsJsonObject();
        JsonObject ui = root.getAsJsonObject("ui");
        validate(ui, 0, new int[]{0,0});
        JsonArray effects = root.has("effects") ? root.getAsJsonArray("effects") : new JsonArray();
        if (effects.size() > 4) throw new IllegalArgumentException("Too many effects");
        for (JsonElement item : effects) {
            JsonObject effect = item.getAsJsonObject();
            if (!EFFECTS.contains(effect.get("type").getAsString())) throw new IllegalArgumentException("Unknown effect");
            if ("hardware.request".equals(effect.get("type").getAsString())) HardwareContract.validate(effect);
        }
        return new UiPacket(ui, effects);
    }
    private static void validate(JsonObject node, int depth, int[] count) {
        if (depth > 8 || ++count[0] > 60) throw new IllegalArgumentException("UI complexity limit");
        String type = text(node, "type", 32);
        if (!TYPES.contains(type)) throw new IllegalArgumentException("Unsupported UI component");
        switch (type) {
            case "graphic":
                if (++count[1] > 4) throw new IllegalArgumentException("Too many images");
                for (String key : node.keySet()) if (!GRAPHIC_FIELDS.contains(key)) throw new IllegalArgumentException("Unknown graphic UI field");
                if (!GraphicAsset.validId(text(node, "asset", 40))) throw new IllegalArgumentException("Invalid graphic asset identifier");
                if (text(node, "description", 200).isBlank()) throw new IllegalArgumentException("Graphic description required");
                if (node.has("highlight")) {
                    if (!node.get("highlight").isJsonArray()) throw new IllegalArgumentException("Expected graphic highlight array");
                    JsonArray highlights = node.getAsJsonArray("highlight");
                    if (highlights.size() > 16) throw new IllegalArgumentException("Too many graphic highlights");
                    Set<String> unique = new java.util.HashSet<>();
                    for (JsonElement id : highlights) {
                        if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString() || !GraphicAsset.validId(id.getAsString()) || !unique.add(id.getAsString())) throw new IllegalArgumentException("Invalid graphic highlight");
                    }
                }
                break;
            case "image":
                if (++count[1]>4) throw new IllegalArgumentException("Too many images");
                if (!HardwareContract.validAsset(text(node,"asset",80))) throw new IllegalArgumentException("Invalid image asset");
                text(node,"description",200);
                break;
            case "column":
                for (JsonElement child : node.getAsJsonArray("children")) validate(child.getAsJsonObject(), depth + 1, count);
                break;
            case "text":
                text(node, "text", 512);
                if (node.has("style")) text(node, "style", 32);
                break;
            case "button": text(node, "label", 80); text(node, "action", 80); break;
            case "input": text(node, "label", 80); text(node, "action", 80); text(node, "value", 2000); break;
            case "meter":
                double value = node.get("value").getAsDouble();
                if (!Double.isFinite(value) || Math.abs(value) > 100) throw new IllegalArgumentException("Invalid meter");
                optionalBoolean(node, "active");
                break;
            case "choices":
                JsonArray options = node.getAsJsonArray("options");
                if (options.size() > 8) throw new IllegalArgumentException("Too many choices");
                for (JsonElement e : options) { text(e.getAsJsonObject(), "label", 40); text(e.getAsJsonObject(), "action", 80); optionalBoolean(e.getAsJsonObject(), "selected"); }
        }
    }
    private static String text(JsonObject object, String key, int limit) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected text for " + key);
        String value = element.getAsString();
        if (value.length() > limit) throw new IllegalArgumentException("Text exceeds limit");
        return value;
    }
    private static void optionalBoolean(JsonObject node, String key) {
        if (node.has(key) && (!node.get(key).isJsonPrimitive() || !node.getAsJsonPrimitive(key).isBoolean())) throw new IllegalArgumentException("Expected boolean");
    }
    public static void checkJsonDepth(String input) {
        int depth = 0; boolean inString = false, escaped = false;
        for (int i=0;i<input.length();i++) {
            char c = input.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
            } else if (c == '"') inString = true;
            else if (c == '{' || c == '[') { if (++depth > 24) throw new IllegalArgumentException("JSON nesting limit"); }
            else if (c == '}' || c == ']') depth--;
        }
    }
}

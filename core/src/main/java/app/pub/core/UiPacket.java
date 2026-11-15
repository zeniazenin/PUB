package app.pub.core;

import com.google.gson.*;
import java.util.Set;

/** A small versioned native renderer contract. Unknown nodes and effects fail closed. */
public final class UiPacket {
    public final JsonObject ui;
    public final JsonArray effects;
    private static final Set<String> TYPES = Set.of("column", "card", "row", "text", "item", "meter", "choices", "button", "input", "image", "graphic");
    private static final Set<String> GRAPHIC_FIELDS = Set.of("type", "asset", "description", "highlight");
    private static final Set<String> EFFECTS = Set.of("microphone.start", "microphone.stop", "storage.save", "hardware.request", FilesContract.EFFECT);
    /** Characters of a text node; style "paragraph" (start-aligned reading text) allows {@link #MAX_PARAGRAPH}. */
    public static final int MAX_TEXT = 512, MAX_PARAGRAPH = 4000;
    /** Characters of an input's value; a multiline input allows {@link #MAX_MULTILINE_VALUE}. */
    public static final int MAX_INPUT_VALUE = 2000, MAX_MULTILINE_VALUE = 8000;
    /** Keyboards an input may ask for. */
    public static final Set<String> KEYBOARDS = Set.of("text", "number", "decimal", "phone", "email");
    /** Button emphasis: one primary action per screen, secondary and text for the rest, danger for destructive actions. */
    public static final Set<String> BUTTON_STYLES = Set.of("primary", "secondary", "text", "danger");
    public static final Set<String> TEXT_STYLES = Set.of("body", "subtitle", "eyebrow", "display", "paragraph");
    public static final Set<String> ALIGNMENTS = Set.of("start", "center");
    /** A row holds a few short texts or small buttons side by side, or a compact series such as a week of days. */
    public static final int MAX_ROW_CHILDREN = 8;
    /** Characters of a list item's title, supporting text and trailing text. */
    public static final int MAX_ITEM_TITLE = 120, MAX_ITEM_SUPPORTING = 240, MAX_ITEM_TRAILING = 40;
    /** Components that react to a tap or typing; a tappable card is one button and holds none of them. */
    private static final Set<String> INTERACTIVE = Set.of("button", "input", "choices", "item", "card");
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
            if (FilesContract.EFFECT.equals(effect.get("type").getAsString())) FilesContract.validate(effect);
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
            case "card": {
                // A card groups related content on its own surface; with an action the whole card is one tappable button.
                boolean tappable = node.has("action");
                if (tappable) text(node, "action", 80);
                if (node.has("label")) text(node, "label", 80);
                JsonArray children = node.getAsJsonArray("children");
                if (children.isEmpty()) throw new IllegalArgumentException("Empty card");
                for (JsonElement child : children) {
                    if (tappable && containsInteractive(child.getAsJsonObject(), 0)) throw new IllegalArgumentException("A tappable card cannot contain controls");
                    validate(child.getAsJsonObject(), depth + 1, count);
                }
                break;
            }
            case "row": {
                JsonArray children = node.getAsJsonArray("children");
                if (children.isEmpty() || children.size() > MAX_ROW_CHILDREN) throw new IllegalArgumentException("A row holds 1 to " + MAX_ROW_CHILDREN + " elements");
                for (JsonElement child : children) {
                    String childType = text(child.getAsJsonObject(), "type", 32);
                    if (!childType.equals("text") && !childType.equals("button")) throw new IllegalArgumentException("A row holds only text and buttons");
                    validate(child.getAsJsonObject(), depth + 1, count);
                }
                break;
            }
            case "text":
                String style = node.has("style") ? text(node, "style", 32) : "body";
                // Unknown styles and alignments render as the default, as they always have, so older apps keep working.
                if (node.has("align")) text(node, "align", 16);
                text(node, "text", "paragraph".equals(style) ? MAX_PARAGRAPH : MAX_TEXT);
                break;
            case "item":
                if (text(node, "title", MAX_ITEM_TITLE).isBlank()) throw new IllegalArgumentException("Item title required");
                if (node.has("supporting")) text(node, "supporting", MAX_ITEM_SUPPORTING);
                if (node.has("trailing")) text(node, "trailing", MAX_ITEM_TRAILING);
                if (node.has("action")) text(node, "action", 80);
                optionalBoolean(node, "selected");
                if (node.has("checked")) {
                    optionalBoolean(node, "checked");
                    if (!node.has("action")) throw new IllegalArgumentException("A checkable item needs an action");
                }
                break;
            case "button":
                text(node, "label", 80); text(node, "action", 80);
                if (node.has("style")) text(node, "style", 16); // An unknown style renders as primary.
                break;
            case "input":
                text(node, "label", 80); text(node, "action", 80);
                text(node, "value", optionalBoolean(node, "multiline") ? MAX_MULTILINE_VALUE : MAX_INPUT_VALUE);
                optionalBoolean(node, "voice");
                if (node.has("keyboard") && !KEYBOARDS.contains(text(node, "keyboard", 16))) throw new IllegalArgumentException("Unsupported keyboard");
                break;
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
    /** Whether [node] or anything inside it reacts to taps or typing. */
    private static boolean containsInteractive(JsonObject node, int depth) {
        if (depth > 8) return false;
        JsonElement type = node.get("type");
        if (type == null || !type.isJsonPrimitive()) return false;
        if (INTERACTIVE.contains(type.getAsString())) return true;
        JsonElement children = node.get("children");
        if (children != null && children.isJsonArray()) {
            for (JsonElement child : children.getAsJsonArray()) if (child.isJsonObject() && containsInteractive(child.getAsJsonObject(), depth + 1)) return true;
        }
        return false;
    }
    private static String text(JsonObject object, String key, int limit) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected text for " + key);
        String value = element.getAsString();
        if (value.length() > limit) throw new IllegalArgumentException("Text exceeds limit");
        return value;
    }
    /** An optional boolean field; absent is false. */
    private static boolean optionalBoolean(JsonObject node, String key) {
        if (!node.has(key)) return false;
        if (!node.get(key).isJsonPrimitive() || !node.getAsJsonPrimitive(key).isBoolean()) throw new IllegalArgumentException("Expected boolean");
        return node.get(key).getAsBoolean();
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

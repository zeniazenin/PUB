package app.pub.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The build's UI plan: how the app's screens use PUB's components within the phone's screen, written by a separate
 * planning step before the code (and updated by each edit). The builder implements it, and repairs and the visual
 * review judge against it. Model output is untrusted data parsed against this fixed, bounded schema; an invalid plan is
 * rejected as a whole and errors never echo it. The stored and model forms are the same JSON object.
 *
 * <pre>{"layout":"…","screens":[{"name":"…","purpose":"…","firstView":"…","elements":["…"],"states":["…"]}],
 *  "interactions":["…"],"content":["…"],"accessibility":["…"]}</pre>
 */
public final class UiPlan {
    public static final int MAX_LAYOUT = 600;
    public static final int MAX_SCREENS = 8;
    public static final int MAX_NAME = 60;
    public static final int MAX_PURPOSE = 300;
    public static final int MAX_FIRST_VIEW = 400;
    public static final int MAX_ELEMENTS = 24;
    public static final int MAX_STATES = 8;
    public static final int MAX_ITEM = 300;
    public static final int MAX_INTERACTIONS = 12;
    public static final int MAX_CONTENT = 8;
    public static final int MAX_ACCESSIBILITY = 6;
    /** Every field at its maximum, with JSON escaping, stays below this. */
    public static final int MAX_JSON_CHARS = 200_000;
    private static final Set<String> FIELDS = Set.of("layout", "screens", "interactions", "content", "accessibility");
    private static final Set<String> SCREEN_FIELDS = Set.of("name", "purpose", "firstView", "elements", "states");

    /** Structure and navigation: which screens exist and how people move between them. */
    public final String layout;
    public final List<Screen> screens;
    public final List<String> interactions;
    /** Wording, formats and units. */
    public final List<String> content;
    public final List<String> accessibility;

    public static final class Screen {
        public final String name;
        public final String purpose;
        /** What is visible without scrolling, from the top, on this phone. */
        public final String firstView;
        /** The screen's components from top to bottom. */
        public final List<String> elements;
        /** Empty, loading, error, validation and success states of the screen. */
        public final List<String> states;
        private Screen(String name, String purpose, String firstView, List<String> elements, List<String> states) {
            this.name = name; this.purpose = purpose; this.firstView = firstView;
            this.elements = List.copyOf(elements); this.states = List.copyOf(states);
        }
    }

    private UiPlan(String layout, List<Screen> screens, List<String> interactions, List<String> content, List<String> accessibility) {
        this.layout = layout; this.screens = List.copyOf(screens); this.interactions = List.copyOf(interactions);
        this.content = List.copyOf(content); this.accessibility = List.copyOf(accessibility);
    }

    /** Parses the planner's answer (optionally inside one ```json fence). */
    public static UiPlan parseModelOutput(String text) {
        if (text == null || text.length() > MAX_JSON_CHARS) throw invalid();
        String trimmed = text.strip();
        if (trimmed.startsWith("```")) {
            int newline = trimmed.indexOf('\n');
            if (newline < 0 || !trimmed.endsWith("```")) throw invalid();
            trimmed = trimmed.substring(newline + 1, trimmed.length() - 3).strip();
        }
        return parse(trimmed);
    }

    /** Parses the stored form written by {@link #toJson()}. */
    public static UiPlan parse(String json) {
        if (json == null || json.length() > MAX_JSON_CHARS) throw invalid();
        JsonObject root;
        try {
            UiPacket.checkJsonDepth(json);
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) throw invalid();
            root = parsed.getAsJsonObject();
        } catch (RuntimeException e) { throw invalid(); }
        if (!root.keySet().equals(FIELDS)) throw invalid();
        String layout = text(root.get("layout"), MAX_LAYOUT);
        JsonArray screenArray = array(root.get("screens"), 1, MAX_SCREENS);
        List<Screen> screens = new ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (JsonElement element : screenArray) {
            if (!element.isJsonObject() || !element.getAsJsonObject().keySet().equals(SCREEN_FIELDS)) throw invalid();
            JsonObject screen = element.getAsJsonObject();
            String name = text(screen.get("name"), MAX_NAME);
            if (!names.add(name)) throw invalid();
            screens.add(new Screen(name, text(screen.get("purpose"), MAX_PURPOSE), text(screen.get("firstView"), MAX_FIRST_VIEW),
                strings(screen.get("elements"), 1, MAX_ELEMENTS), strings(screen.get("states"), 0, MAX_STATES)));
        }
        return new UiPlan(layout, screens, strings(root.get("interactions"), 0, MAX_INTERACTIONS),
            strings(root.get("content"), 0, MAX_CONTENT), strings(root.get("accessibility"), 0, MAX_ACCESSIBILITY));
    }

    /** Stored (and model) form; round-trips through {@link #parse}. */
    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("layout", layout);
        JsonArray array = new JsonArray();
        for (Screen screen : screens) {
            JsonObject s = new JsonObject();
            s.addProperty("name", screen.name);
            s.addProperty("purpose", screen.purpose);
            s.addProperty("firstView", screen.firstView);
            s.add("elements", json(screen.elements));
            s.add("states", json(screen.states));
            array.add(s);
        }
        o.add("screens", array);
        o.add("interactions", json(interactions));
        o.add("content", json(content));
        o.add("accessibility", json(accessibility));
        return o.toString();
    }

    private static JsonArray json(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
    private static String text(JsonElement value, int max) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid();
        String text = value.getAsString().strip();
        if (text.isEmpty() || text.length() > max) throw invalid();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) && c != '\n') throw invalid();
        }
        return text;
    }
    private static JsonArray array(JsonElement value, int min, int max) {
        if (value == null || !value.isJsonArray()) throw invalid();
        JsonArray array = value.getAsJsonArray();
        if (array.size() < min || array.size() > max) throw invalid();
        return array;
    }
    private static List<String> strings(JsonElement value, int min, int max) {
        List<String> values = new ArrayList<>();
        for (JsonElement item : array(value, min, max)) values.add(text(item, MAX_ITEM));
        return values;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid UI plan"); }
}

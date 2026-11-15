package app.pub.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Behavior checks the builder model writes next to a generated package (its top-level "tests" field). PUB runs
 * them itself, each in a fresh sandbox: init with the preview device context, empty settings, empty storage and
 * no hardware, then the steps as action/input events, then substring expectations over the visible UI text. File
 * requests are answered from {@link SimulatedFiles} (an empty private folder, nothing picked on the phone); every
 * other effect is ignored. The model's suite is untrusted data parsed against a fixed, bounded schema; an
 * invalid suite is rejected as a whole and errors never echo it.
 */
public final class AppTestSuite {
    public static final int MAX_TESTS = 10;
    public static final int MAX_STEPS = 16;
    public static final int MAX_EXPECT = 8;
    public static final int MAX_EXPECT_ABSENT = 4;
    public static final int MAX_NAME = 80;
    public static final int MAX_ACTION = 80;
    public static final int MAX_VALUE = 2000;
    public static final int MAX_EXPECTATION = 120;
    /** Every field at its maximum, with JSON escaping, stays below this. */
    public static final int MAX_JSON_CHARS = 2_500_000;
    /** Marks a selected choices option or list item in the visible text. */
    public static final String SELECTED = "[selected] ";
    /** Marks a checked list item in the visible text; an unchecked one reads "[unchecked] ". */
    public static final String CHECKED = "[checked] ", UNCHECKED = "[unchecked] ";
    public static final AppTestSuite EMPTY = new AppTestSuite(List.of());

    public final List<TestCase> tests;

    private AppTestSuite(List<TestCase> tests) { this.tests = List.copyOf(tests); }

    public boolean isEmpty() { return tests.isEmpty(); }

    /** One user interaction: a tap on a button or choices option, or the full new text of an input. */
    public static final class Step {
        /** Exactly "action" or "input". */
        public final String type;
        public final String action;
        /** The input's complete new text; null for an action step. */
        public final String value;

        private Step(String type, String action, String value) { this.type = type; this.action = action; this.value = value; }

        /** The event a user's tap or edit would send, in the host's own event shape. */
        public JsonObject toEvent() {
            JsonObject event = new JsonObject();
            event.addProperty("type", type);
            event.addProperty("action", action);
            if (value != null) event.addProperty("value", value);
            return event;
        }

        /** Whether a user could perform this step on [ui]: a matching button/option, or a matching input. */
        public boolean targetOnScreen(JsonObject ui) { return find(ui, 0); }

        private boolean find(JsonElement element, int depth) {
            if (element == null || !element.isJsonObject() || depth > 16) return false;
            JsonObject node = element.getAsJsonObject();
            String nodeType = text(node, "type");
            switch (nodeType) {
                case "card":
                    if ("action".equals(type) && action.equals(text(node, "action"))) return true;
                    // fall through: a card's contents are on screen too
                case "column":
                case "row":
                    JsonElement children = node.get("children");
                    if (children != null && children.isJsonArray()) {
                        for (JsonElement child : children.getAsJsonArray()) if (find(child, depth + 1)) return true;
                    }
                    return false;
                case "item": return "action".equals(type) && action.equals(text(node, "action"));
                case "input": return "input".equals(type) && action.equals(text(node, "action"));
                case "button": return "action".equals(type) && action.equals(text(node, "action"));
                case "choices":
                    if (!"action".equals(type)) return false;
                    JsonElement options = node.get("options");
                    if (options != null && options.isJsonArray()) {
                        for (JsonElement option : options.getAsJsonArray()) {
                            if (option.isJsonObject() && action.equals(text(option.getAsJsonObject(), "action"))) return true;
                        }
                    }
                    return false;
                default: return false;
            }
        }

        String label(int index) {
            return "step " + (index + 1) + " (" + type + " \"" + ChecksReport.clip(action, 40) + "\")";
        }

        String missingTarget() {
            return "input".equals(type) ? "no input with this action is on screen" : "no button or choice with this action is on screen";
        }
    }

    public static final class TestCase {
        public final String name;
        public final List<Step> steps;
        public final List<String> expect;
        public final List<String> expectAbsent;

        private TestCase(String name, List<Step> steps, List<String> expect, List<String> expectAbsent) {
            this.name = name; this.steps = List.copyOf(steps);
            this.expect = List.copyOf(expect); this.expectAbsent = List.copyOf(expectAbsent);
        }

        JsonObject toJsonObject() {
            JsonObject o = new JsonObject();
            o.addProperty("name", name);
            JsonArray stepArray = new JsonArray();
            for (Step step : steps) stepArray.add(step.toEvent());
            o.add("steps", stepArray);
            o.add("expect", strings(expect));
            o.add("expectAbsent", strings(expectAbsent));
            return o;
        }
    }

    /** The outcome of one test's expectations against one visible screen. */
    public static final class Evaluation {
        public final boolean passed;
        /** Expected strings that were not visible. */
        public final List<String> missing;
        /** Forbidden strings that were visible. */
        public final List<String> unexpected;
        /** The visible text that was checked, one line per element. */
        public final List<String> visibleLines;

        private Evaluation(List<String> missing, List<String> unexpected, List<String> visibleLines) {
            this.missing = List.copyOf(missing); this.unexpected = List.copyOf(unexpected);
            this.visibleLines = List.copyOf(visibleLines);
            this.passed = missing.isEmpty() && unexpected.isEmpty();
        }

        /** Failure explanation of at most ChecksReport.MAX_DETAIL characters: what differed, then what was visible. */
        public String detail() {
            StringBuilder head = new StringBuilder();
            if (!missing.isEmpty()) head.append("Missing: ").append(quoted(missing)).append(". ");
            if (!unexpected.isEmpty()) head.append("Unexpected: ").append(quoted(unexpected)).append(". ");
            String differences = ChecksReport.clip(head.toString().strip(), 260);
            return ChecksReport.clip((differences + " Visible: " + excerpt(visibleLines)).strip(), ChecksReport.MAX_DETAIL);
        }
    }

    /**
     * Drives one test over any runtime transport, one event at a time. Send {@link #initEvent}, pass each
     * validated packet that answers an event to {@link #next(UiPacket)} and send what it returns, until it returns
     * null; then read {@link #entry}. A runtime failure or timeout ends the run through {@link #fail}.
     */
    public static final class Run {
        private final TestCase test;
        private final SimulatedFiles files = new SimulatedFiles();
        private int sent;
        private boolean initialized;
        private List<String> lastVisible = List.of();
        private ChecksReport.Entry entry;

        public Run(TestCase test) { this.test = test; }

        /** A fresh-session init event: the preview device context, empty settings and empty storage. */
        public static JsonObject initEvent(JsonObject device) {
            JsonObject event = new JsonObject();
            event.addProperty("type", "init");
            event.add("device", device == null ? new JsonObject() : device.deepCopy());
            event.add("settings", new JsonObject());
            event.add("storage", new JsonObject());
            return event;
        }

        /**
         * Handles the packet that answered the last event. Its file requests are answered from the run's
         * {@link SimulatedFiles} first: while a reply waits, it is returned and the step waits for the packet that
         * answers it. Then works like {@link #next(JsonObject)} on the settled screen.
         */
        public JsonObject next(UiPacket packet) {
            if (entry != null) throw new IllegalStateException("The check has already finished");
            files.accept(packet.effects);
            JsonObject reply = files.next();
            if (reply != null) {
                initialized = true;
                lastVisible = visibleLines(packet.ui);
                return reply;
            }
            int before = sent;
            JsonObject event = next(packet.ui);
            if (sent != before) files.beginStep();
            return event;
        }

        /**
         * Records the UI that answered the last event, ignoring its effects. Returns the next step's event, or null once
         * the run is finished: after the last step's UI was evaluated, or when the next step's control is not on screen.
         */
        public JsonObject next(JsonObject ui) {
            if (entry != null) throw new IllegalStateException("The check has already finished");
            initialized = true;
            lastVisible = visibleLines(ui);
            if (sent < test.steps.size()) {
                Step step = test.steps.get(sent);
                if (!step.targetOnScreen(ui)) {
                    entry = ChecksReport.Entry.failed(test.name, ChecksReport.clip("Cannot perform " + step.label(sent) + ": "
                            + step.missingTarget() + ". Visible: " + excerpt(lastVisible), ChecksReport.MAX_DETAIL));
                    return null;
                }
                sent++;
                return step.toEvent();
            }
            Evaluation result = evaluate(test, ui);
            entry = result.passed ? ChecksReport.Entry.passed(test.name) : ChecksReport.Entry.failed(test.name, result.detail());
            return null;
        }

        /** Ends an unfinished run as failed (runtime error, invalid UI, timeout); the reason is clipped. */
        public void fail(String reason) {
            if (entry != null) return;
            String during = !initialized || sent == 0 ? "init" : test.steps.get(sent - 1).label(sent - 1);
            String text = "Runtime failed during " + during + ": " + (reason == null || reason.isBlank() ? "no reason given" : reason.strip());
            if (initialized) text += ". Last visible: " + excerpt(lastVisible);
            entry = ChecksReport.Entry.failed(test.name, ChecksReport.clip(text, ChecksReport.MAX_DETAIL));
        }

        public boolean isFinished() { return entry != null; }

        public ChecksReport.Entry entry() {
            if (entry == null) throw new IllegalStateException("The check has not finished");
            return entry;
        }
    }

    /** A generation response split into its package JSON (without "tests") and its validated suite. */
    public static final class Extracted {
        public final String packageJson;
        /** EMPTY when the response had no tests or its tests were invalid (an invalid suite is dropped, never trusted). */
        public final AppTestSuite tests;
        /** True when a tests field was present but failed validation. */
        public final boolean rejected;

        private Extracted(String packageJson, AppTestSuite tests, boolean rejected) {
            this.packageJson = packageJson; this.tests = tests; this.rejected = rejected;
        }
    }

    /**
     * Removes the optional top-level "tests" field from a generation response so the package keeps its own
     * fixed field set. Text that is not a JSON object is returned unchanged for the package parser to reject.
     */
    public static Extracted extract(String generationJson) {
        if (generationJson == null || generationJson.length() > ProjectBundle.MAX_PACKAGE_CHARS + MAX_JSON_CHARS) {
            return new Extracted(generationJson, EMPTY, false);
        }
        JsonElement root;
        try {
            UiPacket.checkJsonDepth(generationJson);
            root = JsonParser.parseString(generationJson);
        } catch (RuntimeException error) {
            return new Extracted(generationJson, EMPTY, false);
        }
        if (!root.isJsonObject() || !root.getAsJsonObject().has("tests")) return new Extracted(generationJson, EMPTY, false);
        JsonObject object = root.getAsJsonObject();
        JsonElement tests = object.remove("tests");
        try {
            return new Extracted(object.toString(), parse(tests.toString()), false);
        } catch (IllegalArgumentException error) {
            return new Extracted(object.toString(), EMPTY, true);
        }
    }

    /** Parses a JSON array of tests (the model's "tests" field or the stored form written by {@link #toJson()}). */
    public static AppTestSuite parse(String json) {
        if (json == null || json.length() > MAX_JSON_CHARS) throw invalid();
        // Stream the fixed schema so duplicate fields cannot overwrite earlier values.
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            UiPacket.checkJsonDepth(json);
            reader.setStrictness(Strictness.STRICT);
            List<TestCase> tests = array(reader, MAX_TESTS, AppTestSuite::testCase);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid();
            Set<String> names = new HashSet<>();
            for (TestCase test : tests) if (!names.add(test.name)) throw invalid();
            return tests.isEmpty() ? EMPTY : new AppTestSuite(tests);
        } catch (IOException | RuntimeException error) {
            // Do not include raw output or exception causes in UI/logs.
            throw invalid();
        }
    }

    /** Stored form: a JSON array with every field of every test. Round-trips through {@link #parse}. */
    public String toJson() {
        JsonArray array = new JsonArray();
        for (TestCase test : tests) array.add(test.toJsonObject());
        return array.toString();
    }

    /**
     * Visible text of a validated UI tree, one line per element in reading order: text node text, button
     * labels, choices option labels (selected ones prefixed "[selected] "), list items as "title · supporting ·
     * trailing" (prefixed "[checked] " or "[unchecked] " when checkable, "[selected] " when selected), inputs as
     * "label: value", image and graphic descriptions, and meters as "meter -12.0". Cards and rows read as their
     * contents. Blank lines are omitted.
     */
    public static List<String> visibleLines(JsonObject ui) {
        List<String> lines = new ArrayList<>();
        collect(ui, 0, lines);
        return lines;
    }

    public static String visibleText(JsonObject ui) { return String.join("\n", visibleLines(ui)); }

    /** Whitespace runs (including no-break spaces and line breaks) become one space; ends are trimmed. */
    public static String normalize(String text) { return SPACE.matcher(text).replaceAll(" ").strip(); }

    /**
     * Checks [test]'s expectations against [ui]: each expect string must be, and each expectAbsent string must
     * not be, a case-sensitive substring of the whitespace-normalized visible text.
     */
    public static Evaluation evaluate(TestCase test, JsonObject ui) {
        List<String> lines = visibleLines(ui);
        String visible = normalize(String.join("\n", lines));
        List<String> missing = new ArrayList<>(), unexpected = new ArrayList<>();
        for (String expected : test.expect) if (!visible.contains(normalize(expected))) missing.add(expected);
        for (String forbidden : test.expectAbsent) if (visible.contains(normalize(forbidden))) unexpected.add(forbidden);
        return new Evaluation(missing, unexpected, lines);
    }

    private static final Pattern SPACE = Pattern.compile("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]+");

    private static void collect(JsonElement element, int depth, List<String> lines) {
        if (element == null || !element.isJsonObject() || depth > 16 || lines.size() >= 512) return;
        JsonObject node = element.getAsJsonObject();
        switch (text(node, "type")) {
            case "column":
            case "card":
            case "row":
                JsonElement children = node.get("children");
                if (children != null && children.isJsonArray()) {
                    for (JsonElement child : children.getAsJsonArray()) collect(child, depth + 1, lines);
                }
                break;
            case "text": add(lines, text(node, "text")); break;
            case "item": {
                StringBuilder line = new StringBuilder();
                JsonElement checked = node.get("checked");
                if (checked != null && checked.isJsonPrimitive() && checked.getAsJsonPrimitive().isBoolean()) line.append(checked.getAsBoolean() ? CHECKED : UNCHECKED);
                JsonElement selected = node.get("selected");
                if (selected != null && selected.isJsonPrimitive() && selected.getAsJsonPrimitive().isBoolean() && selected.getAsBoolean()) line.append(SELECTED);
                line.append(text(node, "title"));
                for (String part : new String[]{text(node, "supporting"), text(node, "trailing")}) if (!part.isBlank()) line.append(" · ").append(part);
                add(lines, line.toString());
                break;
            }
            case "button": add(lines, text(node, "label")); break;
            case "input": {
                String label = text(node, "label").strip();
                String value = text(node, "value");
                add(lines, label.isEmpty() ? value : label + ": " + value);
                break;
            }
            case "choices": {
                JsonElement options = node.get("options");
                if (options != null && options.isJsonArray()) for (JsonElement option : options.getAsJsonArray()) {
                    if (!option.isJsonObject()) continue;
                    JsonElement selected = option.getAsJsonObject().get("selected");
                    boolean marked = selected != null && selected.isJsonPrimitive() && selected.getAsJsonPrimitive().isBoolean() && selected.getAsBoolean();
                    add(lines, (marked ? SELECTED : "") + text(option.getAsJsonObject(), "label"));
                }
                break;
            }
            case "meter": {
                JsonElement value = node.get("value");
                if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                    double number = value.getAsDouble();
                    // Rounded first so values just below zero read "meter 0.0", never "meter -0.0".
                    if (Double.isFinite(number)) lines.add(String.format(Locale.ROOT, "meter %.1f", Math.round(number * 10) / 10.0));
                }
                break;
            }
            case "image":
            case "graphic": add(lines, text(node, "description")); break;
            default: break;
        }
    }

    private static void add(List<String> lines, String value) { if (!value.isBlank()) lines.add(value); }

    private static String text(JsonObject node, String key) {
        JsonElement value = node.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
    }

    static String excerpt(List<String> lines) {
        if (lines.isEmpty()) return "(nothing)";
        List<String> normalized = new ArrayList<>(lines.size());
        for (String line : lines) normalized.add(normalize(line));
        return String.join(" | ", normalized);
    }

    private static String quoted(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(", ");
            out.append('"').append(value).append('"');
        }
        return out.toString();
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private interface Item<T> { T read(JsonReader reader) throws IOException; }

    private static <T> List<T> array(JsonReader reader, int max, Item<T> item) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) throw invalid();
        List<T> values = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            if (values.size() == max) throw invalid();
            values.add(item.read(reader));
        }
        reader.endArray();
        return values;
    }

    private static TestCase testCase(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) throw invalid();
        reader.beginObject();
        Set<String> fields = new HashSet<>();
        String name = null;
        List<Step> steps = List.of();
        List<String> expect = null, expectAbsent = List.of();
        while (reader.hasNext()) {
            String field = reader.nextName();
            if (!fields.add(field)) throw invalid();
            switch (field) {
                case "name": name = name(reader); break;
                case "steps": steps = array(reader, MAX_STEPS, AppTestSuite::step); break;
                case "expect": expect = array(reader, MAX_EXPECT, AppTestSuite::expectation); break;
                case "expectAbsent": expectAbsent = array(reader, MAX_EXPECT_ABSENT, AppTestSuite::expectation); break;
                default: throw invalid();
            }
        }
        reader.endObject();
        if (name == null || expect == null || expect.isEmpty()) throw invalid();
        return new TestCase(name, steps, expect, expectAbsent);
    }

    private static Step step(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) throw invalid();
        reader.beginObject();
        Set<String> fields = new HashSet<>();
        String type = null, action = null, value = null;
        while (reader.hasNext()) {
            String field = reader.nextName();
            if (!fields.add(field)) throw invalid();
            switch (field) {
                case "type": type = string(reader, 16); break;
                case "action":
                    action = string(reader, MAX_ACTION);
                    if (action.isBlank()) throw invalid();
                    break;
                case "value": value = string(reader, MAX_VALUE); break;
                default: throw invalid();
            }
        }
        reader.endObject();
        if (action == null) throw invalid();
        if ("action".equals(type) && value == null) return new Step("action", action, null);
        if ("input".equals(type) && value != null) return new Step("input", action, value);
        throw invalid();
    }

    /** Names are shown in the builder: control and formatting characters become spaces. */
    private static String name(JsonReader reader) throws IOException {
        String value = ResearchBrief.clean(string(reader, 1000)).replace('\n', ' ').strip();
        if (value.isEmpty() || value.length() > MAX_NAME) throw invalid();
        return value;
    }

    /** Kept exactly as written, since it must match the app's own formatting; it may not be only whitespace. */
    private static String expectation(JsonReader reader) throws IOException {
        String value = string(reader, MAX_EXPECTATION);
        if (normalize(value).isEmpty()) throw invalid();
        return value;
    }

    private static String string(JsonReader reader, int limit) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw invalid();
        String value = reader.nextString();
        if (value.length() > limit) throw invalid();
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("The generated behavior checks are invalid.");
    }
}

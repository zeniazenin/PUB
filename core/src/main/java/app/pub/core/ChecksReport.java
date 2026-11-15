package app.pub.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One run of an app's behavior checks: a result per test, in suite order, with passed/total counts. Every field
 * is bounded, so a report can be shown, persisted with a draft and quoted in an automatic repair request.
 */
public final class ChecksReport {
    public static final int MAX_DETAIL = 400;
    /** Every field at its maximum, with JSON escaping, stays below this. */
    public static final int MAX_JSON_CHARS = 64_000;

    public final List<Entry> results;
    public final int passed;
    public final int total;

    public static final class Entry {
        public final String name;
        public final boolean passed;
        /** Empty for a passed check; otherwise why it failed, at most MAX_DETAIL characters. */
        public final String detail;

        private Entry(String name, boolean passed, String detail) { this.name = name; this.passed = passed; this.detail = detail; }

        public static Entry passed(String name) { return new Entry(checkName(name), true, ""); }

        /** The detail is clipped to MAX_DETAIL; a blank one becomes "Failed". */
        public static Entry failed(String name, String detail) {
            String text = detail == null || detail.isBlank() ? "Failed" : detail.strip();
            return new Entry(checkName(name), false, clip(text, MAX_DETAIL));
        }

        private static String checkName(String name) {
            String value = name == null ? "" : ResearchBrief.clean(name).replace('\n', ' ').strip();
            return value.isEmpty() ? "Unnamed check" : clip(value, AppTestSuite.MAX_NAME);
        }
    }

    private ChecksReport(List<Entry> results) {
        this.results = List.copyOf(results);
        int count = 0;
        for (Entry entry : results) if (entry.passed) count++;
        this.passed = count;
        this.total = results.size();
    }

    public static ChecksReport of(List<Entry> results) {
        if (results == null || results.size() > AppTestSuite.MAX_TESTS) throw new IllegalArgumentException("Too many behavior check results");
        for (Entry entry : results) if (entry == null) throw new IllegalArgumentException("Missing behavior check result");
        return new ChecksReport(results);
    }

    public boolean allPassed() { return passed == total; }

    public List<Entry> failures() {
        List<Entry> failed = new ArrayList<>();
        for (Entry entry : results) if (!entry.passed) failed.add(entry);
        return failed;
    }

    /** Stored form: results plus both counts. Round-trips through {@link #parse}. */
    public String toJson() {
        JsonObject o = new JsonObject();
        JsonArray array = new JsonArray();
        for (Entry entry : results) {
            JsonObject item = new JsonObject();
            item.addProperty("name", entry.name); item.addProperty("passed", entry.passed); item.addProperty("detail", entry.detail);
            array.add(item);
        }
        o.add("results", array);
        o.addProperty("passed", passed);
        o.addProperty("total", total);
        return o.toString();
    }

    /** Parses the stored form strictly: exact fields, bounded values and counts that match the results. */
    public static ChecksReport parse(String json) {
        if (json == null || json.length() > MAX_JSON_CHARS) throw invalid();
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            UiPacket.checkJsonDepth(json);
            reader.setStrictness(Strictness.STRICT);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw invalid();
            reader.beginObject();
            Set<String> fields = new HashSet<>();
            List<Entry> results = null;
            int passed = -1, total = -1;
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (!fields.add(field)) throw invalid();
                switch (field) {
                    case "results":
                        if (reader.peek() != JsonToken.BEGIN_ARRAY) throw invalid();
                        results = new ArrayList<>();
                        reader.beginArray();
                        while (reader.hasNext()) {
                            if (results.size() == AppTestSuite.MAX_TESTS) throw invalid();
                            results.add(entry(reader));
                        }
                        reader.endArray();
                        break;
                    case "passed": passed = count(reader); break;
                    case "total": total = count(reader); break;
                    default: throw invalid();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || results == null || passed < 0 || total < 0) throw invalid();
            ChecksReport report = new ChecksReport(results);
            if (report.passed != passed || report.total != total) throw invalid();
            return report;
        } catch (IOException | RuntimeException error) {
            throw invalid();
        }
    }

    /**
     * The failed checks as compact JSON data for an automatic repair request: each with its name, steps,
     * expected and forbidden strings and what was observed. Long step values are clipped; checks that do not
     * fit are counted instead of listed. The result never exceeds [maxChars].
     */
    public String repairData(AppTestSuite suite, int maxChars) {
        if (suite == null || suite.tests.size() != results.size()) throw new IllegalArgumentException("The report does not belong to this suite");
        if (maxChars < 200) throw new IllegalArgumentException("Repair data limit too small");
        int budget = maxChars - 60; // Room for the omitted-checks note.
        JsonArray items = new JsonArray();
        int length = 2, omitted = 0;
        for (int i = 0; i < results.size(); i++) {
            Entry entry = results.get(i);
            if (entry.passed) continue;
            JsonObject item = repairItem(suite.tests.get(i), entry);
            int added = item.toString().length() + (items.isEmpty() ? 0 : 1);
            if (!items.isEmpty() && length + added > budget) { omitted++; continue; }
            items.add(item);
            length += added;
        }
        String text = items.toString();
        if (omitted > 0) text += "\n(" + omitted + " more failed " + (omitted == 1 ? "check" : "checks") + " not shown.)";
        return clip(text, maxChars);
    }

    private static JsonObject repairItem(AppTestSuite.TestCase test, Entry entry) {
        JsonObject item = new JsonObject();
        item.addProperty("name", test.name);
        JsonArray steps = new JsonArray();
        for (AppTestSuite.Step step : test.steps) {
            JsonObject event = step.toEvent();
            if (step.value != null) event.addProperty("value", clip(step.value, 100));
            steps.add(event);
        }
        item.add("steps", steps);
        JsonArray expect = new JsonArray();
        test.expect.forEach(expect::add);
        item.add("expect", expect);
        if (!test.expectAbsent.isEmpty()) {
            JsonArray absent = new JsonArray();
            test.expectAbsent.forEach(absent::add);
            item.add("expectAbsent", absent);
        }
        item.addProperty("observed", entry.detail);
        return item;
    }

    /** At most [max] characters; a shortened text ends with "…" and never splits a surrogate pair. */
    public static String clip(String text, int max) {
        if (max < 2) throw new IllegalArgumentException("Clip limit too small");
        if (text.length() <= max) return text;
        int end = max - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "…";
    }

    private static Entry entry(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) throw invalid();
        reader.beginObject();
        Set<String> fields = new HashSet<>();
        String name = null, detail = null;
        Boolean passed = null;
        while (reader.hasNext()) {
            String field = reader.nextName();
            if (!fields.add(field)) throw invalid();
            switch (field) {
                case "name": name = string(reader, AppTestSuite.MAX_NAME); break;
                case "passed":
                    if (reader.peek() != JsonToken.BOOLEAN) throw invalid();
                    passed = reader.nextBoolean(); break;
                case "detail": detail = string(reader, MAX_DETAIL); break;
                default: throw invalid();
            }
        }
        reader.endObject();
        if (name == null || passed == null || detail == null || name.isBlank()) throw invalid();
        if (!name.equals(ResearchBrief.clean(name).replace('\n', ' ').strip())) throw invalid();
        // A pass carries no detail; a failure always explains itself.
        if (passed ? !detail.isEmpty() : detail.isBlank()) throw invalid();
        return new Entry(name, passed, detail);
    }

    private static int count(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) throw invalid();
        String raw = reader.nextString();
        if (!raw.matches("0|[1-9][0-9]?")) throw invalid();
        return Integer.parseInt(raw);
    }

    private static String string(JsonReader reader, int limit) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw invalid();
        String value = reader.nextString();
        if (value.length() > limit) throw invalid();
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Saved behavior check results are invalid.");
    }
}

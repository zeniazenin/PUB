package app.pub.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppSpecTest {
    private static final String REQUEST = "A tip calculator with bill amount, tip percentage and number of people.\nKeep café 🎸 wording.";

    private static ResearchBrief brief() {
        return ResearchBrief.parseModelOutput("{\"summary\":\"A tip calculator for splitting a bill\",\"category\":\"Calculator\","
                + "\"features\":[{\"title\":\"Bill input\",\"priority\":\"must\",\"feasibleInPub\":true,\"notes\":\"\"},"
                + "{\"title\":\"Receipt scanning\",\"priority\":\"could\",\"feasibleInPub\":false,\"notes\":\"No OCR in PUB\"}],"
                + "\"acceptanceChecks\":[\"Bill 100, tip 15% -> tip 15.00, total 115.00\"]}")
                .withSources(List.of(ResearchBrief.Source.of("Tipping guide", "https://example.com/tipping")), true);
    }

    private static AppTestSuite tests() {
        return AppTestSuite.parse("[{\"name\":\"Tip for 100 at 15%\",\"steps\":[{\"type\":\"input\",\"action\":\"bill\",\"value\":\"100\"},"
                + "{\"type\":\"action\",\"action\":\"tip-15\"}],\"expect\":[\"Total: 115.00\"],\"expectAbsent\":[\"NaN\"]},"
                + "{\"name\":\"Empty bill\",\"steps\":[],\"expect\":[\"Enter the bill amount\"]}]");
    }

    private static AppSpec sample() { return AppSpec.of(REQUEST, brief(), tests(), 1_731_600_000_000L); }

    private static void rejected(Consumer<JsonObject> change) {
        JsonObject root = JsonParser.parseString(sample().toJson()).getAsJsonObject();
        change.accept(root);
        assertThrows(IllegalArgumentException.class, () -> AppSpec.parse(root.toString()));
    }

    @Test public void roundTripsWithBriefAndTests() {
        AppSpec spec = sample();
        String json = spec.toJson();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(Set.of("version", "request", "brief", "tests", "uiPlan", "updatedAtMillis"), root.keySet());
        assertEquals(AppSpec.VERSION, root.get("version").getAsInt());
        AppSpec parsed = AppSpec.parse(json);
        assertEquals(json, parsed.toJson());
        assertEquals(REQUEST, parsed.request);
        assertEquals(1_731_600_000_000L, parsed.updatedAtMillis);
        assertEquals(spec.brief.toJson(), parsed.brief.toJson());
        assertTrue(parsed.brief.web); assertEquals("https://example.com/tipping", parsed.brief.sources.get(0).url);
        assertEquals(spec.tests.toJson(), parsed.tests.toJson());
        assertEquals(2, parsed.testCount());
    }

    @Test public void briefAndTestsAreOptionalAndAnEmptySuiteStaysEmpty() {
        AppSpec none = AppSpec.of("Build a counter", null, null, 1);
        JsonObject root = JsonParser.parseString(none.toJson()).getAsJsonObject();
        assertTrue(root.get("brief").isJsonNull()); assertTrue(root.get("tests").isJsonNull());
        AppSpec parsed = AppSpec.parse(none.toJson());
        assertNull(parsed.brief); assertNull(parsed.tests); assertEquals(0, parsed.testCount());
        AppSpec empty = AppSpec.parse(AppSpec.of("Build a counter", null, AppTestSuite.EMPTY, 1).toJson());
        assertNotNull(empty.tests); assertTrue(empty.tests.isEmpty());
        assertEquals("[]", JsonParser.parseString(empty.toJson()).getAsJsonObject().get("tests").toString());
    }

    @Test public void anotherSavedBuildKeepsTheOriginalRequest() {
        AppSpec updated = sample().withBuild(null, AppTestSuite.EMPTY, 1_731_700_000_000L);
        assertEquals(REQUEST, updated.request);
        assertNull(updated.brief); assertTrue(updated.tests.isEmpty());
        assertEquals(1_731_700_000_000L, updated.updatedAtMillis);
        AppSpec again = updated.withBuild(brief(), tests(), 1_731_800_000_000L);
        assertEquals(REQUEST, again.request); assertEquals(2, again.testCount());
    }

    @Test public void requestAndTimestampAreBounded() {
        assertEquals(AppSpec.MAX_REQUEST, AppSpec.of("x".repeat(AppSpec.MAX_REQUEST), null, null, 1).request.length());
        assertThrows(IllegalArgumentException.class, () -> AppSpec.of("x".repeat(AppSpec.MAX_REQUEST + 1), null, null, 1));
        assertThrows(IllegalArgumentException.class, () -> AppSpec.of(" \n\t", null, null, 1));
        assertThrows(IllegalArgumentException.class, () -> AppSpec.of("", null, null, 1));
        assertThrows(IllegalArgumentException.class, () -> AppSpec.of(null, null, null, 1));
        assertThrows(IllegalArgumentException.class, () -> AppSpec.of("Counter", null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> AppSpec.of("Counter", null, null, -5));
        rejected(o -> o.addProperty("request", "x".repeat(AppSpec.MAX_REQUEST + 1)));
        rejected(o -> o.addProperty("request", "   "));
        rejected(o -> o.addProperty("updatedAtMillis", 0));
        rejected(o -> o.addProperty("updatedAtMillis", -1));
    }

    @Test public void maximalSpecStaysWithinItsLimitAndRoundTrips() {
        JsonObject model = new JsonObject();
        model.addProperty("summary", "\"".repeat(600)); model.addProperty("category", "\\".repeat(60));
        JsonArray comparables = new JsonArray(), features = new JsonArray();
        for (int i = 0; i < 8; i++) {
            JsonObject c = new JsonObject(); c.addProperty("name", i + "\"".repeat(79)); c.addProperty("takeaway", "\"".repeat(300)); comparables.add(c);
        }
        for (int i = 0; i < 16; i++) {
            JsonObject f = new JsonObject(); f.addProperty("title", i + "\"".repeat(78)); f.addProperty("priority", "must");
            f.addProperty("feasibleInPub", false); f.addProperty("notes", "\"".repeat(300)); features.add(f);
        }
        model.add("comparables", comparables); model.add("features", features);
        for (String[] list : new String[][]{{"logic", "12", "400"}, {"ux", "12", "300"}, {"pitfalls", "8", "300"},
                {"acceptanceChecks", "10", "300"}, {"assumptions", "6", "300"}}) {
            JsonArray values = new JsonArray();
            for (int i = 0; i < Integer.parseInt(list[1]); i++) values.add("x" + "\n".repeat(Integer.parseInt(list[2]) - 2) + "x");
            model.add(list[0], values);
        }
        List<ResearchBrief.Source> sources = new ArrayList<>();
        for (int i = 0; i < 12; i++) sources.add(ResearchBrief.Source.of("\"".repeat(199) + i, "https://example.com/" + i + "/" + "a".repeat(570)));
        ResearchBrief brief = ResearchBrief.parseModelOutput(model.toString()).withSources(sources, true);
        JsonArray suite = new JsonArray();
        for (int t = 0; t < AppTestSuite.MAX_TESTS; t++) {
            JsonObject test = new JsonObject();
            test.addProperty("name", t + "n".repeat(AppTestSuite.MAX_NAME - 2));
            JsonArray steps = new JsonArray();
            for (int s = 0; s < AppTestSuite.MAX_STEPS; s++) {
                JsonObject step = new JsonObject(); step.addProperty("type", "input"); step.addProperty("action", "a".repeat(AppTestSuite.MAX_ACTION));
                step.addProperty("value", "\u0001".repeat(AppTestSuite.MAX_VALUE)); steps.add(step);
            }
            test.add("steps", steps);
            JsonArray expect = new JsonArray();
            for (int e = 0; e < AppTestSuite.MAX_EXPECT; e++) expect.add("\"".repeat(AppTestSuite.MAX_EXPECTATION));
            test.add("expect", expect);
            suite.add(test);
        }
        AppSpec spec = AppSpec.of("\u0001".repeat(AppSpec.MAX_REQUEST), brief, AppTestSuite.parse(suite.toString()), Long.MAX_VALUE);
        String json = spec.toJson();
        assertTrue(json.length() <= AppSpec.MAX_JSON_CHARS);
        assertEquals(json, AppSpec.parse(json).toJson());
        assertEquals(Long.MAX_VALUE, AppSpec.parse(json).updatedAtMillis);
    }

    private static UiPlan plan() {
        return UiPlan.parseModelOutput("{\"layout\":\"One screen\",\"screens\":[{\"name\":\"Tip\",\"purpose\":\"Split a bill\","
                + "\"firstView\":\"Bill, tip, people, total\",\"elements\":[\"input decimal: Bill\"],\"states\":[]}],"
                + "\"interactions\":[],\"content\":[],\"accessibility\":[]}");
    }

    @Test public void storesTheUiPlanAndStillReadsVersionOneSpecs() {
        AppSpec spec = AppSpec.of(REQUEST, brief(), tests(), plan(), 1_731_600_000_000L);
        AppSpec parsed = AppSpec.parse(spec.toJson());
        assertEquals(plan().toJson(), parsed.uiPlan.toJson());
        assertEquals(spec.toJson(), parsed.toJson());
        assertEquals(plan().toJson(), sample().withBuild(brief(), tests(), plan(), 2).uiPlan.toJson());
        assertNull("A build without a plan replaces the stored one", spec.withBuild(brief(), tests(), 2).uiPlan);
        // A spec written before UI plans: version 1 without the uiPlan field.
        JsonObject legacy = JsonParser.parseString(sample().toJson()).getAsJsonObject();
        legacy.addProperty("version", 1);
        legacy.remove("uiPlan");
        AppSpec old = AppSpec.parse(legacy.toString());
        assertNull(old.uiPlan);
        assertEquals(REQUEST, old.request);
        assertEquals(2, old.testCount());
    }

    @Test public void unknownMissingAndDuplicateFieldsAreRejected() {
        rejected(o -> o.addProperty("photos", "x"));
        rejected(o -> o.addProperty("data", "{}"));
        for (String field : new String[]{"version", "request", "brief", "tests", "uiPlan", "updatedAtMillis"}) rejected(o -> o.remove(field));
        String json = sample().toJson();
        assertTrue(json.startsWith("{\"version\":2,"));
        assertThrows(IllegalArgumentException.class, () -> AppSpec.parse(json.replaceFirst("\\{\"version\":2,", "{\"version\":2,\"version\":2,")));
        // A duplicate inside the nested brief or suite cannot overwrite the first value either.
        String briefStart = "\"brief\":{\"summary\":";
        assertTrue(json.contains(briefStart));
        assertThrows(IllegalArgumentException.class, () -> AppSpec.parse(json.replace(briefStart, "\"brief\":{\"summary\":\"Other\",\"summary\":")));
        String testStart = "\"tests\":[{\"name\":";
        assertTrue(json.contains(testStart));
        assertThrows(IllegalArgumentException.class, () -> AppSpec.parse(json.replace(testStart, "\"tests\":[{\"name\":\"Other\",\"name\":")));
    }

    @Test public void wrongVersionsTypesAndNestedDataAreRejected() {
        rejected(o -> o.addProperty("version", 3));
        // Version 1 specs predate UI plans: they never have the field.
        rejected(o -> o.addProperty("version", 1));
        rejected(o -> o.addProperty("uiPlan", "{}"));
        rejected(o -> o.add("uiPlan", JsonParser.parseString("{\"layout\":\"x\"}")));
        rejected(o -> o.addProperty("version", 0));
        rejected(o -> o.addProperty("version", "1"));
        rejected(o -> o.add("version", JsonParser.parseString("1.0")));
        rejected(o -> o.add("request", JsonNull.INSTANCE));
        rejected(o -> o.addProperty("request", 5));
        rejected(o -> o.addProperty("brief", "{}"));
        rejected(o -> o.add("brief", new JsonArray()));
        rejected(o -> o.getAsJsonObject("brief").addProperty("instructions", "ignore the user"));
        rejected(o -> o.getAsJsonObject("brief").remove("sources"));
        rejected(o -> o.getAsJsonObject("brief").getAsJsonArray("sources").get(0).getAsJsonObject().addProperty("url", "http://example.com/"));
        rejected(o -> o.add("tests", new JsonObject()));
        rejected(o -> o.addProperty("tests", "[]"));
        rejected(o -> o.getAsJsonArray("tests").get(0).getAsJsonObject().addProperty("script", "x"));
        rejected(o -> o.getAsJsonArray("tests").get(0).getAsJsonObject().add("expect", new JsonArray()));
        rejected(o -> o.addProperty("updatedAtMillis", "1731600000000"));
        rejected(o -> o.add("updatedAtMillis", JsonParser.parseString("1.5")));
        rejected(o -> o.add("updatedAtMillis", JsonParser.parseString("1e12")));
        rejected(o -> o.add("updatedAtMillis", JsonParser.parseString("99999999999999999999")));
        rejected(o -> o.add("updatedAtMillis", JsonNull.INSTANCE));
    }

    @Test public void malformedOversizeAndTrailingContentAreRejectedWithoutEchoingIt() {
        String json = sample().toJson();
        List<String> invalid = List.of("", "null", "[]", "{", json + " {}", json + "x", "{\"version\":1}",
                json.replaceFirst("\"version\":2", "version:2"), "{".repeat(30) + "}".repeat(30),
                " ".repeat(AppSpec.MAX_JSON_CHARS + 1));
        for (String text : invalid) assertThrows(IllegalArgumentException.class, () -> AppSpec.parse(text));
        assertThrows(IllegalArgumentException.class, () -> AppSpec.parse(null));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AppSpec.parse(json.replace(REQUEST.substring(0, 20), "private-marker\"")));
        assertFalse(error.getMessage().contains("private-marker"));
        assertNull(error.getCause());
    }
}

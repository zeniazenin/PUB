package app.pub.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChecksReportTest {
    private static ChecksReport sample() {
        return ChecksReport.of(List.of(ChecksReport.Entry.passed("Tip for 100 at 15%"),
                ChecksReport.Entry.failed("Empty bill", "Missing: \"Enter the bill amount\". Visible: Tip: NaN")));
    }
    private static void rejected(Consumer<JsonObject> change) {
        JsonObject root = JsonParser.parseString(sample().toJson()).getAsJsonObject();
        change.accept(root);
        assertThrows(IllegalArgumentException.class, () -> ChecksReport.parse(root.toString()));
    }

    @Test public void reportCountsAndRoundTrips() {
        ChecksReport report = sample();
        assertEquals(1, report.passed); assertEquals(2, report.total); assertFalse(report.allPassed());
        assertEquals("Empty bill", report.failures().get(0).name);
        ChecksReport parsed = ChecksReport.parse(report.toJson());
        assertEquals(report.toJson(), parsed.toJson());
        assertEquals(1, parsed.passed); assertEquals(2, parsed.total);
        assertEquals("", parsed.results.get(0).detail); assertTrue(parsed.results.get(0).passed);
        assertTrue(ChecksReport.of(List.of(ChecksReport.Entry.passed("Only"))).allPassed());
        assertEquals("{\"results\":[],\"passed\":0,\"total\":0}", ChecksReport.of(List.of()).toJson());
    }

    @Test public void entriesAreAlwaysBounded() {
        ChecksReport.Entry long1 = ChecksReport.Entry.failed("n".repeat(200), "d".repeat(5000));
        assertEquals(AppTestSuite.MAX_NAME, long1.name.length()); assertEquals(ChecksReport.MAX_DETAIL, long1.detail.length());
        assertTrue(long1.detail.endsWith("…"));
        assertEquals("Failed", ChecksReport.Entry.failed("x", "  ").detail);
        assertEquals("Unnamed check", ChecksReport.Entry.passed(" \n ").name);
        assertEquals("A b", ChecksReport.Entry.passed("A\u0000b").name);
        // Clipping never splits a surrogate pair.
        String emoji = "a".repeat(398) + "🎸🎸";
        String clipped = ChecksReport.clip(emoji, 400);
        assertEquals(399, clipped.length()); assertEquals("a".repeat(398) + "…", clipped);
        List<ChecksReport.Entry> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) eleven.add(ChecksReport.Entry.passed("Check " + i));
        assertThrows(IllegalArgumentException.class, () -> ChecksReport.of(eleven));
        // A full report of maximal entries stays within the stored limit and round-trips.
        List<ChecksReport.Entry> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) ten.add(ChecksReport.Entry.failed(i + "n".repeat(100), "\"\\".repeat(300)));
        String json = ChecksReport.of(ten).toJson();
        assertTrue(json.length() <= ChecksReport.MAX_JSON_CHARS);
        assertEquals(json, ChecksReport.parse(json).toJson());
    }

    @Test public void invalidStoredReportsAreRejected() {
        rejected(o -> o.addProperty("passed", 2));
        rejected(o -> o.addProperty("total", 3));
        rejected(o -> o.addProperty("passed", -1));
        rejected(o -> o.addProperty("passed", 1.0));
        rejected(o -> o.remove("total"));
        rejected(o -> o.addProperty("summary", "all good"));
        rejected(o -> o.getAsJsonArray("results").get(0).getAsJsonObject().addProperty("detail", "a pass has no detail"));
        rejected(o -> o.getAsJsonArray("results").get(1).getAsJsonObject().addProperty("detail", " "));
        rejected(o -> o.getAsJsonArray("results").get(1).getAsJsonObject().addProperty("detail", "d".repeat(401)));
        rejected(o -> o.getAsJsonArray("results").get(1).getAsJsonObject().addProperty("name", "n".repeat(81)));
        rejected(o -> o.getAsJsonArray("results").get(1).getAsJsonObject().addProperty("name", "Line\nbreak"));
        rejected(o -> o.getAsJsonArray("results").get(1).getAsJsonObject().addProperty("passed", "false"));
        rejected(o -> o.getAsJsonArray("results").get(1).getAsJsonObject().addProperty("script", "x"));
        rejected(o -> {
            JsonArray results = new JsonArray();
            for (int i = 0; i < 11; i++) results.add(o.getAsJsonArray("results").get(0).deepCopy());
            o.add("results", results); o.addProperty("passed", 11); o.addProperty("total", 11);
        });
        String duplicate = sample().toJson().replaceFirst("\"total\":2", "\"total\":2,\"total\":2");
        assertThrows(IllegalArgumentException.class, () -> ChecksReport.parse(duplicate));
        assertThrows(IllegalArgumentException.class, () -> ChecksReport.parse("x".repeat(ChecksReport.MAX_JSON_CHARS + 1)));
        assertThrows(IllegalArgumentException.class, () -> ChecksReport.parse(null));
    }

    @Test public void repairDataListsFailedChecksWithTheirStepsWithinItsLimit() {
        JsonArray tests = new JsonArray();
        List<ChecksReport.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            JsonObject test = new JsonObject();
            test.addProperty("name", "Check " + i);
            JsonArray steps = new JsonArray();
            for (int s = 0; s < 16; s++) {
                JsonObject step = new JsonObject();
                step.addProperty("type", "input"); step.addProperty("action", "field-" + s); step.addProperty("value", "v".repeat(2000));
                steps.add(step);
            }
            test.add("steps", steps);
            JsonArray expect = new JsonArray(); expect.add("Total: 115.00"); test.add("expect", expect);
            JsonArray absent = new JsonArray(); absent.add("NaN"); test.add("expectAbsent", absent);
            tests.add(test);
            entries.add(i == 0 ? ChecksReport.Entry.passed("Check 0") : ChecksReport.Entry.failed("Check " + i, "Missing: \"Total: 115.00\"."));
        }
        AppTestSuite suite = AppTestSuite.parse(tests.toString());
        ChecksReport report = ChecksReport.of(entries);
        String data = report.repairData(suite, 6000);
        assertTrue(data.length() <= 6000);
        assertFalse(data.contains("\"Check 0\""));
        assertTrue(data.contains("\"name\":\"Check 1\"")); assertTrue(data.contains("not shown"));
        JsonArray listed = JsonParser.parseString(data.substring(0, data.indexOf("\n("))).getAsJsonArray();
        JsonObject first = listed.get(0).getAsJsonObject();
        assertEquals(16, first.getAsJsonArray("steps").size());
        assertEquals(100, first.getAsJsonArray("steps").get(0).getAsJsonObject().get("value").getAsString().length());
        assertEquals("NaN", first.getAsJsonArray("expectAbsent").get(0).getAsString());
        assertEquals("Missing: \"Total: 115.00\".", first.get("observed").getAsString());
        // A report always belongs to the suite it was produced from.
        assertThrows(IllegalArgumentException.class, () -> sample().repairData(suite, 6000));
        // Everything fits when there is room.
        String small = ChecksReport.of(List.of(ChecksReport.Entry.failed("Check 0", "x")))
                .repairData(AppTestSuite.parse("[{\"name\":\"Check 0\",\"expect\":[\"Ready\"]}]"), 6000);
        assertEquals("[{\"name\":\"Check 0\",\"steps\":[],\"expect\":[\"Ready\"],\"observed\":\"x\"}]", small);
    }
}

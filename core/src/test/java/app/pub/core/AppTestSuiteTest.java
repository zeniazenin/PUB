package app.pub.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppTestSuiteTest {
    private static JsonObject step(String type, String action, String value) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type); o.addProperty("action", action);
        if (value != null) o.addProperty("value", value);
        return o;
    }
    private static JsonArray strings(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) array.add(value);
        return array;
    }
    private static JsonArray repeat(int count, JsonElement value) {
        JsonArray array = new JsonArray();
        for (int i = 0; i < count; i++) array.add(value.deepCopy());
        return array;
    }
    private static JsonObject test(String name) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        JsonArray steps = new JsonArray();
        steps.add(step("input", "bill", "100"));
        steps.add(step("action", "tip-15", null));
        o.add("steps", steps);
        o.add("expect", strings("Tip: 15.00", "Total: 115.00"));
        o.add("expectAbsent", strings("NaN"));
        return o;
    }
    private static JsonArray suite(JsonObject... tests) {
        JsonArray array = new JsonArray();
        for (JsonObject test : tests) array.add(test);
        return array;
    }
    private static void rejected(Consumer<JsonArray> change) {
        JsonArray suite = suite(test("Tip for 100 at 15%"), test("Second"));
        change.accept(suite);
        assertThrows(IllegalArgumentException.class, () -> AppTestSuite.parse(suite.toString()));
    }
    private static JsonObject node(String type) { JsonObject o = new JsonObject(); o.addProperty("type", type); return o; }
    private static JsonObject text(String value) { JsonObject o = node("text"); o.addProperty("text", value); return o; }
    private static JsonObject column(JsonObject... children) {
        JsonObject o = node("column");
        JsonArray array = new JsonArray();
        for (JsonObject child : children) array.add(child);
        o.add("children", array);
        return o;
    }
    private static JsonObject button(String label, String action) { JsonObject o = node("button"); o.addProperty("label", label); o.addProperty("action", action); return o; }
    private static JsonObject input(String label, String action, String value) {
        JsonObject o = node("input"); o.addProperty("label", label); o.addProperty("action", action); o.addProperty("value", value); return o;
    }
    private static JsonObject choices(String selectedAction, String... labelsAndActions) {
        JsonObject o = node("choices");
        JsonArray options = new JsonArray();
        for (int i = 0; i < labelsAndActions.length; i += 2) {
            JsonObject option = new JsonObject();
            option.addProperty("label", labelsAndActions[i]); option.addProperty("action", labelsAndActions[i + 1]);
            option.addProperty("selected", labelsAndActions[i + 1].equals(selectedAction));
            options.add(option);
        }
        o.add("options", options);
        return o;
    }
    private static AppTestSuite.TestCase only(String json) { return AppTestSuite.parse(json).tests.get(0); }

    @Test public void validSuiteParsesAndRoundTrips() {
        AppTestSuite suite = AppTestSuite.parse(suite(test("Tip for 100 at 15%"), test("Second")).toString());
        assertEquals(2, suite.tests.size());
        AppTestSuite.TestCase first = suite.tests.get(0);
        assertEquals("Tip for 100 at 15%", first.name);
        assertEquals("input", first.steps.get(0).type); assertEquals("100", first.steps.get(0).value);
        assertEquals("action", first.steps.get(1).type); assertNull(first.steps.get(1).value);
        assertEquals(List.of("Tip: 15.00", "Total: 115.00"), first.expect); assertEquals(List.of("NaN"), first.expectAbsent);
        assertEquals(suite.toJson(), AppTestSuite.parse(suite.toJson()).toJson());
        // Steps become the host's own event shape.
        assertEquals("{\"type\":\"input\",\"action\":\"bill\",\"value\":\"100\"}", first.steps.get(0).toEvent().toString());
        assertEquals("{\"type\":\"action\",\"action\":\"tip-15\"}", first.steps.get(1).toEvent().toString());
    }

    @Test public void optionalListsAndBoundariesAreAccepted() {
        AppTestSuite.TestCase minimal = only("[{\"name\":\"Init\",\"expect\":[\"Ready\"]}]");
        assertTrue(minimal.steps.isEmpty()); assertTrue(minimal.expectAbsent.isEmpty());
        assertTrue(AppTestSuite.parse("[]").isEmpty());
        assertSame(AppTestSuite.EMPTY, AppTestSuite.parse(" [ ] "));
        JsonObject full = test("n".repeat(80));
        full.add("steps", repeat(16, step("input", "a".repeat(80), "v".repeat(2000))));
        full.add("expect", repeat(8, new com.google.gson.JsonPrimitive("e".repeat(120))));
        full.add("expectAbsent", repeat(4, new com.google.gson.JsonPrimitive("x".repeat(120))));
        full.getAsJsonArray("steps").set(0, step("input", "empty", ""));
        JsonArray ten = new JsonArray();
        for (int i = 0; i < 10; i++) { JsonObject copy = full.deepCopy(); copy.addProperty("name", "Test " + i); ten.add(copy); }
        AppTestSuite parsed = AppTestSuite.parse(ten.toString());
        assertEquals(10, parsed.tests.size()); assertEquals(16, parsed.tests.get(9).steps.size());
        assertEquals("", parsed.tests.get(0).steps.get(0).value);
        assertEquals(parsed.toJson(), AppTestSuite.parse(parsed.toJson()).toJson());
        // Control characters in a name become spaces; the stored form stays stable.
        assertEquals("Line one two", only("[{\"name\":\" Line one\\ntwo \",\"expect\":[\"x\"]}]").name);
    }

    @Test public void outOfBoundsUnknownAndMalformedSuitesAreRejectedAsAWhole() {
        rejected(s -> s.get(1).getAsJsonObject().addProperty("name", "Tip for 100 at 15%")); // duplicate name
        rejected(s -> { for (int i = 0; i < 9; i++) s.add(test("Extra " + i)); });           // 11 tests
        rejected(s -> s.get(0).getAsJsonObject().addProperty("name", "n".repeat(81)));
        rejected(s -> s.get(0).getAsJsonObject().addProperty("name", "   "));
        rejected(s -> s.get(0).getAsJsonObject().remove("name"));
        rejected(s -> s.get(0).getAsJsonObject().remove("expect"));
        rejected(s -> s.get(0).getAsJsonObject().add("expect", new JsonArray()));
        rejected(s -> s.get(0).getAsJsonObject().add("expect", repeat(9, new com.google.gson.JsonPrimitive("x"))));
        rejected(s -> s.get(0).getAsJsonObject().add("expect", strings("e".repeat(121))));
        rejected(s -> s.get(0).getAsJsonObject().add("expect", strings(" \n ")));
        rejected(s -> s.get(0).getAsJsonObject().add("expectAbsent", repeat(5, new com.google.gson.JsonPrimitive("x"))));
        rejected(s -> s.get(0).getAsJsonObject().add("steps", repeat(17, step("action", "go", null))));
        rejected(s -> s.get(0).getAsJsonObject().addProperty("timeoutMs", 5));
        rejected(s -> s.get(0).getAsJsonObject().getAsJsonArray("steps").get(0).getAsJsonObject().addProperty("delay", 1));
        rejected(s -> s.get(0).getAsJsonObject().getAsJsonArray("steps").set(0, step("input", "bill", null)));
        rejected(s -> s.get(0).getAsJsonObject().getAsJsonArray("steps").set(0, step("action", "go", "value")));
        rejected(s -> s.get(0).getAsJsonObject().getAsJsonArray("steps").set(0, step("pitch", "go", null)));
        rejected(s -> s.get(0).getAsJsonObject().getAsJsonArray("steps").set(0, step("action", "", null)));
        rejected(s -> s.get(0).getAsJsonObject().getAsJsonArray("steps").set(0, step("action", "a".repeat(81), null)));
        rejected(s -> s.get(0).getAsJsonObject().getAsJsonArray("steps").set(0, step("input", "bill", "v".repeat(2001))));
        rejected(s -> s.get(0).getAsJsonObject().addProperty("expect", "Tip"));
        // The unchanged fixture is valid, so every rejection above comes from its one change.
        JsonArray three = suite(test("A"), test("B"), test("C"));
        assertEquals(3, AppTestSuite.parse(three.toString()).tests.size());
        for (String raw : new String[]{"{}", "[1]", "[{\"name\":\"a\",\"expect\":[\"x\"]}] []", "[{\"name\":\"a\",\"name\":\"b\",\"expect\":[\"x\"]}]",
                "[{name:'a',expect:['x']}]", "[{\"name\":\"a\",\"expect\":[\"x\"],}]", null}) {
            assertThrows(IllegalArgumentException.class, () -> AppTestSuite.parse(raw));
        }
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> AppTestSuite.parse("[{\"name\":\"secret-token\"}]"));
        assertFalse(error.getMessage().contains("secret"));
    }

    @Test public void generationOutputIsSplitIntoPackageAndSuite() {
        JsonObject output = new JsonObject();
        output.addProperty("name", "Tips"); output.addProperty("description", "Tip calculator");
        output.add("capabilities", new JsonArray()); output.addProperty("source", "function dispatch(e){return '{}';}");
        output.add("tests", suite(test("Tip for 100 at 15%")));
        AppTestSuite.Extracted extracted = AppTestSuite.extract(output.toString());
        assertFalse(extracted.rejected);
        assertEquals(1, extracted.tests.tests.size());
        assertFalse(JsonParser.parseString(extracted.packageJson).getAsJsonObject().has("tests"));
        assertEquals("Tips", ProjectBundle.parse(extracted.packageJson).name);

        output.remove("tests");
        AppTestSuite.Extracted absent = AppTestSuite.extract(output.toString());
        assertSame(AppTestSuite.EMPTY, absent.tests); assertFalse(absent.rejected);
        assertEquals(output.toString(), absent.packageJson);

        output.addProperty("tests", "run everything");
        AppTestSuite.Extracted invalid = AppTestSuite.extract(output.toString());
        assertSame(AppTestSuite.EMPTY, invalid.tests); assertTrue(invalid.rejected);
        assertEquals("Tips", ProjectBundle.parse(invalid.packageJson).name);

        // Text that is not a package is left for ProjectBundle to reject as before.
        assertEquals("not json", AppTestSuite.extract("not json").packageJson);
        assertEquals("[1]", AppTestSuite.extract("[1]").packageJson);
        assertThrows(IllegalArgumentException.class, () -> ProjectBundle.parse(AppTestSuite.extract("{".repeat(30)).packageJson));
    }

    @Test public void visibleTextCoversEveryNodeType() {
        JsonObject meter = node("meter"); meter.addProperty("value", -12); meter.addProperty("active", true);
        JsonObject nearZero = node("meter"); nearZero.addProperty("value", -0.04);
        JsonObject image = node("image"); image.addProperty("asset", "3f1c0f59-3c39-4a6f-9f6c-2f0b9a6e6f10.jpg"); image.addProperty("description", "Receipt photo");
        JsonObject graphic = node("graphic"); graphic.addProperty("asset", "chart"); graphic.addProperty("description", "Bar chart of tips");
        JsonObject blank = text("   ");
        JsonObject unlabeled = input("", "note", "hello");
        JsonObject ui = column(text("Tip calculator"), input("Bill amount", "bill", "100"), unlabeled,
                choices("tip-15", "10%", "tip-10", "15%", "tip-15"), button("Reset", "reset"), meter, nearZero, image,
                column(graphic, blank));
        UiPacket.parse("{\"ui\":" + ui + "}"); // The fixture is a valid UI tree.
        assertEquals(List.of("Tip calculator", "Bill amount: 100", "hello", "10%", "[selected] 15%", "Reset",
                "meter -12.0", "meter 0.0", "Receipt photo", "Bar chart of tips"), AppTestSuite.visibleLines(ui));
        assertEquals(String.join("\n", AppTestSuite.visibleLines(ui)), AppTestSuite.visibleText(ui));
    }

    @Test public void expectationsUseNormalizedCaseSensitiveSubstrings() {
        JsonObject ui = column(text("Tip:  15.00"), text("Total:\n115.00"), text("Each"), text("38.33"));
        AppTestSuite.TestCase passing = only("[{\"name\":\"a\",\"expect\":[\"Tip: 15.00\",\" Total:  115.00 \",\"Each 38.33\"],\"expectAbsent\":[\"NaN\"]}]");
        AppTestSuite.Evaluation pass = AppTestSuite.evaluate(passing, ui);
        assertTrue(pass.passed); assertTrue(pass.missing.isEmpty()); assertTrue(pass.unexpected.isEmpty());
        AppTestSuite.TestCase failing = only("[{\"name\":\"a\",\"expect\":[\"tip: 15.00\",\"Total: 115.00\"],\"expectAbsent\":[\"Each\",\"NaN\"]}]");
        AppTestSuite.Evaluation fail = AppTestSuite.evaluate(failing, ui);
        assertFalse(fail.passed);
        assertEquals(List.of("tip: 15.00"), fail.missing); assertEquals(List.of("Each"), fail.unexpected);
        assertEquals("Missing: \"tip: 15.00\". Unexpected: \"Each\". Visible: Tip: 15.00 | Total: 115.00 | Each | 38.33", fail.detail());
    }

    @Test public void failureDetailsStayWithinBounds() {
        JsonArray children = new JsonArray();
        for (int i = 0; i < 50; i++) children.add(text("Line " + i + " " + "x".repeat(400)));
        JsonObject ui = node("column"); ui.add("children", children);
        JsonObject test = test("Long");
        test.add("expect", repeat(8, new com.google.gson.JsonPrimitive("m".repeat(120))));
        test.add("expectAbsent", strings("Line 1"));
        AppTestSuite.Evaluation result = AppTestSuite.evaluate(AppTestSuite.parse(suite(test).toString()).tests.get(0), ui);
        assertFalse(result.passed);
        assertTrue(result.detail().length() <= ChecksReport.MAX_DETAIL);
        assertTrue(result.detail().startsWith("Missing: \"mmm"));
        assertTrue(result.detail().endsWith("…"));
    }

    @Test public void runSendsStepsInOrderAndRequiresTheirControlsOnScreen() {
        AppTestSuite.TestCase test = AppTestSuite.parse(suite(test("Tip for 100 at 15%")).toString()).tests.get(0);
        JsonObject init = AppTestSuite.Run.initEvent(JsonParser.parseString("{\"screenWidthDp\":412}").getAsJsonObject());
        assertEquals("{\"type\":\"init\",\"device\":{\"screenWidthDp\":412},\"settings\":{},\"storage\":{}}", init.toString());
        JsonObject form = column(input("Bill", "bill", ""), choices("tip-10", "10%", "tip-10", "15%", "tip-15"));
        AppTestSuite.Run run = new AppTestSuite.Run(test);
        assertEquals("bill", run.next(form).get("action").getAsString());
        assertEquals("tip-15", run.next(form).get("action").getAsString());
        assertFalse(run.isFinished());
        assertNull(run.next(column(form, text("Tip: 15.00"), text("Total: 115.00"))));
        assertTrue(run.entry().passed); assertEquals("", run.entry().detail);
        assertThrows(IllegalStateException.class, () -> run.next(form));

        // An input step cannot target a button, and a missing control fails without sending anything.
        AppTestSuite.Run blocked = new AppTestSuite.Run(test);
        assertNull(blocked.next(column(button("Bill", "bill"))));
        assertFalse(blocked.entry().passed);
        assertEquals("Cannot perform step 1 (input \"bill\"): no input with this action is on screen. Visible: Bill", blocked.entry().detail);
        AppTestSuite.Run noChoice = new AppTestSuite.Run(test);
        noChoice.next(form);
        assertNull(noChoice.next(column(input("Bill", "bill", "100"))));
        assertTrue(noChoice.entry().detail.startsWith("Cannot perform step 2 (action \"tip-15\"): no button or choice"));
    }

    @Test public void runtimeFailuresNameTheirStepAndAreBounded() {
        AppTestSuite.TestCase test = AppTestSuite.parse(suite(test("Tip for 100 at 15%")).toString()).tests.get(0);
        AppTestSuite.Run duringInit = new AppTestSuite.Run(test);
        assertThrows(IllegalStateException.class, duringInit::entry);
        duringInit.fail("Runtime did not respond.");
        assertEquals("Runtime failed during init: Runtime did not respond.", duringInit.entry().detail);
        duringInit.fail("ignored once finished");
        assertEquals("Runtime failed during init: Runtime did not respond.", duringInit.entry().detail);

        AppTestSuite.Run duringStep = new AppTestSuite.Run(test);
        duringStep.next(column(input("Bill", "bill", "")));
        duringStep.fail("TypeError: " + "x".repeat(1000));
        assertTrue(duringStep.entry().detail.startsWith("Runtime failed during step 1 (input \"bill\"): TypeError: xxx"));
        assertEquals(ChecksReport.MAX_DETAIL, duringStep.entry().detail.length());
    }
}

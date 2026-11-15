package app.pub.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * The sample app a new install starts with (app/src/main/assets/samples/tip-calculator.json), run the way the host
 * runs behavior checks: a fresh sandbox per check, init, steps as events, every packet validated, effects ignored.
 */
public class SampleTipCalculatorTest {
    private static final Path SAMPLE = Path.of("app/src/main/assets/samples/tip-calculator.json");
    private static final JsonObject DEVICE = JsonParser.parseString("{\"screenWidthDp\":412,\"fontScale\":1.0}").getAsJsonObject();
    private static final String SUITE = "[" +
        "{\"name\":\"Defaults\",\"expect\":[\"Bill amount ($): 100\",\"Tip percent (%): 20\",\"People: 2\",\"Tip: $20.00\",\"Total: $120.00\",\"$60.00 per person\"],\"expectAbsent\":[\"Check your inputs\",\"NaN\"]}," +
        "{\"name\":\"Bill 50, tip 15%, 3 people\",\"steps\":[{\"type\":\"input\",\"action\":\"bill\",\"value\":\"50\"},{\"type\":\"input\",\"action\":\"tip\",\"value\":\"15\"},{\"type\":\"input\",\"action\":\"people\",\"value\":\"3\"}],\"expect\":[\"Tip: $7.50\",\"Total: $57.50\",\"$19.17 per person\"],\"expectAbsent\":[\"NaN\"]}," +
        "{\"name\":\"Invalid bill\",\"steps\":[{\"type\":\"input\",\"action\":\"bill\",\"value\":\"abc\"}],\"expect\":[\"Check your inputs\"],\"expectAbsent\":[\"NaN\",\"Infinity\",\"undefined\",\"Total:\"]}," +
        "{\"name\":\"Reset restores defaults\",\"steps\":[{\"type\":\"input\",\"action\":\"bill\",\"value\":\"abc\"},{\"type\":\"input\",\"action\":\"people\",\"value\":\"5\"},{\"type\":\"action\",\"action\":\"reset\"}],\"expect\":[\"Bill amount ($): 100\",\"Tip percent (%): 20\",\"People: 2\",\"Tip: $20.00\",\"Total: $120.00\",\"$60.00 per person\"],\"expectAbsent\":[\"Check your inputs\"]}" +
        "]";

    private static ProjectBundle sample() throws Exception { return ProjectBundle.parse(Files.readString(SAMPLE)); }

    /** One check in a fresh engine, starting from [init]. */
    private static ChecksReport.Entry check(String source, AppTestSuite.TestCase test, JsonObject init) {
        ScriptEngine engine = new ScriptEngine();
        AppTestSuite.Run run = new AppTestSuite.Run(test);
        try {
            engine.load(source);
            JsonObject event = init;
            while (event != null) event = run.next(UiPacket.parse(engine.dispatch(event.toString())).ui);
        } catch (RuntimeException | ScriptEngine.BudgetExceeded error) {
            run.fail(error.getMessage());
        }
        return run.entry();
    }

    @Test public void samplePackageIsAnOrdinaryStorageOnlyApp() throws Exception {
        ProjectBundle bundle = sample();
        assertEquals("Tip Calculator", bundle.name);
        assertEquals(Set.of("storage.project"), bundle.capabilities);
        assertFalse(bundle.description.isBlank());
        bundle.requireReadyAssets();
    }

    @Test public void behaviorChecksPassFromAFreshStart() throws Exception {
        String source = sample().source;
        List<ChecksReport.Entry> results = new ArrayList<>();
        for (AppTestSuite.TestCase test : AppTestSuite.parse(SUITE).tests) results.add(check(source, test, AppTestSuite.Run.initEvent(DEVICE)));
        ChecksReport report = ChecksReport.of(results);
        assertEquals(report.toJson(), 4, report.passed);
        assertTrue(report.toJson(), report.allPassed());
    }

    @Test public void storedInputsAreRestoredAtInit() throws Exception {
        String source = sample().source;
        JsonObject init = AppTestSuite.Run.initEvent(DEVICE);
        init.add("storage", JsonParser.parseString("{\"bill\":\"80\",\"tip\":\"10\",\"people\":\"4\"}"));
        AppTestSuite.TestCase restored = AppTestSuite.parse("[{\"name\":\"Restored\",\"expect\":[\"Bill amount ($): 80\",\"Tip percent (%): 10\",\"People: 4\",\"Tip: $8.00\",\"Total: $88.00\",\"$22.00 per person\"]}]").tests.get(0);
        ChecksReport.Entry entry = check(source, restored, init);
        assertTrue(entry.detail, entry.passed);
    }

    @Test public void changesAreSavedThroughStorageAndRoundTrip() throws Exception {
        String source = sample().source;
        ScriptEngine engine = new ScriptEngine();
        engine.load(source);
        assertEquals("Init saves nothing", 0, UiPacket.parse(engine.dispatch(AppTestSuite.Run.initEvent(DEVICE).toString())).effects.size());
        UiPacket packet = UiPacket.parse(engine.dispatch("{\"type\":\"input\",\"action\":\"bill\",\"value\":\"80\"}"));
        assertEquals(1, packet.effects.size());
        JsonObject effect = packet.effects.get(0).getAsJsonObject();
        assertEquals("storage.save", effect.get("type").getAsString());
        assertEquals(JsonParser.parseString("{\"bill\":\"80\",\"tip\":\"20\",\"people\":\"2\"}"), effect.get("data"));

        JsonObject init = AppTestSuite.Run.initEvent(DEVICE);
        init.add("storage", effect.get("data"));
        AppTestSuite.TestCase reopened = AppTestSuite.parse("[{\"name\":\"Reopened\",\"expect\":[\"Bill amount ($): 80\",\"Tip: $16.00\",\"Total: $96.00\",\"$48.00 per person\"]}]").tests.get(0);
        ChecksReport.Entry entry = check(source, reopened, init);
        assertTrue(entry.detail, entry.passed);
    }
}

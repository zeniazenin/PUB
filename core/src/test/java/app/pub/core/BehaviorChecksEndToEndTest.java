package app.pub.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * A realistic generated mini-app and its model-written suite, run the way the host runs behavior checks: a fresh
 * sandbox per test, init with empty storage/settings, steps as events, effects ignored, every packet validated.
 */
public class BehaviorChecksEndToEndTest {
    private static final String TIP_CALCULATOR = String.join("\n",
        "var bill = '', tip = 15, people = '1';",
        "function money(n) { return n.toFixed(2); }",
        "function view() {",
        "  var children = [{type:'text', text:'Tip calculator', style:'display'},",
        "    {type:'input', label:'Bill amount', action:'bill', value:bill},",
        "    {type:'choices', options:[10, 15, 18, 20].map(function (p) { return {label:p + '%', action:'tip-' + p, selected:p === tip}; })},",
        "    {type:'input', label:'People', action:'people', value:people}];",
        "  var b = Number(bill), n = Number(people);",
        "  if (bill.trim() === '') children.push({type:'text', text:'Enter the bill amount.'});",
        "  else if (!isFinite(b) || b < 0) children.push({type:'text', text:'Bill must be a positive number.'});",
        "  else if (!isFinite(n) || n < 1 || Math.floor(n) !== n) children.push({type:'text', text:'People must be a whole number of at least 1.'});",
        "  else {",
        "    var t = TIP_FORMULA;",
        "    children.push({type:'text', text:'Tip: ' + money(t)});",
        "    children.push({type:'text', text:'Total: ' + money(b + t)});",
        "    children.push({type:'text', text:'Each: ' + money((b + t) / n)});",
        "  }",
        "  children.push({type:'button', label:'Reset', action:'reset'});",
        "  return children;",
        "}",
        "function dispatch(raw) {",
        "  var e = JSON.parse(raw);",
        "  if (e.type === 'init') { bill = (e.storage && e.storage.bill) || ''; }",
        "  else if (e.type === 'input' && e.action === 'bill') bill = String(e.value);",
        "  else if (e.type === 'input' && e.action === 'people') people = String(e.value);",
        "  else if (e.type === 'action' && e.action.indexOf('tip-') === 0) tip = Number(e.action.slice(4));",
        "  else if (e.type === 'action' && e.action === 'reset') { RESET }",
        "  var effects = e.type === 'init' ? [] : [{type:'storage.save', data:{bill:bill, tip:tip}}];",
        "  return JSON.stringify({ui:{type:'column', children:view()}, effects:effects});",
        "}");
    private static final String CORRECT = TIP_CALCULATOR.replace("TIP_FORMULA", "b * tip / 100").replace("RESET", "bill = ''; tip = 15; people = '1';");

    /** What the builder model returns beside the package, implementing the brief's acceptance checks. */
    private static final String SUITE = "[" +
        "{\"name\":\"Bill 100, tip 15%\",\"steps\":[{\"type\":\"input\",\"action\":\"bill\",\"value\":\"100\"},{\"type\":\"action\",\"action\":\"tip-15\"}],\"expect\":[\"Tip: 15.00\",\"Total: 115.00\",\"[selected] 15%\"],\"expectAbsent\":[\"NaN\"]}," +
        "{\"name\":\"Bill 100, 3 people, 15%\",\"steps\":[{\"type\":\"input\",\"action\":\"bill\",\"value\":\"100\"},{\"type\":\"input\",\"action\":\"people\",\"value\":\"3\"}],\"expect\":[\"Each: 38.33\"]}," +
        "{\"name\":\"Empty bill\",\"steps\":[],\"expect\":[\"Enter the bill amount.\"],\"expectAbsent\":[\"NaN\",\"Total:\"]}," +
        "{\"name\":\"Invalid bill\",\"steps\":[{\"type\":\"input\",\"action\":\"bill\",\"value\":\"abc\"}],\"expect\":[\"Bill must be a positive number.\"],\"expectAbsent\":[\"NaN\",\"Infinity\",\"undefined\"]}," +
        "{\"name\":\"Reset restores defaults\",\"steps\":[{\"type\":\"input\",\"action\":\"bill\",\"value\":\"80\"},{\"type\":\"action\",\"action\":\"tip-20\"},{\"type\":\"action\",\"action\":\"reset\"}],\"expect\":[\"Enter the bill amount.\",\"[selected] 15%\",\"Bill amount: \"],\"expectAbsent\":[\"Total:\"]}" +
        "]";
    private static final JsonObject DEVICE = JsonParser.parseString("{\"screenWidthDp\":412,\"fontScale\":1.0}").getAsJsonObject();

    /** The host's runner loop without Android: one fresh engine per test; effects are never executed. */
    private static ChecksReport run(String source, AppTestSuite suite) {
        List<ChecksReport.Entry> results = new ArrayList<>();
        for (AppTestSuite.TestCase test : suite.tests) {
            ScriptEngine engine = new ScriptEngine();
            AppTestSuite.Run run = new AppTestSuite.Run(test);
            try {
                engine.load(source);
                JsonObject event = AppTestSuite.Run.initEvent(DEVICE);
                while (event != null) event = run.next(UiPacket.parse(engine.dispatch(event.toString())).ui);
            } catch (RuntimeException | ScriptEngine.BudgetExceeded error) {
                run.fail(error.getMessage());
            }
            results.add(run.entry());
        }
        return ChecksReport.of(results);
    }

    @Test public void correctAppPassesItsSuite() {
        AppTestSuite suite = AppTestSuite.extract("{\"name\":\"Tips\",\"description\":\"Tip calculator\",\"capabilities\":[\"storage.project\"],\"source\":"
                + new com.google.gson.JsonPrimitive(CORRECT) + ",\"tests\":" + SUITE + "}").tests;
        assertEquals(5, suite.tests.size());
        ChecksReport report = run(CORRECT, suite);
        assertTrue(report.toJson(), report.allPassed());
        assertEquals(5, report.passed);
        assertEquals(report.toJson(), ChecksReport.parse(report.toJson()).toJson());
    }

    @Test public void wrongFormulaFailsWithTheObservedValues() {
        ChecksReport report = run(TIP_CALCULATOR.replace("TIP_FORMULA", "b * tip / 1000").replace("RESET", "bill = ''; tip = 15; people = '1';"), AppTestSuite.parse(SUITE));
        assertEquals(3, report.passed);
        ChecksReport.Entry first = report.results.get(0);
        assertFalse(first.passed);
        assertTrue(first.detail, first.detail.startsWith("Missing: \"Tip: 15.00\", \"Total: 115.00\". Visible: Tip calculator | Bill amount: 100 | 10% | [selected] 15%"));
        assertTrue(first.detail.contains("Tip: 1.50 | Total: 101.50"));
        assertFalse(report.results.get(1).passed);
        assertTrue(report.results.get(2).passed); assertTrue(report.results.get(3).passed); assertTrue(report.results.get(4).passed);
    }

    @Test public void missingValidationAndBrokenResetAreCaught() {
        String unvalidated = CORRECT.replace("else if (!isFinite(b) || b < 0) children.push({type:'text', text:'Bill must be a positive number.'});", "");
        ChecksReport report = run(unvalidated, AppTestSuite.parse(SUITE));
        ChecksReport.Entry invalid = report.results.get(3);
        assertFalse(invalid.passed);
        assertTrue(invalid.detail, invalid.detail.startsWith("Missing: \"Bill must be a positive number.\". Unexpected: \"NaN\"."));

        // A script error fails only the check that triggers it, and names the step.
        String throwing = TIP_CALCULATOR.replace("TIP_FORMULA", "b * tip / 100").replace("RESET", "missing.reset();");
        ChecksReport broken = run(throwing, AppTestSuite.parse(SUITE));
        assertEquals(4, broken.passed);
        ChecksReport.Entry reset = broken.results.get(4);
        assertTrue(reset.detail, reset.detail.startsWith("Runtime failed during step 3 (action \"reset\"): "));
        assertTrue(reset.detail.contains("missing"));
    }

    @Test public void stepsOnlyReachControlsAUserCouldTap() {
        // An app without a Reset button cannot pass a check that taps it, even if dispatch would handle it.
        String noButton = CORRECT.replace("children.push({type:'button', label:'Reset', action:'reset'});", "");
        ChecksReport report = run(noButton, AppTestSuite.parse(SUITE));
        assertEquals(4, report.passed);
        assertEquals("Cannot perform step 3 (action \"reset\"): no button or choice with this action is on screen.",
                report.results.get(4).detail.substring(0, report.results.get(4).detail.indexOf(" Visible:")));
        // Saved data is never supplied: the app always starts from its own defaults.
        assertTrue(run(CORRECT, AppTestSuite.parse("[{\"name\":\"Fresh start\",\"expect\":[\"Bill amount: \",\"Enter the bill amount.\"]}]")).allPassed());
    }

    @Test(timeout = 5000) public void runawayScriptFailsItsCheckWithinTheBudget() {
        String loop = CORRECT.replace("bill = ''; tip = 15; people = '1';", "while (true) {}");
        ChecksReport report = run(loop, AppTestSuite.parse(SUITE));
        assertEquals(4, report.passed);
        assertTrue(report.results.get(4).detail.startsWith("Runtime failed during step 3 (action \"reset\"): Script execution budget exceeded"));
    }
}

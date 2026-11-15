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

    /** A journal that keeps one file per entry: it lists at init, and after each save lists and reads every entry again. */
    private static final String JOURNAL = String.join("\n",
        "var titles = [], waiting = [], status = 'Loading entries...', draft = '', serial = 0;",
        "function req(op, args) { return {type:'files.request', id:'r' + (++serial), operation:op, args:args}; }",
        "function view() {",
        "  var c = [{type:'text', text:'Journal', style:'display'}, {type:'text', text:status}];",
        "  titles.forEach(function (t) { c.push({type:'text', text:'Entry: ' + t}); });",
        "  c.push({type:'input', label:'Title', action:'title', value:draft});",
        "  c.push({type:'button', label:'Save', action:'save'});",
        "  return c;",
        "}",
        "function dispatch(raw) {",
        "  var e = JSON.parse(raw), fx = [];",
        "  if (e.type === 'init') fx.push(req('list', {path:'entries'}));",
        "  else if (e.type === 'input' && e.action === 'title') draft = String(e.value);",
        "  else if (e.type === 'action' && e.action === 'save' && draft.trim()) {",
        "    status = 'Saving...';",
        "    fx.push(req('write', {path:'entries/entry-' + (titles.length + 1) + '.txt', data:draft, encoding:'utf8'}));",
        "  } else if (e.type === 'files') {",
        "    if (!e.ok) status = e.code === 'not_found' ? 'No entries yet' : 'Error: ' + e.message;",
        "    else if (e.operation === 'write') { draft = ''; fx.push(req('list', {path:'entries'})); }",
        "    else if (e.operation === 'list') {",
        "      titles = []; waiting = e.entries.map(function (x) { return x.path; });",
        "      status = waiting.length ? 'Loading entries...' : 'No entries yet';",
        "      if (waiting.length) fx.push(req('read', {path:waiting.shift(), encoding:'utf8'}));",
        "    } else if (e.operation === 'read') {",
        "      titles.push(e.data);",
        "      if (waiting.length) fx.push(req('read', {path:waiting.shift(), encoding:'utf8'}));",
        "      else status = titles.length + ' saved';",
        "    }",
        "  }",
        "  return JSON.stringify({ui:{type:'column', children:view()}, effects:fx});",
        "}");
    private static final String JOURNAL_SUITE = "[" +
        "{\"name\":\"Starts empty\",\"steps\":[],\"expect\":[\"No entries yet\"],\"expectAbsent\":[\"Loading\"]}," +
        "{\"name\":\"Saved entry is listed\",\"steps\":[{\"type\":\"input\",\"action\":\"title\",\"value\":\"Groceries\"},{\"type\":\"action\",\"action\":\"save\"}]," +
        "\"expect\":[\"Entry: Groceries\",\"1 saved\",\"Title: \"],\"expectAbsent\":[\"Saving\"]}," +
        "{\"name\":\"Two entries\",\"steps\":[{\"type\":\"input\",\"action\":\"title\",\"value\":\"First\"},{\"type\":\"action\",\"action\":\"save\"}," +
        "{\"type\":\"input\",\"action\":\"title\",\"value\":\"Second\"},{\"type\":\"action\",\"action\":\"save\"}],\"expect\":[\"Entry: First\",\"Entry: Second\",\"2 saved\"]}" +
        "]";

    /** The host's loop with file replies: each packet goes to Run.next(UiPacket), which answers file requests first. */
    private static ChecksReport runWithFiles(String source, AppTestSuite suite) {
        List<ChecksReport.Entry> results = new ArrayList<>();
        for (AppTestSuite.TestCase test : suite.tests) {
            ScriptEngine engine = new ScriptEngine();
            AppTestSuite.Run run = new AppTestSuite.Run(test);
            try {
                engine.load(source);
                JsonObject event = AppTestSuite.Run.initEvent(DEVICE);
                while (event != null) event = run.next(UiPacket.parse(engine.dispatch(event.toString())));
            } catch (RuntimeException | ScriptEngine.BudgetExceeded error) {
                run.fail(error.getMessage());
            }
            results.add(run.entry());
        }
        return ChecksReport.of(results);
    }

    @Test public void fileBasedAppsAreCheckedAgainstAnEmptySimulatedFolder() {
        ChecksReport report = runWithFiles(JOURNAL, AppTestSuite.parse(JOURNAL_SUITE));
        assertTrue(report.toJson(), report.allPassed());
        // Without file replies the app never leaves its loading state, which is what checks saw before.
        ChecksReport ignored = run(JOURNAL, AppTestSuite.parse(JOURNAL_SUITE));
        assertEquals(0, ignored.passed);
        assertTrue(ignored.results.get(0).detail, ignored.results.get(0).detail.contains("Loading entries..."));
        // Every test starts from an empty folder: entries saved by one test are never seen by the next.
        ChecksReport fresh = runWithFiles(JOURNAL, AppTestSuite.parse("[" +
            "{\"name\":\"Save\",\"steps\":[{\"type\":\"input\",\"action\":\"title\",\"value\":\"Kept\"},{\"type\":\"action\",\"action\":\"save\"}],\"expect\":[\"1 saved\"]}," +
            "{\"name\":\"Fresh\",\"steps\":[],\"expect\":[\"No entries yet\"],\"expectAbsent\":[\"Kept\"]}]"));
        assertTrue(fresh.toJson(), fresh.allPassed());
    }

    @Test public void theVisualReviewSeesTheEmptyStateAfterInitsReplies() throws Exception {
        // VisualPreviewSession's loop: answer each packet's file requests until none waits, then capture.
        ScriptEngine engine = new ScriptEngine();
        engine.load(JOURNAL);
        SimulatedFiles files = new SimulatedFiles();
        UiPacket packet = UiPacket.parse(engine.dispatch(AppTestSuite.Run.initEvent(DEVICE).toString()));
        while (true) {
            files.accept(packet.effects);
            JsonObject reply = files.next();
            if (reply == null) break;
            packet = UiPacket.parse(engine.dispatch(reply.toString()));
        }
        assertTrue(AppTestSuite.visibleText(packet.ui).contains("No entries yet"));
    }

    @Test(timeout = 5000) public void runawayScriptFailsItsCheckWithinTheBudget() {
        String loop = CORRECT.replace("bill = ''; tip = 15; people = '1';", "while (true) {}");
        ChecksReport report = run(loop, AppTestSuite.parse(SUITE));
        assertEquals(4, report.passed);
        assertTrue(report.results.get(4).detail.startsWith("Runtime failed during step 3 (action \"reset\"): Script execution budget exceeded"));
    }
}

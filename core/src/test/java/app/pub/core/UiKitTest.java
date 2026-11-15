package app.pub.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Cards, rows, list items and button emphasis: validated by UiPacket, read and tapped by behavior checks. */
public class UiKitTest {
    private static UiPacket packet(String ui) { return UiPacket.parse("{\"ui\":" + ui + "}"); }
    private static String column(String... children) { return "{\"type\":\"column\",\"children\":[" + String.join(",", children) + "]}"; }
    private static final String TEXT = "{\"type\":\"text\",\"text\":\"Hello\"}";
    private static final String BUTTON = "{\"type\":\"button\",\"label\":\"Save\",\"action\":\"save\",\"style\":\"primary\"}";

    @Test public void newComponentsValidate() {
        packet(column(
            "{\"type\":\"card\",\"children\":[" + TEXT + "," + BUTTON + "]}",
            "{\"type\":\"card\",\"action\":\"open-1\",\"label\":\"Open entry\",\"children\":[" + TEXT + ",{\"type\":\"row\",\"children\":[" + TEXT + "," + TEXT + "]}]}",
            "{\"type\":\"row\",\"children\":[" + BUTTON.replace("primary", "text") + "," + BUTTON.replace("primary", "danger") + "]}",
            "{\"type\":\"item\",\"title\":\"Read\",\"supporting\":\"Streak 3 days\",\"trailing\":\"Done\",\"action\":\"toggle-read\",\"checked\":true}",
            "{\"type\":\"item\",\"title\":\"Settings\",\"selected\":true}",
            "{\"type\":\"text\",\"text\":\"Total\",\"style\":\"display\",\"align\":\"start\"}"));
        // Older apps keep working: unknown styles and alignments render as the default.
        packet(column("{\"type\":\"text\",\"text\":\"x\",\"style\":\"caption\",\"align\":\"right\"}", BUTTON.replace("primary", "outlined")));
    }

    @Test public void invalidUsesAreRejected() {
        List<String> invalid = new ArrayList<>(List.of(
            "{\"type\":\"card\",\"children\":[]}",
            "{\"type\":\"card\",\"action\":\"open\",\"children\":[" + BUTTON + "]}",
            "{\"type\":\"card\",\"action\":\"open\",\"children\":[{\"type\":\"row\",\"children\":[" + BUTTON + "]}]}",
            "{\"type\":\"card\",\"action\":\"open\",\"children\":[{\"type\":\"card\",\"children\":[" + TEXT + "]}]}",
            "{\"type\":\"row\",\"children\":[]}",
            "{\"type\":\"row\",\"children\":[{\"type\":\"input\",\"label\":\"x\",\"action\":\"x\",\"value\":\"\"}]}",
            "{\"type\":\"row\",\"children\":[" + String.join(",", java.util.Collections.nCopies(UiPacket.MAX_ROW_CHILDREN + 1, TEXT)) + "]}",
            "{\"type\":\"item\",\"title\":\" \"}",
            "{\"type\":\"item\",\"title\":\"" + "x".repeat(UiPacket.MAX_ITEM_TITLE + 1) + "\"}",
            "{\"type\":\"item\",\"title\":\"x\",\"trailing\":\"" + "x".repeat(UiPacket.MAX_ITEM_TRAILING + 1) + "\"}",
            "{\"type\":\"item\",\"title\":\"x\",\"checked\":true}",
            "{\"type\":\"item\",\"title\":\"x\",\"action\":\"x\",\"checked\":\"yes\"}",
            "{\"type\":\"button\",\"label\":\"x\",\"action\":\"x\",\"style\":5}"));
        for (String ui : invalid) assertThrows(ui, IllegalArgumentException.class, () -> packet(column(ui)));
    }

    /** A habit list: checkable items, a summary card that opens details, Back as a text button. */
    private static final String HABITS = String.join("\n",
        "var done = {}, screen = 'list', names = ['Read', 'Walk'];",
        "function list() {",
        "  var count = names.filter(function (n) { return done[n]; }).length;",
        "  var c = [{type:'text', text:'Habits', style:'subtitle'},",
        "    {type:'card', action:'summary', label:'Today summary', children:[{type:'text', text:count + ' of ' + names.length + ' done today'}]}];",
        "  names.forEach(function (n) { c.push({type:'item', title:n, supporting:'Daily', trailing:done[n] ? 'Done' : 'Pending', action:'toggle-' + n, checked:!!done[n]}); });",
        "  return c;",
        "}",
        "function details() {",
        "  return [{type:'button', label:'Back', action:'back', style:'text'}, {type:'text', text:'Details', style:'subtitle'},",
        "    {type:'row', children:[{type:'text', text:'Done'}, {type:'text', text:String(names.filter(function (n) { return done[n]; }).length)}]}];",
        "}",
        "function dispatch(raw) {",
        "  var e = JSON.parse(raw);",
        "  if (e.type === 'action' && e.action.indexOf('toggle-') === 0) { var n = e.action.slice(7); done[n] = !done[n]; }",
        "  else if (e.type === 'action' && e.action === 'summary') screen = 'details';",
        "  else if (e.type === 'action' && e.action === 'back') screen = 'list';",
        "  return JSON.stringify({ui:{type:'column', children:screen === 'list' ? list() : details()}, effects:[]});",
        "}");

    private static ChecksReport run(String suite) {
        List<ChecksReport.Entry> results = new ArrayList<>();
        for (AppTestSuite.TestCase test : AppTestSuite.parse(suite).tests) {
            ScriptEngine engine = new ScriptEngine();
            AppTestSuite.Run run = new AppTestSuite.Run(test);
            try {
                engine.load(HABITS);
                JsonObject event = AppTestSuite.Run.initEvent(new JsonObject());
                while (event != null) event = run.next(UiPacket.parse(engine.dispatch(event.toString())));
            } catch (RuntimeException | ScriptEngine.BudgetExceeded error) { run.fail(error.getMessage()); }
            results.add(run.entry());
        }
        return ChecksReport.of(results);
    }

    @Test public void checksTapItemsAndCardsAndReadThem() {
        ChecksReport report = run("[" +
            "{\"name\":\"Starts unchecked\",\"steps\":[],\"expect\":[\"[unchecked] Read · Daily · Pending\",\"0 of 2 done today\"]}," +
            "{\"name\":\"Check one\",\"steps\":[{\"type\":\"action\",\"action\":\"toggle-Read\"}],\"expect\":[\"[checked] Read · Daily · Done\",\"[unchecked] Walk\",\"1 of 2 done today\"]}," +
            "{\"name\":\"Card opens details\",\"steps\":[{\"type\":\"action\",\"action\":\"toggle-Walk\"},{\"type\":\"action\",\"action\":\"summary\"}],\"expect\":[\"Details\",\"Done\",\"1\"],\"expectAbsent\":[\"Habits\"]}," +
            "{\"name\":\"Back returns\",\"steps\":[{\"type\":\"action\",\"action\":\"summary\"},{\"type\":\"action\",\"action\":\"back\"}],\"expect\":[\"Habits\"]}]");
        assertTrue(report.toJson(), report.allPassed());
        // A step can only tap what is on screen: the card's action is gone on the details screen.
        ChecksReport missing = run("[{\"name\":\"No card\",\"steps\":[{\"type\":\"action\",\"action\":\"summary\"},{\"type\":\"action\",\"action\":\"summary\"}],\"expect\":[\"x\"]}]");
        assertTrue(missing.results.get(0).detail, missing.results.get(0).detail.startsWith("Cannot perform step 2"));
    }

    @Test public void visibleTextReadsCardsRowsAndItems() {
        JsonObject ui = JsonParser.parseString(column(
            "{\"type\":\"card\",\"children\":[" + TEXT + ",{\"type\":\"row\",\"children\":[{\"type\":\"text\",\"text\":\"A\"},{\"type\":\"text\",\"text\":\"B\"}]}]}",
            "{\"type\":\"item\",\"title\":\"Settings\",\"selected\":true}",
            "{\"type\":\"item\",\"title\":\"Read\",\"supporting\":\"Daily\",\"action\":\"r\",\"checked\":false}")).getAsJsonObject();
        assertEquals(List.of("Hello", "A", "B", "[selected] Settings", "[unchecked] Read · Daily"), AppTestSuite.visibleLines(ui));
    }
}

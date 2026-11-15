package app.pub.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.function.Consumer;
import org.junit.Test;
import static org.junit.Assert.*;

/** The UI planner's answer is untrusted data with a fixed, bounded shape. */
public class UiPlanTest {
    private static final String PLAN = "{\"layout\":\"Entries list opens first; New entry and Open lead to the editor and reader, each with Back at the top.\","
        + "\"screens\":[{\"name\":\"Entries\",\"purpose\":\"Find and open entries\",\"firstView\":\"Title, New entry button, the five newest entries (about 520 dp)\","
        + "\"elements\":[\"text display: 'Journal'\",\"button: 'New entry', primary\",\"text subtitle per entry: title, then body date\"],"
        + "\"states\":[\"empty: 'No entries yet' and the New entry button\",\"loading: 'Loading entries'\"]},"
        + "{\"name\":\"Editor\",\"purpose\":\"Write an entry\",\"firstView\":\"Back, title field, entry field, Save\","
        + "\"elements\":[\"button: 'Back to entries'\",\"input: 'Title'\",\"input multiline voice: 'Entry'\",\"button: 'Save entry'\"],\"states\":[]}],"
        + "\"interactions\":[\"After Save, return to the list with 'Entry saved.' at the top\"],\"content\":[\"Dates as 'Nov 15, 10:08 AM'\"],"
        + "\"accessibility\":[\"Every field has a visible label\"]}";

    private static void rejected(Consumer<JsonObject> change) {
        JsonObject root = JsonParser.parseString(PLAN).getAsJsonObject();
        change.accept(root);
        assertThrows(IllegalArgumentException.class, () -> UiPlan.parseModelOutput(root.toString()));
    }

    @Test public void parsesAndRoundTrips() {
        UiPlan plan = UiPlan.parseModelOutput(PLAN);
        assertEquals(2, plan.screens.size());
        assertEquals("Entries", plan.screens.get(0).name);
        assertEquals(4, plan.screens.get(1).elements.size());
        assertTrue(plan.screens.get(1).states.isEmpty());
        assertEquals(plan.toJson(), UiPlan.parse(plan.toJson()).toJson());
        assertEquals(plan.toJson(), UiPlan.parseModelOutput("```json\n" + PLAN + "\n```").toJson());
    }

    @Test public void unknownMissingBlankAndOversizeContentIsRejected() {
        rejected(o -> o.addProperty("code", "function dispatch(){}"));
        rejected(o -> o.remove("accessibility"));
        rejected(o -> o.addProperty("layout", "   "));
        rejected(o -> o.addProperty("layout", "x".repeat(UiPlan.MAX_LAYOUT + 1)));
        rejected(o -> o.add("screens", new JsonArray()));
        rejected(o -> o.getAsJsonArray("screens").get(0).getAsJsonObject().addProperty("color", "red"));
        rejected(o -> o.getAsJsonArray("screens").get(0).getAsJsonObject().add("elements", new JsonArray()));
        rejected(o -> o.getAsJsonArray("screens").get(1).getAsJsonObject().addProperty("name", "Entries"));
        rejected(o -> o.getAsJsonArray("interactions").add(5));
        rejected(o -> o.getAsJsonArray("content").add("x".repeat(UiPlan.MAX_ITEM + 1)));
        rejected(o -> {
            JsonArray screens = o.getAsJsonArray("screens");
            for (int i = 0; i < UiPlan.MAX_SCREENS; i++) {
                JsonObject copy = screens.get(0).getAsJsonObject().deepCopy();
                copy.addProperty("name", "Screen " + i);
                screens.add(copy);
            }
        });
        for (String text : new String[]{"", "null", "[]", "{", PLAN + "x", "```json\n" + PLAN}) {
            assertThrows(IllegalArgumentException.class, () -> UiPlan.parseModelOutput(text));
        }
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> UiPlan.parseModelOutput(PLAN.replace("Find and open entries", "private-marker\u0007")));
        assertFalse(error.getMessage().contains("private-marker"));
    }

    @Test public void maximalPlanStaysWithinItsLimit() {
        JsonObject o = new JsonObject();
        o.addProperty("layout", "\"".repeat(UiPlan.MAX_LAYOUT));
        JsonArray screens = new JsonArray();
        for (int s = 0; s < UiPlan.MAX_SCREENS; s++) {
            JsonObject screen = new JsonObject();
            screen.addProperty("name", s + "\"".repeat(UiPlan.MAX_NAME - 1));
            screen.addProperty("purpose", "\"".repeat(UiPlan.MAX_PURPOSE));
            screen.addProperty("firstView", "\"".repeat(UiPlan.MAX_FIRST_VIEW));
            JsonArray elements = new JsonArray(), states = new JsonArray();
            for (int i = 0; i < UiPlan.MAX_ELEMENTS; i++) elements.add("x" + "\n".repeat(UiPlan.MAX_ITEM - 2) + "x");
            for (int i = 0; i < UiPlan.MAX_STATES; i++) states.add("\"".repeat(UiPlan.MAX_ITEM));
            screen.add("elements", elements);
            screen.add("states", states);
            screens.add(screen);
        }
        o.add("screens", screens);
        for (String[] list : new String[][]{{"interactions", "" + UiPlan.MAX_INTERACTIONS}, {"content", "" + UiPlan.MAX_CONTENT}, {"accessibility", "" + UiPlan.MAX_ACCESSIBILITY}}) {
            JsonArray values = new JsonArray();
            for (int i = 0; i < Integer.parseInt(list[1]); i++) values.add("\"".repeat(UiPlan.MAX_ITEM));
            o.add(list[0], values);
        }
        String json = UiPlan.parse(o.toString()).toJson();
        assertTrue(json.length() + " chars", json.length() <= UiPlan.MAX_JSON_CHARS);
        assertEquals(json, UiPlan.parse(json).toJson());
    }
}

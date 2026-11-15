package app.pub.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;
import static org.junit.Assert.*;

/** Launcher icons are pictograms: valid 48-unit vectors without text, added to a package under a free asset id. */
public class AppIconTest {
    private static final String NOTEBOOK = "{\"shapes\":["
        + "{\"id\":\"cover\",\"type\":\"rect\",\"x\":10,\"y\":7,\"width\":24,\"height\":34,\"rx\":3,\"fill\":\"transparent\",\"stroke\":\"primary\"},"
        + "{\"id\":\"line\",\"type\":\"line\",\"x1\":16,\"y1\":16,\"x2\":28,\"y2\":16,\"stroke\":\"primary\"},"
        + "{\"id\":\"pen\",\"type\":\"path\",\"commands\":[[\"M\",38,12],[\"L\",30,36],[\"Z\"]],\"fill\":\"transparent\",\"stroke\":\"primary\"}]}";

    private static ProjectBundle bundle(String assets, String icon, String source) {
        JsonObject o = new JsonObject();
        o.addProperty("name", "Notes"); o.addProperty("description", "Notes"); o.addProperty("source", source);
        o.add("capabilities", new com.google.gson.JsonArray());
        if (assets != null) o.add("assets", JsonParser.parseString(assets));
        if (icon != null) o.addProperty("icon", icon);
        return ProjectBundle.parse(o.toString());
    }
    private static String vector(int size, String shape) {
        return "{\"type\":\"vector\",\"width\":" + size + ",\"height\":" + size + ",\"shapes\":[" + shape + "]}";
    }
    private static final String RECT = "{\"id\":\"r\",\"type\":\"rect\",\"x\":8,\"y\":8,\"width\":32,\"height\":32}";
    private static final String SOURCE = "function dispatch(raw){return JSON.stringify({ui:{type:'text',text:'x'},effects:[]});}";

    @Test public void designsBecomePictogramsOnTheGrid() {
        JsonObject icon = AppIcon.fromDesign(NOTEBOOK);
        assertEquals("vector", icon.get("type").getAsString());
        assertEquals(AppIcon.GRID, icon.get("width").getAsInt());
        assertEquals(AppIcon.GRID, icon.get("height").getAsInt());
        assertEquals(3, icon.getAsJsonArray("shapes").size());
        assertTrue(AppIcon.isPictogram(icon));
        // A Markdown fence around the answer is tolerated.
        assertTrue(AppIcon.isPictogram(AppIcon.fromDesign("```json\n" + NOTEBOOK + "\n```")));
    }

    @Test public void lettersOtherFieldsAndInvalidShapesAreRejected() {
        String letters = "{\"shapes\":[{\"id\":\"t\",\"type\":\"text\",\"x\":10,\"y\":30,\"text\":\"PJ\",\"size\":20}]}";
        assertThrows(IllegalArgumentException.class, () -> AppIcon.fromDesign(letters));
        assertThrows(IllegalArgumentException.class, () -> AppIcon.fromDesign("{\"shapes\":[],\"name\":\"x\"}"));
        assertThrows(IllegalArgumentException.class, () -> AppIcon.fromDesign("{\"shapes\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> AppIcon.fromDesign("{\"shapes\":[{\"id\":\"x\",\"type\":\"blob\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> AppIcon.fromDesign("not json"));
        assertThrows(IllegalArgumentException.class, () -> AppIcon.fromDesign(null));
        StringBuilder many = new StringBuilder("{\"shapes\":[");
        for (int i = 0; i <= AppIcon.MAX_SHAPES; i++) many.append(i == 0 ? "" : ",").append(RECT.replace("\"r\"", "\"r" + i + "\""));
        assertThrows(IllegalArgumentException.class, () -> AppIcon.fromDesign(many + "]}"));
        assertFalse("Not on the grid", AppIcon.isPictogram(JsonParser.parseString(vector(64, RECT)).getAsJsonObject()));
        assertFalse(AppIcon.isPictogram(JsonParser.parseString("{\"type\":\"image\",\"prompt\":\"an icon\"}").getAsJsonObject()));
    }

    @Test public void iconsAreAddedUnderAFreeIdAndReplaceUnusedOldOnes() {
        JsonObject icon = AppIcon.fromDesign(NOTEBOOK);
        ProjectBundle plain = AppIcon.withIcon(bundle(null, null, SOURCE), icon);
        assertEquals(AppIcon.ASSET_ID, plain.icon);
        assertNotNull(AppIcon.pictogram(plain));

        // The id is taken by an illustration the app shows: the icon gets the next id and the illustration stays.
        String shown = SOURCE.replace("{type:'text',text:'x'}", "{type:'graphic',asset:'app-icon',description:'Art'}");
        ProjectBundle taken = AppIcon.withIcon(bundle("{\"app-icon\":" + vector(320, RECT) + "}", "app-icon", shown), icon);
        assertEquals("app-icon-2", taken.icon);
        assertTrue(taken.assets.has("app-icon"));

        // An old icon the app never shows is replaced instead of piling up.
        ProjectBundle old = AppIcon.withIcon(bundle("{\"logo\":" + vector(320, RECT) + "}", "logo", SOURCE), icon);
        assertFalse(old.assets.has("logo"));
        assertEquals(1, old.assets.size());
        assertNull("A non-pictogram icon is not a pictogram", AppIcon.pictogram(bundle("{\"logo\":" + vector(320, RECT) + "}", "logo", SOURCE)));

        // No room left: the package keeps its assets and gets no icon.
        StringBuilder full = new StringBuilder("{");
        for (int i = 0; i < 8; i++) full.append(i == 0 ? "" : ",").append("\"a").append(i).append("\":").append(vector(320, RECT));
        assertNull(AppIcon.withIcon(bundle(full + "}", null, SOURCE), icon));

        ProjectBundle without = AppIcon.withoutIcon(plain);
        assertNull(without.icon);
        assertTrue(without.assets.has(AppIcon.ASSET_ID));
    }
}

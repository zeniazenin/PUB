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

    /** Curves are measured at their extremes, not their control points, so curvy icons fit like boxy ones. */
    @Test public void boundsFollowCurvesAndFitCentresInTheLiveArea() {
        JsonObject arch = JsonParser.parseString(vector(48, "{\"id\":\"a\",\"type\":\"path\",\"commands\":[[\"M\",10,30],[\"C\",10,10,38,10,38,30]]}")).getAsJsonObject();
        assertArrayEquals(new double[]{10, 15, 38, 30}, AppIcon.bounds(arch), 1e-9);
        JsonObject quad = JsonParser.parseString(vector(48, "{\"id\":\"q\",\"type\":\"path\",\"commands\":[[\"M\",8,40],[\"Q\",24,8,40,40]]}")).getAsJsonObject();
        assertEquals(24, AppIcon.bounds(quad)[1], 1e-9);

        // The arch (28 x 15 plus 1 unit of stroke on each side) is scaled to the 30-unit live area and centred on 24,24.
        double[] fit = AppIcon.fit(arch, 1);
        double scale = (AppIcon.LIVE_MAX - AppIcon.LIVE_MIN) / 30.0;
        assertEquals(scale, fit[0], 1e-9);
        assertEquals(24, 24 * fit[0] + fit[1], 1e-9);
        assertEquals(24, 22.5 * fit[0] + fit[2], 1e-9);

        // A tiny design grows by at most MAX_GROWTH; one without geometry is left as it is.
        JsonObject dot = JsonParser.parseString(vector(48, "{\"id\":\"d\",\"type\":\"ellipse\",\"cx\":30,\"cy\":30,\"rx\":2,\"ry\":2}")).getAsJsonObject();
        assertEquals(AppIcon.MAX_GROWTH, AppIcon.fit(dot, 1)[0], 1e-9);
        assertArrayEquals(new double[]{1, 0, 0}, AppIcon.fit(JsonParser.parseString("{\"shapes\":[]}").getAsJsonObject(), 1), 1e-9);
    }

    /** Designs are cleaned to the icon style when they arrive: half units, no widths, the icon palette, nothing invisible or repeated. */
    @Test public void designsAreRefinedToTheIconStyle() {
        JsonObject icon = AppIcon.fromDesign("{\"shapes\":["
            + "{\"id\":\"body\",\"type\":\"rect\",\"x\":12.26,\"y\":9.9,\"width\":24.1,\"height\":30,\"rx\":3.2,\"fill\":\"secondaryContainer\",\"stroke\":\"#FF000000\",\"strokeWidth\":4},"
            + "{\"id\":\"copy\",\"type\":\"rect\",\"x\":12.4,\"y\":10.1,\"width\":24,\"height\":30,\"rx\":3,\"fill\":\"surfaceVariant\",\"stroke\":\"onSurface\"},"
            + "{\"id\":\"dot\",\"type\":\"ellipse\",\"cx\":24,\"cy\":24,\"rx\":0.2,\"ry\":0.2,\"fill\":\"primary\"},"
            + "{\"id\":\"ghost\",\"type\":\"line\",\"x1\":10,\"y1\":10,\"x2\":30,\"y2\":30,\"stroke\":\"transparent\"},"
            + "{\"id\":\"line\",\"type\":\"line\",\"x1\":16,\"y1\":18.74,\"x2\":32,\"y2\":18.74,\"fill\":\"primary\"},"
            + "{\"id\":\"cut\",\"type\":\"ellipse\",\"cx\":24,\"cy\":30,\"rx\":3,\"ry\":3,\"fill\":\"surface\",\"stroke\":\"#00FFFFFF\"}]}");
        com.google.gson.JsonArray shapes = icon.getAsJsonArray("shapes");
        // The copy rounds to the same rectangle and is dropped; the dot is too small; the ghost line draws nothing.
        java.util.List<String> ids = new java.util.ArrayList<>(); for (com.google.gson.JsonElement s : shapes) ids.add(s.getAsJsonObject().get("id").getAsString());
        assertEquals(java.util.List.of("body", "line", "cut"), ids);
        JsonObject body = shapes.get(0).getAsJsonObject();
        assertFalse(body.has("strokeWidth"));
        assertEquals(12.5, body.get("x").getAsDouble(), 0); assertEquals(10, body.get("y").getAsDouble(), 0);
        assertEquals(24, body.get("width").getAsDouble(), 0); assertEquals(3, body.get("rx").getAsDouble(), 0);
        assertEquals("primaryContainer", body.get("fill").getAsString()); assertEquals("primary", body.get("stroke").getAsString());
        JsonObject line = shapes.get(1).getAsJsonObject();
        assertEquals("transparent", line.get("fill").getAsString()); assertEquals("primary", line.get("stroke").getAsString());
        assertEquals(18.5, line.get("y1").getAsDouble(), 0);
        JsonObject cut = shapes.get(2).getAsJsonObject();
        assertEquals("surface", cut.get("fill").getAsString()); assertEquals("transparent", cut.get("stroke").getAsString());
        // Refining is idempotent, and a design that draws nothing is refused.
        assertEquals(icon, AppIcon.refine(icon));
        assertThrows(IllegalArgumentException.class, () -> AppIcon.fromDesign("{\"shapes\":[{\"id\":\"a\",\"type\":\"line\",\"x1\":1,\"y1\":1,\"x2\":1.1,\"y2\":1.1}]}"));
    }

    /** Busyness counts drawing: a line 1, a rect or ellipse 2, a path its commands; past the style's limits a design is too busy. */
    @Test public void busyDesignsAreRecognised() {
        JsonObject simple = AppIcon.fromDesign(NOTEBOOK);
        assertEquals(2 + 1 + 3, AppIcon.busyness(simple));
        assertFalse(AppIcon.tooBusy(simple));
        StringBuilder many = new StringBuilder("{\"shapes\":[");
        for (int i = 0; i < AppIcon.SIMPLE_SHAPES + 1; i++) {
            if (i > 0) many.append(',');
            many.append("{\"id\":\"l").append(i).append("\",\"type\":\"line\",\"x1\":10,\"y1\":").append(10 + 4 * i).append(",\"x2\":38,\"y2\":").append(10 + 4 * i).append('}');
        }
        assertTrue(AppIcon.tooBusy(AppIcon.fromDesign(many + "]}")));
        StringBuilder path = new StringBuilder("{\"shapes\":[{\"id\":\"p\",\"type\":\"path\",\"commands\":[[\"M\",10,10]");
        for (int i = 0; i < AppIcon.SIMPLE_BUSYNESS; i++) path.append(",[\"L\",").append(10 + i % 20).append(',').append(10 + (i * 7) % 28).append(']');
        assertTrue(AppIcon.tooBusy(AppIcon.fromDesign(path + "]}]}")));
    }

    /** The designer's reference designs are valid, already refined and within the style's limits. */
    @Test public void styleExamplesAreCleanAndSimple() {
        assertFalse(AppIcon.STYLE_EXAMPLES.isEmpty());
        AppIcon.STYLE_EXAMPLES.forEach((name, json) -> {
            JsonObject icon = AppIcon.fromDesign(json);
            assertTrue(name, AppIcon.isPictogram(icon));
            assertEquals(name, JsonParser.parseString(json).getAsJsonObject().get("shapes"), icon.get("shapes"));
            assertFalse(name, AppIcon.tooBusy(icon));
        });
    }
}

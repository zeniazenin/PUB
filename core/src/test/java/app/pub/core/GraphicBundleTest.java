package app.pub.core;
import com.google.gson.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class GraphicBundleTest {
    private JsonObject base() { return JsonParser.parseString("{\"name\":\"Graphic test\",\"description\":\"Test\",\"capabilities\":[],\"source\":\"function dispatch(e){return '{}';}\"}").getAsJsonObject(); }
    @Test public void vectorAssetsAndIconRoundTripWithoutChangingLegacyPackages() {
        JsonObject root=base(); assertEquals(root,JsonParser.parseString(ProjectBundle.parse(root.toString()).toJson()));
        JsonObject assets=JsonParser.parseString("{\"art\":{\"type\":\"vector\",\"width\":100,\"height\":100,\"shapes\":[{\"id\":\"string1\",\"type\":\"line\",\"x1\":10,\"y1\":10,\"x2\":90,\"y2\":90}]}}").getAsJsonObject();
        root.add("assets",assets);root.addProperty("icon","art");
        ProjectBundle bundle=ProjectBundle.parse(root.toString());bundle.requireReadyAssets();
        assertEquals(root,JsonParser.parseString(bundle.toJson()));
        root.addProperty("icon","missing");assertThrows(IllegalArgumentException.class,()->ProjectBundle.parse(root.toString()));
    }
    @Test public void imageRequestsCannotBecomeRunnableUntilPixelsArePresent() {
        JsonObject root=base();root.add("assets",JsonParser.parseString("{\"art\":{\"type\":\"image\",\"prompt\":\"Guitar illustration\"}}"));
        ProjectBundle bundle=ProjectBundle.parse(root.toString());
        assertThrows(IllegalStateException.class,bundle::requireReadyAssets);
        assertTrue(bundle.toPromptJson().contains("Guitar illustration"));
    }
}

package app.pub.core;

import com.google.gson.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class BuilderContractTest {
    private String bundle(String caps) { return "{\"name\":\"Counter\",\"description\":\"Counts\",\"source\":\"function dispatch(e){return '{}';}\",\"capabilities\":"+caps+"}"; }
    @Test public void supportedProjectRoundTrips() {
        ProjectBundle p=ProjectBundle.parse(bundle("[\"storage.project\"]"));
        assertEquals(p.source,ProjectBundle.parse(p.toJson()).source);
        assertTrue(p.capabilities.contains("storage.project"));
    }
    @Test public void rejectsPathsAndUnsupportedHardware() {
        assertThrows(IllegalArgumentException.class,()->ProjectBundle.parse(bundle("[\"arbitrary.network\"]")));
        assertThrows(IllegalArgumentException.class,()->ProjectBundle.parse(bundle("[]").replace("\"name\":", "\"id\":\"../../x\",\"name\":")));
        assertThrows(IllegalArgumentException.class,()->ProjectBundle.parse("{".repeat(26)));
    }
    @Test public void pkceMatchesRfc7636() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",OAuthRules.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        assertNotEquals(OAuthRules.random(),OAuthRules.random());
    }
    @Test public void rejectsForgedAndChangedRegistrations() {
        assertThrows(IllegalArgumentException.class,()->OAuthRules.client("state","wrong","dynamic_agent_client","oaiapp_123"));
        assertThrows(IllegalArgumentException.class,()->OAuthRules.client("state","state","dynamic_agent_client",null));
        assertThrows(IllegalArgumentException.class,()->OAuthRules.client("state","state","oaiapp_a","oaiapp_b"));
        assertEquals("oaiapp_a",OAuthRules.client("state","state","oaiapp_a",null));
        assertEquals("oaiapp_new",OAuthRules.client("state","state","dynamic_agent_client","oaiapp_new"));
    }
    @Test public void generatedCalculatorRendersAndPersistsInSandbox() {
        String source="var amount='0';function dispatch(raw){var e=JSON.parse(raw);if(e.type==='init')amount=e.storage.amount||'0';if(e.type==='input')amount=e.value;return JSON.stringify({ui:{type:'column',children:[{type:'input',label:'Bill',action:'bill',value:amount},{type:'text',text:String(Number(amount)*1.2)}]},effects:e.type==='input'?[{type:'storage.save',data:{amount:amount}}]:[]});}";
        ScriptEngine engine=new ScriptEngine();engine.load(source);
        UiPacket first=UiPacket.parse(engine.dispatch("{\"type\":\"init\",\"storage\":{}}"));
        assertEquals(0,first.effects.size());
        UiPacket changed=UiPacket.parse(engine.dispatch("{\"type\":\"input\",\"action\":\"bill\",\"value\":\"100\"}"));
        assertEquals("120",changed.ui.getAsJsonArray("children").get(1).getAsJsonObject().get("text").getAsString());
        assertEquals("100",changed.effects.get(0).getAsJsonObject().getAsJsonObject("data").get("amount").getAsString());
    }
    @Test public void inputBoundsAreEnforced() {
        JsonObject node=new JsonObject();node.addProperty("type","input");node.addProperty("label","Bill");node.addProperty("action","bill");node.addProperty("value","x".repeat(2001));
        assertThrows(IllegalArgumentException.class,()->UiPacket.parse("{\"ui\":"+node+"}"));
    }
}

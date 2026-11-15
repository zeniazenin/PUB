package app.pub.core;

import com.google.gson.*;
import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;

/**
 * The tuner device-test fixture (PUB no longer ships a tuner). It is read from the test APK's assets, shared with the
 * androidTest suites that need a microphone app, and modeled on an app saved from the former built-in tuner.
 */
public class TunerProjectTest {
    private static final Path FIXTURE = Path.of("app/src/androidTest/assets/fixtures/tuner");
    private ScriptEngine tuner() throws Exception {
        ScriptEngine engine = new ScriptEngine();
        engine.load(Files.readString(FIXTURE.resolve("main.js")));
        UiPacket.parse(engine.dispatch("{\"type\":\"init\"}"));
        return engine;
    }
    /** The same package the device tests' TunerFixture saves, so an invalid fixture fails here first. */
    @Test public void fixtureIsAValidPackageWithItsGraphic() throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("name", "Guitar Tuner"); root.addProperty("description", "Device test fixture: offline guitar tuner");
        root.addProperty("source", Files.readString(FIXTURE.resolve("main.js")));
        root.add("assets", JsonParser.parseString(Files.readString(FIXTURE.resolve("graphics.json"))).getAsJsonObject());
        root.addProperty("icon", "guitar");
        JsonArray capabilities = new JsonArray(); capabilities.add("microphone.pitch"); capabilities.add("storage.project");
        root.add("capabilities", capabilities);
        ProjectBundle bundle = ProjectBundle.parse(root.toString());
        bundle.requireReadyAssets();
        assertTrue(bundle.assets.has("guitar"));
        assertTrue(tuner().dispatch("{\"type\":\"init\",\"settings\":{},\"storage\":{}}").contains("GUITAR TUNER"));
    }
    @Test public void microphoneAccessIsAnEffectNotASideEffect() throws Exception {
        ScriptEngine engine=tuner();
        UiPacket p=UiPacket.parse(engine.dispatch("{\"type\":\"action\",\"action\":\"start\"}"));
        assertEquals("microphone.start",p.effects.get(0).getAsJsonObject().get("type").getAsString());
        assertTrue(p.ui.toString().contains("Start listening")); // Native permission success drives state.
    }
    @Test public void identifiesAllStringsAndDropD() throws Exception {
        ScriptEngine engine=tuner();
        engine.dispatch("{\"type\":\"status\",\"listening\":true,\"message\":\"Listening\"}");
        double[] hz={82.406889,110,146.832384,195.997718,246.941651,329.627557};
        String[] notes={"E2","A2","D3","G3","B3","E4"};
        for(int i=0;i<hz.length;i++) {
            UiPacket p=UiPacket.parse(engine.dispatch("{\"type\":\"pitch\",\"hz\":"+hz[i]+",\"confidence\":0.99,\"rms\":0.2}"));
            assertEquals(notes[i],p.ui.getAsJsonArray("children").get(1).getAsJsonObject().get("text").getAsString());
            assertTrue(p.ui.toString().contains("In tune"));
            assertEquals("string"+i,p.ui.getAsJsonArray("children").get(2).getAsJsonObject().getAsJsonArray("highlight").get(0).getAsString());
        }
        engine.dispatch("{\"type\":\"action\",\"action\":\"drop\"}");
        String result=engine.dispatch("{\"type\":\"pitch\",\"hz\":73.416192,\"confidence\":0.99,\"rms\":0.2}");
        assertTrue(result.contains("D2")); assertTrue(result.contains("In tune"));
    }
    @Test public void silenceAndRevocationClearStalePitch() throws Exception {
        ScriptEngine engine=tuner();
        engine.dispatch("{\"type\":\"status\",\"listening\":true,\"message\":\"Listening\"}");
        engine.dispatch("{\"type\":\"pitch\",\"hz\":110,\"confidence\":0.99,\"rms\":0.2}");
        assertTrue(engine.dispatch("{\"type\":\"pitch\",\"hz\":0,\"confidence\":0,\"rms\":0}").contains("Pluck one string"));
        String stopped=engine.dispatch("{\"type\":\"status\",\"listening\":false,\"message\":\"Revoked\"}");
        assertTrue(stopped.contains("Revoked")); assertTrue(stopped.contains("Start listening"));
    }
    @Test public void detectedGraphicTracksPitchRatherThanManualTargetAndClearsOnStop() throws Exception {
        ScriptEngine engine=tuner();
        engine.dispatch("{\"type\":\"action\",\"action\":\"string:0\"}");
        engine.dispatch("{\"type\":\"status\",\"listening\":true,\"message\":\"Listening\"}");
        UiPacket packet=UiPacket.parse(engine.dispatch("{\"type\":\"pitch\",\"hz\":110,\"confidence\":0.99,\"rms\":0.2}"));
        assertEquals("string1",packet.ui.getAsJsonArray("children").get(2).getAsJsonObject().getAsJsonArray("highlight").get(0).getAsString());
        packet=UiPacket.parse(engine.dispatch("{\"type\":\"status\",\"listening\":false,\"message\":\"Stopped\"}"));
        assertEquals(0,packet.ui.getAsJsonArray("children").get(2).getAsJsonObject().getAsJsonArray("highlight").size());
    }
    @Test public void legacySettingsRestoreAndChangesPersistThroughStorage() throws Exception {
        ScriptEngine engine=tuner();
        assertTrue(engine.dispatch("{\"type\":\"init\",\"settings\":{\"dropD\":true,\"reference\":442},\"storage\":{}}").contains("442 Hz"));
        UiPacket p=UiPacket.parse(engine.dispatch("{\"type\":\"action\",\"action\":\"refUp\"}"));
        JsonObject effect=p.effects.get(0).getAsJsonObject();
        assertEquals("storage.save",effect.get("type").getAsString());
        assertEquals(443,effect.getAsJsonObject("data").get("reference").getAsInt());
        assertTrue(effect.getAsJsonObject("data").get("dropD").getAsBoolean());
        // Saved storage takes precedence over the legacy settings.
        String restored=engine.dispatch("{\"type\":\"init\",\"settings\":{\"dropD\":true,\"reference\":442},\"storage\":{\"dropD\":false,\"reference\":445}}");
        assertTrue(restored.contains("445 Hz")); assertTrue(restored.contains("E2"));
    }
}

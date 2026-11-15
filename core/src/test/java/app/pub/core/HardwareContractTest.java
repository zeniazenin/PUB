package app.pub.core;

import com.google.gson.*;
import org.junit.Test;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

/** Boundary tests for untrusted generated hardware requests and private camera asset references. */
public class HardwareContractTest {
    private JsonObject request(String capability, String operation) {
        JsonObject request = new JsonObject();
        request.addProperty("type", "hardware.request");
        request.addProperty("id", "request_1");
        request.addProperty("capability", capability);
        request.addProperty("operation", operation);
        request.add("args", new JsonObject());
        return request;
    }
    private String packet(JsonObject effect) {
        JsonObject root = new JsonObject();
        JsonObject ui = new JsonObject(); ui.addProperty("type", "text"); ui.addProperty("text", "Hardware app");
        root.add("ui", ui);
        JsonArray effects = new JsonArray(); effects.add(effect); root.add("effects", effects);
        return root.toString();
    }

    @Test public void projectCanDeclareEverySupportedHardwareCapability() {
        Set<String> expected = Set.of("camera", "location", "sensors", "bluetooth", "haptics", "torch", "audio", "battery", "infrared", "nfc", "usb", "files", "microphone.pitch", "storage.project");
        JsonObject bundle = new JsonObject();
        bundle.addProperty("name", "Hardware workshop");
        bundle.addProperty("description", "Only declared, brokered hardware access");
        bundle.addProperty("source", "function dispatch(e){return JSON.stringify({ui:{type:'text',text:'Ready'},effects:[]});}");
        JsonArray capabilities = new JsonArray(); expected.forEach(capabilities::add); bundle.add("capabilities", capabilities);
        assertEquals(expected, ProjectBundle.parse(bundle.toString()).capabilities);
        capabilities.add("arbitrary.network");
        assertThrows(IllegalArgumentException.class, () -> ProjectBundle.parse(bundle.toString()));
    }

    @Test public void realUiPacketsAcceptSupportedCapabilityRequests() {
        Map<String,String> examples = Map.ofEntries(
            Map.entry("camera", "list"), Map.entry("location", "stop"), Map.entry("sensors", "list"),
            Map.entry("bluetooth", "choose"), Map.entry("haptics", "stop"), Map.entry("torch", "set"),
            Map.entry("audio", "stop"), Map.entry("battery", "get"), Map.entry("infrared", "frequencies"),
            Map.entry("nfc", "read"), Map.entry("usb", "choose"), Map.entry("files", "grants")
        );
        examples.forEach((capability, operation) -> {
            JsonObject command = request(capability, operation);
            if (capability.equals("torch")) command.getAsJsonObject("args").addProperty("enabled", false);
            assertEquals(capability, UiPacket.parse(packet(command)).effects.get(0).getAsJsonObject().get("capability").getAsString());
        });
    }

    @Test public void unknownAndCrossCapabilityOperationsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> HardwareContract.validate(request("bluetooth", "openAddress")));
        assertThrows(IllegalArgumentException.class, () -> HardwareContract.validate(request("bluetooth", "capture")));
        assertThrows(IllegalArgumentException.class, () -> HardwareContract.validate(request("camera", "execute")));
        assertThrows(IllegalArgumentException.class, () -> HardwareContract.validate(request("network", "fetch")));
    }

    @Test public void requestIdentityAndEnvelopeCannotSmuggleHostFields() {
        JsonObject extra = request("bluetooth", "choose"); extra.addProperty("projectId", "another-project");
        assertThrows(IllegalArgumentException.class, () -> HardwareContract.validate(extra));
        JsonObject badId = request("camera", "list"); badId.addProperty("id", "../../other");
        assertThrows(IllegalArgumentException.class, () -> HardwareContract.validate(badId));
        JsonObject noId = request("camera", "list"); noId.remove("id");
        assertThrows(IllegalArgumentException.class, () -> HardwareContract.validate(noId));
        JsonObject numericCap = request("camera", "list"); numericCap.addProperty("capability", 12);
        assertThrows(IllegalArgumentException.class, () -> HardwareContract.validate(numericCap));
        JsonObject wrongType = request("camera", "list"); wrongType.addProperty("type", "camera.capture");
        assertThrows(IllegalArgumentException.class, () -> HardwareContract.validate(wrongType));
    }

    @Test public void malformedAndOversizedArgumentsAreRejected() {
        for (JsonElement args : new JsonElement[]{JsonNull.INSTANCE, new JsonArray(), new JsonPrimitive("not-an-object")}) {
            JsonObject command = request("usb", "choose"); command.add("args", args);
            assertThrows(IllegalArgumentException.class, () -> HardwareContract.validate(command));
        }
        JsonObject huge = request("usb", "write"); huge.getAsJsonObject("args").addProperty("data", "x".repeat(32768));
        assertThrows(IllegalArgumentException.class, () -> HardwareContract.validate(huge));
        String deep = "{\"ui\":{\"type\":\"text\",\"text\":\"x\"},\"effects\":[{\"type\":\"hardware.request\",\"id\":\"x\",\"capability\":\"usb\",\"operation\":\"choose\",\"args\":{\"data\":" + "[".repeat(26) + "0" + "]".repeat(26) + "}}]}";
        assertThrows(IllegalArgumentException.class, () -> UiPacket.parse(deep));
    }

    @Test public void cameraImageReferencesAreOpaqueLocalFilenamesOnly() {
        String asset = "12345678-1234-1234-1234-123456789abc.jpg";
        assertTrue(HardwareContract.validAsset(asset));
        JsonObject image = new JsonObject(); image.addProperty("type", "image"); image.addProperty("asset", asset); image.addProperty("description", "Captured photo");
        JsonObject root = new JsonObject(); root.add("ui", image);
        assertEquals(asset, UiPacket.parse(root.toString()).ui.get("asset").getAsString());
        for (String invalid : new String[]{"../" + asset, "/" + asset, "file:///" + asset, "content://camera/" + asset,
            "https://example.com/" + asset, asset + "/..", asset.replace(".jpg", ".png"), asset.replace(".jpg", ".JPG"), "secret.json"}) {
            assertFalse(invalid, HardwareContract.validAsset(invalid));
            image.addProperty("asset", invalid);
            assertThrows(IllegalArgumentException.class, () -> UiPacket.parse(root.toString()));
        }
    }
}

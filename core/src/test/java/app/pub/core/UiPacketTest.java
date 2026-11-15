package app.pub.core;
import org.junit.Test;

public class UiPacketTest {
    @Test(expected=IllegalArgumentException.class) public void malformedOptionalBooleanRejected() { UiPacket.parse("{\"ui\":{\"type\":\"meter\",\"value\":0,\"active\":{}}}"); }
    @Test(expected=IllegalArgumentException.class) public void nestedJsonRejectedBeforeTreeParsing() {
        String array="0";
        for(int i=0;i<1000;i++) array="["+array+"]";
        UiPacket.parse("{\"ui\":"+array+"}");
    }
    @Test(expected=IllegalArgumentException.class) public void unknownEffectRejected() {
        UiPacket.parse("{\"ui\":{\"type\":\"text\",\"text\":\"x\"},\"effects\":[{\"type\":\"camera.start\"}]}");
    }
    /** The former built-in tuner's settings.save effect is gone; apps persist through storage.save. */
    @Test(expected=IllegalArgumentException.class) public void legacySettingsSaveIsAnUnknownEffect() {
        UiPacket.parse("{\"ui\":{\"type\":\"text\",\"text\":\"x\"},\"effects\":[{\"type\":\"settings.save\",\"dropD\":true,\"reference\":440}]}");
    }
    @Test(expected=IllegalArgumentException.class) public void unknownNativeComponentRejected() { UiPacket.parse("{\"ui\":{\"type\":\"WebView\"}}"); }
    @Test(expected=IllegalArgumentException.class) public void invalidMeterRejected() { UiPacket.parse("{\"ui\":{\"type\":\"meter\",\"value\":1e100}}"); }
    @Test(expected=IllegalArgumentException.class) public void excessiveDepthRejected() {
        String node="{\"type\":\"text\",\"text\":\"x\"}";
        for(int i=0;i<12;i++) node="{\"type\":\"column\",\"children\":["+node+"]}";
        UiPacket.parse("{\"ui\":"+node+"}");
    }
    private static String input(String fields) { return "{\"ui\":{\"type\":\"input\",\"label\":\"Note\",\"action\":\"note\"," + fields + "}}"; }
    private static String text(String style, int length) { return "{\"ui\":{\"type\":\"text\",\"style\":\"" + style + "\",\"text\":\"" + "x".repeat(length) + "\"}}"; }
    @Test public void multilineInputsHoldLongerTextWithKeyboardAndVoiceOptions() {
        UiPacket.parse(input("\"value\":\"" + "x".repeat(UiPacket.MAX_MULTILINE_VALUE) + "\",\"multiline\":true,\"voice\":true"));
        UiPacket.parse(input("\"value\":\"12.5\",\"keyboard\":\"decimal\""));
        org.junit.Assert.assertThrows(IllegalArgumentException.class, () -> UiPacket.parse(input("\"value\":\"" + "x".repeat(UiPacket.MAX_INPUT_VALUE + 1) + "\"")));
        org.junit.Assert.assertThrows(IllegalArgumentException.class, () -> UiPacket.parse(input("\"value\":\"" + "x".repeat(UiPacket.MAX_INPUT_VALUE + 1) + "\",\"multiline\":false")));
        org.junit.Assert.assertThrows(IllegalArgumentException.class, () -> UiPacket.parse(input("\"value\":\"\",\"multiline\":\"yes\"")));
        org.junit.Assert.assertThrows(IllegalArgumentException.class, () -> UiPacket.parse(input("\"value\":\"\",\"voice\":1")));
        org.junit.Assert.assertThrows(IllegalArgumentException.class, () -> UiPacket.parse(input("\"value\":\"\",\"keyboard\":\"emoji\"")));
    }
    @Test public void onlyParagraphTextHoldsLongReadingText() {
        UiPacket.parse(text("paragraph", UiPacket.MAX_PARAGRAPH));
        UiPacket.parse(text("body", UiPacket.MAX_TEXT));
        org.junit.Assert.assertThrows(IllegalArgumentException.class, () -> UiPacket.parse(text("body", UiPacket.MAX_TEXT + 1)));
        org.junit.Assert.assertThrows(IllegalArgumentException.class, () -> UiPacket.parse(text("paragraph", UiPacket.MAX_PARAGRAPH + 1)));
    }
}

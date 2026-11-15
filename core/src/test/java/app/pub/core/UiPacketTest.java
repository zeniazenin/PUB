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
    @Test(expected=IllegalArgumentException.class) public void unknownNativeComponentRejected() { UiPacket.parse("{\"ui\":{\"type\":\"WebView\"}}"); }
    @Test(expected=IllegalArgumentException.class) public void invalidMeterRejected() { UiPacket.parse("{\"ui\":{\"type\":\"meter\",\"value\":1e100}}"); }
    @Test(expected=IllegalArgumentException.class) public void excessiveDepthRejected() {
        String node="{\"type\":\"text\",\"text\":\"x\"}";
        for(int i=0;i<12;i++) node="{\"type\":\"column\",\"children\":["+node+"]}";
        UiPacket.parse("{\"ui\":"+node+"}");
    }
}

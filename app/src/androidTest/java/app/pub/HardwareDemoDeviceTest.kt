package app.pub

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pub.core.ScriptEngine
import app.pub.core.UiPacket
import app.pub.hardware.HardwareDemo
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HardwareDemoDeviceTest {
    @Test fun hardwareCheckScreensAndEffectsValidateInRealJavaScriptEngine() {
        val bundle=HardwareDemo.bundle()
        val engine=ScriptEngine();engine.load(bundle.source)
        UiPacket.parse(engine.dispatch("{\"type\":\"init\",\"storage\":{}}"))
        val actions=listOf("page:camera","camera","page:audio","audioRoute","audioTone","audioSpeak","microphone","microphoneStop","audioStop","audioReset",
            "page:sensors","sensor","sensorList","sensorStop","page:location","location","page:bluetooth","paired","bluetooth","bleConnect","bleStop",
            "page:other","vibrate","torchOn","torchOff","battery","nfc","usb","usbStop","infrared","menu")
        for(action in actions) {
            val request=JsonObject().apply {addProperty("type","action");addProperty("action",action)}
            val packet=UiPacket.parse(engine.dispatch(request.toString()))
            assertNotNull(packet.ui)
            packet.effects.forEach {e->if(e.asJsonObject["type"].asString=="hardware.request") assertTrue(bundle.capabilities.contains(e.asJsonObject["capability"].asString))}
        }
        val packet=UiPacket.parse(engine.dispatch("{\"type\":\"hardware\",\"id\":\"test\",\"capability\":\"camera\",\"event\":\"photo\",\"data\":{\"asset\":\"00000000-0000-0000-0000-000000000001.jpg\"}}"))
        assertEquals("storage.save",packet.effects[0].asJsonObject["type"].asString)
    }
}

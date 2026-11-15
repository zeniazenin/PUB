package app.pub

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.pub.hardware.*
import com.google.gson.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HardwareDeviceTest {
    @get:Rule val permissions:GrantPermissionRule=GrantPermissionRule.grant(Manifest.permission.CAMERA)
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun main(block:()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun request(cap:String,op:String,args:JsonObject=JsonObject())=JsonObject().apply {
        addProperty("type","hardware.request");addProperty("id","test");addProperty("capability",cap);addProperty("operation",op);add("args",args)
    }
    private fun await(message:String,predicate:()->Boolean) {
        val end=System.currentTimeMillis()+10000
        while(System.currentTimeMillis()<end) {var done=false;main {done=predicate()};if(done)return;Thread.sleep(50)}
        fail(message)
    }
    @Test fun undeclaredAndNonActionRequestsCannotOpenHardware() {
        val output=mutableListOf<JsonObject>();var prompts=0
        lateinit var broker:HardwareBroker
        main {
            broker=HardwareBroker(context,UUID.randomUUID().toString(),false,emptySet(),{true},{if(it!=null)prompts++},{},{},{},{},{e,_->output.add(e)},{})
            broker.request(request("camera","capture"),true)
            broker.request(request("sensors","start"),false)
            assertEquals(0,prompts);assertEquals(2,output.size)
            assertTrue(output.all {it["event"].asString=="error"});broker.close()
        }
    }
    @Test fun realSensorGrantRevocationAndBackgroundDropQueuedEvents() {
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            scenario.onActivity {it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            val prefs=context.getSharedPreferences("permissions",Context.MODE_PRIVATE)
            val keys=listOf("sensors.blocked","sensors.default")
            val prior=keys.associateWith {prefs.all[it]}
            lateinit var broker:HardwareBroker
            var foreground=true;var asked=false;val output=mutableListOf<JsonObject>()
            try {
                main {
                    prefs.edit().putBoolean("sensors.blocked",false).putString("sensors.default","ASK").commit()
                    broker=HardwareBroker(context,UUID.randomUUID().toString(),false,setOf("sensors"),{foreground},{asked=it!=null},{},{},{},{},{e,_->output.add(e)},{})
                    broker.request(request("sensors","start",JsonObject().apply {addProperty("sensor","accelerometer");addProperty("rateHz",5)}),true)
                    assertTrue(asked);assertTrue(output.isEmpty());broker.consent(true,false)
                }
                await("real accelerometer events") {output.any {it["event"].asString=="sensor"}}
                var before=0
                main {prefs.edit().putBoolean("sensors.blocked",true).commit();before=output.size}
                Thread.sleep(400)
                main {assertTrue(broker.isClosed);assertEquals(before,output.size);foreground=false;broker.request(request("sensors","list"),true);assertEquals(before,output.size)}
            } finally {
                main {broker.close();prefs.edit().apply {keys.forEach {key->when(val old=prior[key]) {is Boolean->putBoolean(key,old);is String->putString(key,old);else->remove(key)}}}.commit()}
            }
        }
    }
    @Test fun lateCameraResultCannotReachClosedSessionAndPhotoIsRemoved() {
        val id="preview-${UUID.randomUUID()}";val media=ProjectMedia(context)
        val prefs=context.getSharedPreferences("permissions",Context.MODE_PRIVATE)
        val oldBlock=prefs.all["camera.blocked"];val oldDefault=prefs.all["camera.default"]
        var callback:((JsonObject?)->Unit)?=null;val output=mutableListOf<JsonObject>();lateinit var broker:HardwareBroker
        try {
            main {
                prefs.edit().putBoolean("camera.blocked",false).putString("camera.default","ASK").commit()
                broker=HardwareBroker(context,id,true,setOf("camera"),{true},{},{},{},{callback=it},{},{e,_->output.add(e)},{})
                broker.request(request("camera","capture"),true);broker.consent(true,false)
                assertNotNull(callback)
                val stale=callback!!
                val asset="${UUID.randomUUID()}.jpg";val file=File(media.directory(id),asset)
                val bitmap=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888)
                file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,85,it)};bitmap.recycle()
                broker.close();stale(JsonObject().apply {addProperty("asset",asset)})
                assertNull(callback);assertTrue(output.isEmpty());assertFalse(file.exists())
            }
        } finally {
            main {broker.close();media.delete(id);prefs.edit().apply {
                if(oldBlock is Boolean)putBoolean("camera.blocked",oldBlock) else remove("camera.blocked")
                if(oldDefault is String)putString("camera.default",oldDefault) else remove("camera.default")
            }.commit()}
        }
    }
}

package app.pub

import android.content.Context
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pub.runtime.RuntimeClient
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class IsolationTest {
    @Test fun scriptRunsUnderSeparateUidAndCannotSeeJava() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context: Context = instrumentation.targetContext
        val done = CountDownLatch(1)
        val error = AtomicReference<String?>(null)
        var uid = -1
        var text = ""
        lateinit var client: RuntimeClient
        instrumentation.runOnMainSync {
            client = RuntimeClient(context, onReady = {
                uid = it
                client.send(JsonObject().apply { addProperty("type", "init") })
            }, onPacket = { packet -> text = packet.ui["text"].asString; done.countDown() }, onError = { error.set(it); done.countDown() })
            client.start("function dispatch(s) { return JSON.stringify({ui:{type:'text',text:typeof java+','+typeof Packages+','+typeof fetch},effects:[]}); }")
        }
        try {
            assertTrue("Service response timed out", done.await(15, TimeUnit.SECONDS))
            assertNull(error.get())
            assertTrue("UID must be assigned", uid > 0)
            assertNotEquals(Process.myUid(), uid)
            assertEquals("undefined,undefined,undefined", text)
        } finally { instrumentation.runOnMainSync { client.close() } }
    }
}

package app.pub

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pub.core.PermissionPolicy.Rule
import app.pub.permissions.PermissionBroker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MicrophonePreparationStatusTest {
    /** Keep user permission preferences and Android's actual grant untouched; no capture is started. */
    private class IsolatedPermissionContext(base: Context, private val namespace: String) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences("$namespace.$name", mode)
        override fun checkSelfPermission(permission: String): Int =
            if (permission == Manifest.permission.RECORD_AUDIO) PackageManager.PERMISSION_GRANTED else super.checkSelfPermission(permission)
    }

    @Test fun asynchronousPreparationDoesNotReportStoppedAndLateSuccessCannotUndoStop() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val namespace = "microphone-preparation-test-${UUID.randomUUID()}"
        val context = IsolatedPermissionContext(base, namespace)
        val statuses = mutableListOf<Pair<Boolean, String>>()
        var pending: ((AudioDeviceInfo?) -> Unit)? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val broker = PermissionBroker(context,
                prepareInput = { ready, _ -> pending = ready }, declared = { true },
                requestUser = { fail("The isolated policy allows capture") }, requestAndroid = { fail("The fixture permission is granted") },
                status = { active, message -> statuses.add(active to message) }, pitch = { _, _ -> fail("Capture must never start") })
            try {
                broker.globalDefault = Rule.ALLOW
                broker.setForeground(true)
                broker.start()
                assertNotNull("The route is being prepared asynchronously", pending)
                assertFalse(broker.active)
                assertTrue("Preparation must not clear a script's wanted-listening flag with a false status", statuses.isEmpty())
                broker.start()
                assertTrue("Repeated Start during preparation must remain silent", statuses.isEmpty())
                broker.stop("Stopped by user")
                assertEquals(listOf(false to "Stopped by user"), statuses)
                pending!!(null)
                assertFalse(broker.active)
                assertEquals("A late ready callback cannot restart or announce capture", 1, statuses.size)
            } finally { broker.close() }
        }
        base.deleteSharedPreferences("$namespace.permissions")
    }

    @Test fun preparationFailureStillReportsATerminalFalseStatus() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val namespace = "microphone-preparation-test-${UUID.randomUUID()}"
        val context = IsolatedPermissionContext(base, namespace)
        val statuses = mutableListOf<Pair<Boolean, String>>()
        var reject: ((String) -> Unit)? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val broker = PermissionBroker(context,
                prepareInput = { _, error -> reject = error }, declared = { true },
                requestUser = { fail("The isolated policy allows capture") }, requestAndroid = { fail("The fixture permission is granted") },
                status = { active, message -> statuses.add(active to message) }, pitch = { _, _ -> fail("Capture must never start") })
            try {
                broker.globalDefault = Rule.ALLOW
                broker.setForeground(true)
                broker.start()
                assertTrue(statuses.isEmpty())
                checkNotNull(reject)("Selected headset disconnected")
                assertEquals(listOf(false to "Selected headset disconnected"), statuses)
                assertFalse(broker.active)
                checkNotNull(reject)("Late duplicate failure")
                assertEquals("Invalidated callbacks cannot emit duplicate terminal results", 1, statuses.size)
            } finally { broker.close() }
        }
        base.deleteSharedPreferences("$namespace.permissions")
    }
}

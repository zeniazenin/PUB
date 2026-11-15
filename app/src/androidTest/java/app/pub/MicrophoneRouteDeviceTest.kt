package app.pub

import android.Manifest
import android.content.Context
import android.media.AudioDeviceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.pub.permissions.PermissionBroker
import app.pub.core.PermissionPolicy.Rule as AccessRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MicrophoneRouteDeviceTest {
    @get:Rule val permission:GrantPermissionRule=GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    @Test fun revokedOrClosedRoutePreparationCannotStartCapture() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val prefs=context.getSharedPreferences("permissions",Context.MODE_PRIVATE)
        val old=prefs.all["microphone.blocked"]
        val id=UUID.randomUUID().toString()
        var pending:((AudioDeviceInfo?)->Unit)?=null
        var started=0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val broker=PermissionBroker(context,projectId={id},prepareInput={ready,_->pending=ready},declared={true},
                requestUser={fail("App rule already allows microphone")},requestAndroid={fail("Android permission expected")},
                status={active,_->if(active)started++},pitch={_,_->fail("No capture should start")})
            try {
                broker.globalBlocked=false;broker.appRule=AccessRule.ALLOW;broker.setForeground(true)
                broker.start();assertNotNull(pending);assertFalse(broker.active)
                broker.globalBlocked=true;pending!!(null)
                assertFalse(broker.active);assertEquals(0,started)
                broker.globalBlocked=false;broker.start();val late=pending!!
                broker.close();late(null)
                assertFalse(broker.active);assertEquals(0,started)
            } finally {
                broker.close()
                prefs.edit().remove("$id.microphone").apply {if(old is Boolean)putBoolean("microphone.blocked",old) else remove("microphone.blocked")}.commit()
            }
        }
    }
}

package app.pub

import android.app.Application
import android.app.NotificationManager
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.pub.builder.*
import app.pub.core.ProjectBundle
import app.pub.core.VisualReviewDecision
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real foreground service and private-display renderer with bounded offline model fixtures. */
class BackgroundBuildTest {
    private val app get()=ApplicationProvider.getApplicationContext<Application>()
    private fun main(block:()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun waitFor(label:String,timeout:Long=30000,check:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<end) {var done=false;main {done=check()};if(done)return;Thread.sleep(60)}
        fail(label)
    }
    private class Backend:BuilderBackend {
        override val initialModel="fixture-model"
        val gate=CountDownLatch(1);val calls=AtomicInteger();val reviews=AtomicInteger()
        override fun generate(model:String,request:String,context:String,existing:ProjectBundle?):ProjectBundle {
            val n=calls.incrementAndGet()
            if(n==1) check(gate.await(20,TimeUnit.SECONDS)) {"Fixture was not released"}
            return bundle(n)
        }
        override fun assess(model:String,request:String,bundle:ProjectBundle,png:ByteArray):VisualReviewDecision {
            check(png.size>100)
            return VisualReviewDecision.parse(if(reviews.incrementAndGet()==1) """{"status":"fix","summary":"Correct the heading","issues":["Make the heading match the request."]}""" else """{"status":"pass","summary":"The revised heading is visible","issues":[]}""")
        }
        companion object {
            fun bundle(n:Int)=ProjectBundle.parse(JsonObject().apply {
                addProperty("name","Background fixture");addProperty("description","Controlled test")
                add("capabilities",JsonArray())
                addProperty("source","function dispatch(e){return JSON.stringify({ui:{type:'text',text:'Background result $n',style:'display'},effects:[]});}")
            }.toString())
        }
    }
    @Test fun finishesAfterActivityStopsPersistsThenNotifiesAndRestores() {
        val directory=File(app.cacheDir,"background-build-${UUID.randomUUID()}").apply {check(mkdir())}
        val context=object:ContextWrapper(app){override fun getFilesDir()=directory}
        val backend=Backend()
        lateinit var controller:BuilderController
        var previous:BuilderController?=null
        main {controller=BuilderController(app,backend,context,true);previous=BuilderControllers.replaceForTest(controller)}
        try {
            waitFor("Controller restoration failed") {controller.busy==null}
            ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                scenario.onActivity {controller.prompt="A readable heading";controller.generate("{\"screenWidthDp\":320,\"screenHeightDp\":480}")}
                waitFor("Build service did not start") {controller.buildInProgress && backend.calls.get()>0}
                waitFor("Progress notification missing") {app.getSystemService(NotificationManager::class.java).activeNotifications.any {it.id==BuildNotifications.ONGOING_ID}}
                scenario.moveToState(Lifecycle.State.CREATED)
                if(InstrumentationRegistry.getArguments().getString("pubSleepDuringBuild")=="true") {
                    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 223").close()
                    waitFor("Phone did not sleep") {!app.getSystemService(android.os.PowerManager::class.java).isInteractive}
                }
                backend.gate.countDown()
                waitFor("Background build did not finish",45000) {!controller.buildInProgress && controller.hasBuildResult}
                main {assertNull(controller.visualReviewError);assertTrue(controller.message!!.contains("passed"));assertNotNull(controller.draft)}
                assertEquals(2,backend.calls.get());assertEquals(2,backend.reviews.get())
                val saved=BuildDraftStore(context).read()!!
                assertFalse(saved.running);assertNotNull(saved.draft);assertNotNull(saved.visualReview)
                waitFor("Completion notification missing") {app.getSystemService(NotificationManager::class.java).activeNotifications.any {it.id==BuildNotifications.RESULT_ID}}
                val notification=app.getSystemService(NotificationManager::class.java).activeNotifications.first {it.id==BuildNotifications.RESULT_ID}.notification
                assertNotNull(notification.contentIntent)
                assertEquals("app.pub",notification.contentIntent.creatorPackage)
                if(InstrumentationRegistry.getArguments().getString("pubSleepDuringBuild")=="true")
                    assertFalse("The private renderer must not wake the phone",app.getSystemService(android.os.PowerManager::class.java).isInteractive)
            }
            // New process-owned controller restores without another model call.
            main {controller.close()}
            val restoredBackend=Backend()
            lateinit var restored:BuilderController
            main {restored=BuilderController(app,restoredBackend,context,true)}
            try {
                waitFor("Draft not restored") {restored.busy==null}
                main {assertNotNull(restored.draft);assertFalse(restored.buildInProgress);assertTrue(restored.hasBuildResult)}
                assertEquals(0,restoredBackend.calls.get())
                val oldSession=restored.historySessionId
                val projectId=UUID.randomUUID().toString()
                main {restored.markSaved(restored.draftHistoryId,projectId,"Saved fixture")}
                waitFor("Saved result not adopted") {restored.busy==null && !restored.hasBuildResult}
                main {assertEquals(projectId,restored.editingProject!!.id);assertNotEquals(oldSession,restored.historySessionId);assertNull(restored.draft)}
                assertNull(BuildDraftStore(context).read())
            } finally {main {restored.close()}}
        } finally {
            backend.gate.countDown();main {controller.cancel();controller.close();BuilderControllers.replaceForTest(previous)}
            app.getSystemService(NotificationManager::class.java).cancel(BuildNotifications.RESULT_ID)
            directory.deleteRecursively()
        }
    }
    @Test fun notificationCancelStopsBuildAndEmptyResultCanBeDismissed() {
        val directory=File(app.cacheDir,"background-cancel-${UUID.randomUUID()}").apply {check(mkdir())}
        val context=object:ContextWrapper(app){override fun getFilesDir()=directory}
        val backend=Backend();lateinit var controller:BuilderController;var previous:BuilderController?=null
        main {controller=BuilderController(app,backend,context,true);previous=BuilderControllers.replaceForTest(controller)}
        try {
            waitFor("Not ready") {controller.busy==null}
            ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                scenario.onActivity {controller.prompt="Cancellation fixture";controller.generate("{}")}
                val manager=app.getSystemService(NotificationManager::class.java)
                waitFor("Missing cancel action") {manager.activeNotifications.any {it.id==BuildNotifications.ONGOING_ID && !it.notification.actions.isNullOrEmpty()}}
                scenario.moveToState(Lifecycle.State.CREATED)
                val notification=manager.activeNotifications.first {it.id==BuildNotifications.ONGOING_ID}.notification
                notification.actions.first {it.title.toString()=="Cancel"}.actionIntent.send()
                waitFor("Cancel action not delivered") {controller.message?.contains("cancelled",ignoreCase=true)==true}
                backend.gate.countDown()
                waitFor("Cancelled build still running") {!controller.buildInProgress && controller.busy==null}
                assertEquals(0,backend.reviews.get())
                main {assertNull(controller.draft);assertTrue(controller.hasBuildResult)}
                assertFalse(BuildDraftStore(context).read()!!.running)
                main {controller.clearDraft()}
                waitFor("Result did not clear") {!controller.hasBuildResult && controller.busy==null}
                assertNull(BuildDraftStore(context).read())
                main {controller.beginCreate();assertEquals("",controller.prompt)}
            }
        } finally {
            backend.gate.countDown();main {controller.close();BuilderControllers.replaceForTest(previous)}
            app.getSystemService(NotificationManager::class.java).cancel(BuildNotifications.RESULT_ID)
            directory.deleteRecursively()
        }
    }
    @Test fun interruptedSnapshotIsRecoveredWithoutPaidRetry() {
        val directory=File(app.cacheDir,"background-interrupted-${UUID.randomUUID()}").apply {check(mkdir())}
        val context=object:ContextWrapper(app){override fun getFilesDir()=directory}
        val snapshot=BuildDraftSnapshot("A request",UUID.randomUUID().toString(),null,Backend.bundle(1),UUID.randomUUID().toString(),"A request","fixture-model",0,null,null,null,true)
        BuildDraftStore(context).write(snapshot)
        val backend=Backend();lateinit var controller:BuilderController
        main {controller=BuilderController(app,backend,context,true)}
        try {
            waitFor("Interrupted state did not recover") {controller.busy==null}
            main {assertFalse(controller.buildInProgress);assertNotNull(controller.draft);assertTrue(controller.message!!.contains("interrupted"));assertNotNull(controller.visualReviewError)}
            assertEquals(0,backend.calls.get());assertFalse(BuildDraftStore(context).read()!!.running)
        } finally {main {controller.close()};directory.deleteRecursively()}
    }
}

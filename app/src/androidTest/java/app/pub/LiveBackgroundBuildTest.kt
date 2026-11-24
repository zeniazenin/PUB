package app.pub

import android.app.Application
import android.app.NotificationManager
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.pub.auth.ChatGptAuth
import app.pub.builder.*
import app.pub.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class LiveBackgroundBuildTest {
    private fun main(block:()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun await(label:String,limit:Long=180000,check:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+limit
        while(SystemClock.elapsedRealtime()<until){var ok=false;main{ok=check()};if(ok)return;Thread.sleep(100)}
        fail(label)
    }
    @Test fun realChatGptBuildCompletesInBackgroundAndNotificationOpensDraft() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pubLiveBackgroundBuild")=="true")
        val app=ApplicationProvider.getApplicationContext<Application>()
        val auth=ChatGptAuth(app);auth.prepare();assertNotNull(auth.accountLabel())
        val selected=ModelSelection.choose(auth.models().map {ModelSelection.Model(it.id,it.name)},null)
        val directory=File(app.cacheDir,"live-background-${UUID.randomUUID()}").apply{check(mkdir())}
        val context=object:ContextWrapper(app){override fun getFilesDir()=directory}
        val builds=AtomicInteger();val reviews=AtomicInteger()
        val backend=object:BuilderBackend {
            override val initialModel=selected
            override fun generate(model:String,request:String,context:String,existing:ProjectBundle?):ProjectBundle {
                builds.incrementAndGet();return Generator(auth).generate(model,request,context,existing)
            }
            override fun assess(model:String,request:String,bundle:ProjectBundle,png:ByteArray):VisualReviewDecision {
                reviews.incrementAndGet();File(app.cacheDir,"background-live-proof.png").writeBytes(png)
                return VisualReview(auth).assess(model,request,bundle,png)
            }
        }
        lateinit var controller:BuilderController;var prior:BuilderController?=null
        main {controller=BuilderController(app,backend,context,true);prior=BuilderControllers.replaceForTest(controller)}
        try {
            await("Controller not ready",15000){controller.busy==null}
            ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                scenario.onActivity {activity ->
                    val host=androidx.lifecycle.ViewModelProvider(activity)[HostModel::class.java]
                    controller.prompt="A simple offline counter with heading 'Practice counter', a displayed count starting at zero, an Add one button, and a Reset button. No storage, graphics or hardware access needed."
                    controller.generate(host.generationContext())
                }
                await("Model did not start",15000){builds.get()>0}
                scenario.moveToState(Lifecycle.State.CREATED)
                await("Background build did not finish",300000){!controller.buildInProgress && controller.hasBuildResult}
                main {assertNotNull(controller.draft);assertNull(controller.visualReviewError);assertTrue(controller.message!!.contains("passed"))}
                assertTrue(reviews.get()>0)
                val snapshot=BuildDraftStore(context).read()!!;assertFalse(snapshot.running);assertNotNull(snapshot.draft)
                val notifications=app.getSystemService(NotificationManager::class.java)
                await("Ready notification missing",10000){notifications.activeNotifications.any {it.id==BuildNotifications.RESULT_ID}}
                scenario.close() // Verify notification cold-opens a new Activity; controller and draft survive.
                val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
                automation.executeShellCommand("cmd statusbar expand-notifications").close()
                fun find(node:AccessibilityNodeInfo?,text:String):AccessibilityNodeInfo? {
                    if(node==null)return null
                    if(node.text?.toString()==text)return node
                    for(i in 0 until node.childCount)find(node.getChild(i),text)?.let{return it}
                    return null
                }
                var readyNode:AccessibilityNodeInfo?=null
                await("Notification text not visible",10000){readyNode=find(automation.rootInActiveWindow,"Draft ready to review");readyNode!=null}
                var clickNode=readyNode
                while(clickNode!=null && !clickNode.isClickable)clickNode=clickNode.parent
                assertNotNull(clickNode);assertTrue(clickNode!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                // With a draft the builder opens on its refine heading; Prompt history follows further down.
                await("Notification did not open the builder",15000){find(automation.rootInActiveWindow,"Try it, then fine-tune")!=null}
                main {assertNotNull(controller.draft)}
                File(app.cacheDir,"background-live-proof-summary.txt").writeText("Live ChatGPT background build passed; screenshot reviews=${reviews.get()}, generation calls=${builds.get()}. Completion notification opened the builder.")
            }
        } finally {
            auth.cancel();main {controller.close();BuilderControllers.replaceForTest(prior)}
            app.getSystemService(NotificationManager::class.java).cancel(BuildNotifications.RESULT_ID)
            directory.deleteRecursively()
        }
    }
}

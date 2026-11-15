package app.pub

import android.content.ContextWrapper
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import app.pub.auth.ChatGptAuth
import app.pub.builder.*
import app.pub.core.*
import app.pub.ui.BuildVisualPreview
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class LiveAutomaticRepairTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun actualModelRepairsVisibleMismatchThenChecksNewScreenshot() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pubLiveAutoRepair")=="true")
        val app=compose.activity.application
        val auth=ChatGptAuth(app);assertNotNull("Connect ChatGPT first",auth.accountLabel());auth.prepare()
        val selected=ModelSelection.choose(auth.models().map {ModelSelection.Model(it.id,it.name)},null)
        val directory=File(app.cacheDir,"live-auto-repair-${UUID.randomUUID()}").apply {check(mkdir())}
        val context=object:ContextWrapper(app){override fun getFilesDir()=directory}
        val store=ViewModelStore()
        val reviews=AtomicInteger();val builds=AtomicInteger()
        val images=mutableListOf<Int>();val decisions=mutableListOf<String>()
        lateinit var builder:BuilderModel
        val backend=object:BuilderBackend {
            override val initialModel=selected
            override fun generate(model:String,request:String,context:String,existing:ProjectBundle?):ProjectBundle {
                if(builds.incrementAndGet()==1) return ProjectBundle.parse(JsonObject().apply {
                    addProperty("name","Visual repair proof");addProperty("description","Controlled temporary test")
                    add("capabilities",JsonArray())
                    addProperty("source","function dispatch(e){return JSON.stringify({ui:{type:'column',children:[{type:'text',text:'WRONG TITLE',style:'display'},{type:'text',text:'Placeholder'}]},effects:[]});}")
                }.toString())
                return Generator(auth).generate(model,request,context,existing)
            }
            override fun assess(model:String,request:String,bundle:ProjectBundle,png:ByteArray):VisualReviewDecision {
                val n=reviews.incrementAndGet()
                File(app.cacheDir,"auto-repair-proof-$n.png").writeBytes(png)
                synchronized(images) {images.add(png.contentHashCode())}
                val decision=VisualReview(auth).assess(model,request,bundle,png)
                synchronized(decisions) {decisions.add(decision.status)}
                File(app.cacheDir,"auto-repair-proof-$n.txt").writeText(VisualReview.formatDecision(decision))
                return decision
            }
        }
        try {
            compose.runOnUiThread {
                builder=BuilderModel(app,backend,context);store.put("proof",builder)
                compose.activity.setContent {
                    MaterialTheme {
                        Box(Modifier.fillMaxSize()) {
                            val capture=builder.capturePending
                            if(capture!=null && builder.busy==null) key(capture.token) {
                                BuildVisualPreview(capture.bundle,com.google.gson.JsonParser.parseString(capture.context).asJsonObject,{builder.reviewCapture(capture.token,it)},{builder.captureFailed(capture.token,it)})
                            } else Text(builder.busy ?: builder.message ?: "Waiting")
                        }
                    }
                }
                builder.prompt="Build a simple offline greeting card. The main heading must read exactly 'Hello guitarist' and the body must read exactly 'Ready to practice'. Use large readable heading text and normal body text. No buttons, other controls, graphics, storage or hardware access are needed."
                builder.generate(androidx.lifecycle.ViewModelProvider(compose.activity)[HostModel::class.java].generationContext())
            }
            compose.waitUntil(360000) {reviews.get()>0 && builder.busy==null && builder.capturePending==null}
            compose.runOnIdle {assertNull(builder.visualReviewError);assertTrue(builder.message!!.contains("passed"))}
            assertEquals("fix",decisions.first());assertEquals("pass",decisions.last())
            assertTrue(reviews.get()>=2);assertTrue(builds.get()>=2)
            assertNotEquals(images.first(),images.last())
            val entries=PromptHistoryStore(context).list()
            assertTrue(entries.any {it.automatic});assertTrue(entries.all {it.review!=null})
        } finally {
            auth.cancel();compose.runOnUiThread {store.clear()};directory.deleteRecursively()
        }
    }
}

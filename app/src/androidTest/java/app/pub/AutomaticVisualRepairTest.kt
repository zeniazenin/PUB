package app.pub

import android.app.Application
import android.content.ContextWrapper
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import app.pub.builder.*
import app.pub.core.ProjectBundle
import app.pub.core.VisualReviewDecision
import app.pub.ui.BuildVisualPreview
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real isolated runtime/capture, deterministic model responses, and a private fixture journal. */
class AutomaticVisualRepairTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val lifecycle=ViewModelStore()
    private lateinit var builder:BuilderModel
    private lateinit var directory:File
    private lateinit var journal:PromptHistoryStore
    private val request="Build a guitar overview with readable string labels and a start button."
    private class Backend(val forever:Boolean=false,val failRepair:Boolean=false,val invalidReview:Boolean=false):BuilderBackend {
        override val initialModel="fixture-model"
        val generations=AtomicInteger();val reviews=AtomicInteger()
        val images=mutableListOf<Int>();val contexts=mutableListOf<String>();val sources=mutableListOf<String>()
        var gate:CountDownLatch?=null
        var repairStarted=CountDownLatch(1)
        override fun generate(model:String,request:String,context:String,existing:ProjectBundle?):ProjectBundle {
            val n=generations.incrementAndGet()
            synchronized(contexts) {contexts.add(context)}
            if(n>1) {
                repairStarted.countDown();gate?.await(10,TimeUnit.SECONDS)
                if(failRepair) error("Controlled repair failure")
                check(existing!=null)
            }
            return ProjectBundle.parse(JsonObject().apply {
                addProperty("name","Repair fixture")
                addProperty("description","Temporary deterministic fixture")
                add("capabilities",JsonArray())
                addProperty("source","function dispatch(e){return JSON.stringify({ui:{type:'column',children:[{type:'text',text:'Guitar overview revision $n',style:'display'},{type:'button',label:'Start tuning',action:'start'}]},effects:[]});}")
            }.toString())
        }
        override fun assess(model:String,request:String,bundle:ProjectBundle,png:ByteArray):VisualReviewDecision {
            val n=reviews.incrementAndGet()
            synchronized(images) {images.add(png.contentHashCode());sources.add(bundle.source)}
            if(invalidReview) return VisualReviewDecision.parse("not a decision")
            val fix=forever || n==1
            return VisualReviewDecision.parse(if(fix) """{"status":"fix","summary":"A visible label needs correction.","issues":["Make the visible heading clearer."]}""" else """{"status":"pass","summary":"The corrected heading is visible and readable.","issues":[]}""")
        }
    }
    private fun start(backend:Backend,automaticCapture:Boolean=true) {
        val app=compose.activity.application as Application
        directory=File(app.cacheDir,"auto-review-test-${UUID.randomUUID()}").apply {check(mkdir())}
        val context=object:ContextWrapper(app){override fun getFilesDir()=directory}
        journal=PromptHistoryStore(context)
        compose.runOnUiThread {
            builder=BuilderModel(app,backend,context);lifecycle.put("fixture",builder)
            compose.activity.setContent {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        val capture=builder.capturePending
                        if(automaticCapture && capture!=null && builder.busy==null) {
                            key(capture.token) {BuildVisualPreview(capture.bundle,JsonObject(),
                                {builder.reviewCapture(capture.token,it)},{builder.captureFailed(capture.token,it)})}
                        } else Text(builder.busy ?: builder.message ?: "Waiting")
                    }
                }
            }
            builder.prompt=request;builder.generate("{\"screenWidthDp\":412}")
        }
    }
    private fun finished(minReviews:Int,backend:Backend) {
        compose.waitUntil(30000) {backend.reviews.get()>=minReviews && builder.busy==null && builder.capturePending==null}
    }
    @After fun cleanup() {
        compose.runOnUiThread {lifecycle.clear()}
        if(::directory.isInitialized) directory.deleteRecursively()
    }
    @Test fun fixesAutomaticallyCapturesChangedPixelsAndLinksBothPassesToApp() {
        val backend=Backend();start(backend);finished(2,backend)
        assertEquals(2,backend.generations.get());assertEquals(2,backend.reviews.get())
        assertNotEquals(backend.images[0],backend.images[1])
        assertNotEquals(backend.sources[0],backend.sources[1])
        assertTrue(backend.contexts.last().contains(request))
        compose.runOnIdle {assertNull(builder.visualReviewError);assertTrue(builder.message!!.contains("passed"));assertTrue(builder.draft!!.source.contains("revision 2"))}
        val entries=journal.list();assertEquals(2,entries.size)
        assertTrue(entries.first().automatic);assertFalse(entries.last().automatic)
        assertTrue(entries.all {it.review!=null})
        val project=UUID.randomUUID().toString()
        journal.markSaved(entries.first().id,project,"Saved fixture")
        assertEquals(2,journal.list(project).size)
        assertTrue(PromptHistoryStore(object:ContextWrapper(compose.activity){override fun getFilesDir()=directory}).list(project).first().automatic)
    }
    @Test fun stopsAfterTwoRepairsAndLeavesUnresolvedReview() {
        val backend=Backend(forever=true);start(backend);finished(3,backend)
        assertEquals(3,backend.generations.get());assertEquals(3,backend.reviews.get())
        compose.runOnIdle {assertTrue(builder.message!!.contains("limit"));assertNotNull(builder.visualReviewError);assertTrue(builder.draft!!.source.contains("revision 3"))}
        assertEquals(2,journal.list().count {it.automatic})
    }
    @Test fun invalidReviewNeverPassesOrTriggersRepair() {
        val backend=Backend(invalidReview=true);start(backend);finished(1,backend)
        assertEquals(1,backend.generations.get())
        compose.runOnIdle {assertNotNull(builder.visualReviewError);assertNotNull(builder.draft);assertFalse(builder.message!!.contains("passed"))}
    }
    @Test fun failedRepairKeepsLastValidDraftAndRecordsFailure() {
        val backend=Backend(failRepair=true);start(backend);finished(1,backend)
        assertEquals(2,backend.generations.get())
        compose.runOnIdle {assertTrue(builder.draft!!.source.contains("revision 1"));assertNotNull(builder.visualReviewError)}
        assertEquals("failed",journal.list().first().status);assertTrue(journal.list().first().automatic)
    }
    @Test fun cancellationDuringRepairPreventsAnotherCapture() {
        val backend=Backend().apply {gate=CountDownLatch(1)};start(backend)
        try {
            compose.waitUntil(20000) {backend.repairStarted.count==0L}
            compose.runOnUiThread {builder.cancel()}
        } finally {backend.gate!!.countDown()}
        finished(1,backend)
        assertEquals(1,backend.reviews.get())
        compose.runOnIdle {assertNull(builder.capturePending);assertTrue(builder.draft!!.source.contains("revision 1"))}
        assertEquals("cancelled",journal.list().first().status)
    }
    @Test fun staleCaptureCannotReviewAnotherDraft() {
        val backend=Backend();start(backend,automaticCapture=false)
        compose.waitUntil(15000) {builder.capturePending!=null && builder.busy==null}
        compose.runOnIdle {
            val capture=builder.capturePending!!
            builder.reviewCapture("stale-token",byteArrayOf(1))
            assertEquals(capture.token,builder.capturePending!!.token);assertEquals(0,backend.reviews.get())
            builder.cancel();assertNull(builder.capturePending)
        }
    }
}

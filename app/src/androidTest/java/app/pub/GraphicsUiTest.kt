package app.pub

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pub.core.ProjectBundle
import com.google.gson.JsonObject
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GraphicsUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun historyIsScopedToBuilderAndAbsentFromSettings() {
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("prompt-history").assertDoesNotExist()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("create-app").performClick()
        compose.onNodeWithTag("builder-history").performClick()
        compose.onNodeWithTag("prompt-history-screen").assertIsDisplayed()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("app-instructions").assertExists()
    }
    @Test fun generatedVectorDrawsAndItsHighlightChangesPixels() {
        val fixture=File(compose.activity.cacheDir,"graphics-proof-vector.json")
        assumeTrue("Run the explicit live vector test first",fixture.exists())
        val bundle=ProjectBundle.parse(fixture.readText())
        val host=ViewModelProvider(compose.activity)[HostModel::class.java]
        compose.runOnIdle {host.previewProject(bundle)}
        compose.waitUntil(15000){host.ui!=null || host.failure!=null}
        fun find(node:JsonObject,type:String):JsonObject? {
            if(node["type"].asString==type)return node
            node.getAsJsonArray("children")?.forEach {find(it.asJsonObject,type)?.let {return it}}
            return null
        }
        lateinit var asset:String;lateinit var button:String;lateinit var initial:String
        compose.runOnIdle {
            assertNull(host.failure)
            asset=find(host.ui!!,"graphic")!!["asset"].asString
            button=find(host.ui!!,"button")!!["label"].asString
            initial=host.ui.toString()
        }
        val graphic=compose.onNodeWithTag("graphic-$asset")
        graphic.performScrollTo().assertIsDisplayed()
        val before=graphic.captureToImage().toPixelMap()
        compose.onNodeWithText(button).performScrollTo().performClick()
        compose.waitUntil(10000){host.ui.toString()!=initial}
        graphic.performScrollTo()
        val result=graphic.captureToImage()
        val after=result.toPixelMap()
        assertEquals(before.width,after.width);assertEquals(before.height,after.height)
        var changed=0
        for(y in 0 until before.height step 2)for(x in 0 until before.width step 2)if(before[x,y]!=after[x,y])changed++
        assertTrue("Native vector highlights must visibly change the drawing",changed>10)
        File(compose.activity.cacheDir,"graphics-proof.png").outputStream().use {result.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        compose.runOnIdle {host.closeProject()}
    }
}

package app.pub

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pub.auth.ChatGptAuth
import app.pub.core.GraphicAsset
import app.pub.core.ModelSelection
import app.pub.core.ProjectBundle
import app.pub.projects.ProjectStore
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GraphicAssetsDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun bundle(data: String): ProjectBundle = ProjectBundle.parse(JsonObject().apply {
        addProperty("name","Graphic storage fixture");addProperty("description","Test fixture")
        addProperty("source","function dispatch(e){return JSON.stringify({ui:{type:'graphic',asset:'art',description:'Generated art'},effects:[]});}")
        add("capabilities",JsonArray());addProperty("icon","art")
        add("assets",JsonObject().apply {add("art",JsonObject().apply {addProperty("type","image");addProperty("prompt","A guitar icon");addProperty("data",data)})})
    }.toString())
    private fun verifyStorage(encoded:String) {
        val original=bundle(encoded);original.requireReadyAssets()
        val sandbox=File(context.cacheDir,"graphics-store-${UUID.randomUUID()}").apply {check(mkdir())}
        try {
            val store=ProjectStore(object:ContextWrapper(context){override fun getFilesDir()=sandbox})
            val saved=store.save(original)
            assertEquals(encoded,store.read(saved.id)!!.bundle.assets["art"].asJsonObject["data"].asString)
            val prompt=store.read(saved.id)!!.bundle.toPromptJson()
            assertFalse(prompt.contains(encoded));assertFalse(prompt.contains("\"data\""));assertTrue(prompt.contains("A guitar icon"))
            store.delete(saved.id);assertNull(store.read(saved.id))
        } finally { check(sandbox.canonicalFile.parentFile==context.cacheDir.canonicalFile);sandbox.deleteRecursively() }
    }
    @Test fun pngAssetsSurviveSaveAndEditsExcludePixelData() {
        val bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
        val output=java.io.ByteArrayOutputStream()
        bitmap.eraseColor(android.graphics.Color.GREEN)
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));bitmap.recycle()
        verifyStorage(java.util.Base64.getEncoder().encodeToString(output.toByteArray()))
    }
    @Test fun liveImageToolUsesExistingChatGptConnection() {
        assumeTrue("Explicit live test only",InstrumentationRegistry.getArguments().getString("pubLiveGraphics")=="true")
        val auth=ChatGptAuth(context);auth.foreground=true;auth.prepare()
        try {
            assertNotNull("Connect ChatGPT in PUB first",auth.accountLabel())
            val models=auth.models()
            val model=ModelSelection.choose(models.map {ModelSelection.Model(it.id,it.name)},null)
            val encoded = try {
                auth.generateImage(model,"One simple acoustic guitar app icon, mint and warm wood on dark green, no text.")
            } catch (failure: IllegalStateException) {
                if (!auth.imageToolsUnavailable()) throw failure
                InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {putString("graphicsUnavailable","OpenAI subscription-sharing connection rejects image tools; native vectors remain available")})
                assumeTrue("PNG tool unavailable for this ChatGPT connection",false)
                throw failure
            }
            val bytes=GraphicAsset.decodePng(encoded)
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            assertTrue(bounds.outWidth>0 && bounds.outHeight>0)
            verifyStorage(encoded)
            InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {putString("graphicsResult","Generated PNG ${bounds.outWidth}x${bounds.outHeight}; ${bytes.size} image bytes; validated and round-tripped locally")})
        } finally {auth.cancel()}
    }
    @Test fun liveVectorBuilderGeneratesAnInteractiveDiagram() {
        assumeTrue("Explicit live test only",InstrumentationRegistry.getArguments().getString("pubLiveGraphics")=="true")
        val auth=ChatGptAuth(context);auth.foreground=true;auth.prepare()
        try {
            val models=auth.models()
            val model=ModelSelection.choose(models.map {ModelSelection.Model(it.id,it.name)},null)
            val generated=app.pub.builder.Generator(auth).generate(model,
                "Create an offline app named Graphics Proof. Draw a real native vector schematic of a guitar with six separate strings and a curved body. Add one button labelled Next string that cycles the highlighted string. Use the vector asset as the launcher icon too. Use only native vectors, no PNG generation or ASCII. No hardware or storage is needed.",
                "{\"uiTheme\":\"dark\",\"accent\":\"moss\"}")
            generated.requireReadyAssets()
            assertTrue(generated.assets.entrySet().any {it.value.asJsonObject["type"].asString=="vector"})
            assertNotNull(generated.icon)
            val engine=app.pub.core.ScriptEngine();engine.load(generated.source)
            val packet=app.pub.core.UiPacket.parse(engine.dispatch("{\"type\":\"init\",\"storage\":{},\"settings\":{},\"device\":{}}"))
            fun find(node:JsonObject,type:String):JsonObject? {
                if(node["type"].asString==type)return node
                node.getAsJsonArray("children")?.forEach {find(it.asJsonObject,type)?.let {return it}}
                return null
            }
            val before=find(packet.ui,"graphic")!!
            val action=find(packet.ui,"button")!!["action"].asString
            val changed=app.pub.core.UiPacket.parse(engine.dispatch(JsonObject().apply {addProperty("type","action");addProperty("action",action)}.toString()))
            assertNotEquals(before.getAsJsonArray("highlight"),find(changed.ui,"graphic")!!.getAsJsonArray("highlight"))
            File(context.cacheDir,"graphics-proof-vector.json").writeText(generated.toJson())
            InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {putString("vectorResult","Real model generated vector guitar, launcher icon and working string-highlight button")})
        } finally {auth.cancel()}
    }

}

package app.pub

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.pub.builder.BuildEnvironment
import app.pub.builder.Generator
import app.pub.core.ProjectBundle
import app.pub.core.ScriptEngine
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BuildEnvironmentTest {
    // An edit's context needs an existing saved app.
    @get:Rule val tuner = TunerFixtureRule()

    @Test fun contextContainsActualViewportAndRuntimeLimitsWithoutAccountIdentity() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val host=HostModel(app)
        host.updateBuilderViewport(1080,1800)
        TunerFixture.awaitListed(host, tuner.id)
        val context=JsonParser.parseString(host.generationContext(tuner.id)).asJsonObject
        assertFalse(context.has("hostUid"));assertFalse(context.has("cloudConnection"))
        assertTrue(context.has("hardware"));assertTrue(context.has("model"));assertTrue(context.has("fontScale"))
        assertEquals(1080/app.resources.displayMetrics.density,context.getAsJsonObject("layoutContext")["measuredBuilderViewportWidthDp"].asFloat,0.1f)
        assertTrue(context.getAsJsonObject("permissionContext").has("appHardwareRules"))
        val limits=context.getAsJsonObject("buildEnvironment").getAsJsonObject("limits")
        assertEquals(ScriptEngine.MAX_SOURCE,limits["sourceCharacters"].asInt)
        assertEquals(ScriptEngine.MAX_MESSAGE,limits["eventAndUiPacketCharacters"].asInt)
        assertTrue(context.getAsJsonObject("buildEnvironment")["runtime"].asString.contains("Rhino"))
    }
    @Test fun buildAndRepairInputKeepFullRequirementsCurrentCodeAndEnvironment() {
        val bundle=ProjectBundle.parse(JsonObject().apply {
            addProperty("name","Test");addProperty("description","Test")
            addProperty("source","function dispatch(e){return JSON.stringify({ui:{type:'text',text:'Example'},effects:[]});}")
            add("capabilities",JsonArray())
        }.toString())
        val original="x".repeat(7990)+"END-OF-REQ"
        val context=JsonObject().apply {addProperty("originalUserRequest",original);addProperty("repairPass",1)}
        val input=Generator.buildInput("Fix the visible label",context.toString(),bundle)
        assertTrue(input.contains(original));assertTrue(input.contains(bundle.toPromptJson()))
        assertTrue(input.contains("Rhino"));assertTrue(input.contains("uiComponents"));assertTrue(input.contains("executionBudgetMsPerLoadOrDispatch"))
        assertTrue(input.endsWith("User request:\nFix the visible label"))
        assertTrue(BuildEnvironment.CONTRACT.contains("not a browser"))
        assertTrue(BuildEnvironment.CONTRACT.contains("65..400Hz"))
    }
}

package app.pub

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherUiTest {
    // Saved before the Activity starts, so the launcher's first library load lists it as an ordinary app.
    @get:Rule(order = 0) val tuner = TunerFixtureRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun launcherOpensFullscreenAppAndCloseUnloadsIt() {
        val host = ViewModelProvider(compose.activity)[HostModel::class.java]
        compose.onNodeWithTag("launcher").assertIsDisplayed()
        compose.runOnIdle { assertFalse(host.projectOpen); assertNull(host.isolatedUid) }
        compose.waitUntil(15000) { host.projects.any { it.id == tuner.id } }
        compose.onNodeWithTag("launcher").performScrollToNode(hasTestTag("app-${tuner.id}"))
        compose.onNodeWithTag("app-${tuner.id}").performClick()
        compose.waitUntil(15000) { host.ui != null || host.failure != null }
        compose.onNodeWithTag("close-app").assertIsDisplayed()
        compose.onNodeWithText("GUITAR TUNER").assertIsDisplayed()
        compose.onNodeWithTag("settings").assertDoesNotExist()
        compose.onNodeWithTag("launcher").assertDoesNotExist()
        compose.onNodeWithTag("close-app").performClick()
        compose.onNodeWithTag("launcher").assertIsDisplayed()
        compose.runOnIdle { assertFalse(host.projectOpen);assertNull(host.ui);assertNull(host.isolatedUid);assertFalse(host.listening) }
    }

    @Test fun settingsAndBuilderDoNotStartRuntimeAndVoiceRespectsGlobalBlock() {
        val host = ViewModelProvider(compose.activity)[HostModel::class.java]
        var oldBlock = false
        compose.runOnIdle { oldBlock=host.globalBlocked;host.blockGlobally(true) }
        try {
            compose.onNodeWithTag("settings").performClick()
            compose.onNodeWithTag("settings-screen").assertIsDisplayed()
            compose.onNodeWithText("ChatGPT account").assertIsDisplayed()
            compose.runOnIdle { assertFalse(host.projectOpen);assertNull(host.isolatedUid) }
            compose.onNodeWithTag("back").performClick()
            compose.onNodeWithTag("create-app").performClick()
            val builder = ViewModelProvider(compose.activity)[app.pub.builder.BuilderModel::class.java]
            compose.waitUntil(30000) { builder.busy == null }
            compose.onNodeWithTag("app-instructions").performTextInput("A garden watering checklist")
            compose.onNodeWithText("Speak your idea").performScrollTo().assertIsNotEnabled()
            compose.runOnIdle { host.blockGlobally(false) }
            compose.onNodeWithText("Speak your idea").assertIsEnabled()
            compose.onNodeWithText("Continue with ChatGPT").assertDoesNotExist()
            compose.runOnIdle { assertFalse(host.projectOpen);assertNull(host.isolatedUid) }
        } finally { compose.runOnIdle { host.blockGlobally(oldBlock) } }
    }
}

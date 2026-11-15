package app.pub

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pub.ui.VoicePromptButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Compose result callback without launching a microphone or speech network. */
@RunWith(AndroidJUnit4::class)
class VoiceInputUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private class SpeechResults : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        var launchedIntent: Intent? = null
        var resultCode = Activity.RESULT_OK
        var spokenText = "with large note labels"
        var holdResult = false
        private var pendingRequest: Int? = null

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) {
            launchedIntent = input as Intent
            pendingRequest = requestCode
            if (!holdResult) Handler(Looper.getMainLooper()).post { deliver() }
        }

        fun deliver() {
            val request = checkNotNull(pendingRequest)
            pendingRequest = null
            val result = if (resultCode == Activity.RESULT_OK) Intent().putStringArrayListExtra(
                RecognizerIntent.EXTRA_RESULTS, arrayListOf(spokenText)
            ) else null
            dispatchResult(request, resultCode, result)
        }
    }

    @Test fun recognizedSpeechAppendsToEditableDraftUsingExpectedIntent() {
        val draft = mutableStateOf("Build a guitar tuner")
        val registry = SpeechResults()
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                    MaterialTheme {
                        Column {
                            VoicePromptButton(draft.value, { draft.value = it }, enabled = true, globallyBlocked = false)
                            Text(draft.value, Modifier.testTag("voice-draft"))
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Speak your idea").performClick()
        compose.onNodeWithTag("voice-draft").assertTextEquals("Build a guitar tuner\nwith large note labels")
        compose.onNodeWithText("Voice text added. Review or edit it before generating.").assertExists()
        compose.runOnIdle {
            val request = checkNotNull(registry.launchedIntent)
            assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, request.action)
            assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, request.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
            assertTrue(request.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
            assertEquals(1, request.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 0))
        }
        // The component only updates this editable draft. No generator or account client is installed here.
    }

    @Test fun cancellationPreservesDraftAndNewGlobalBlockRejectsPendingSpeech() {
        val draft = mutableStateOf("Keep my typed description")
        val blocked = mutableStateOf(false)
        val registry = SpeechResults().apply { resultCode = Activity.RESULT_CANCELED }
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                    MaterialTheme {
                        Column {
                            VoicePromptButton(draft.value, { draft.value = it }, enabled = true, globallyBlocked = blocked.value)
                            Text(draft.value, Modifier.testTag("voice-draft"))
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Speak your idea").performClick()
        compose.onNodeWithText("Voice input cancelled.").assertExists()
        compose.onNodeWithTag("voice-draft").assertTextEquals("Keep my typed description")

        compose.runOnIdle {
            registry.resultCode = Activity.RESULT_OK
            registry.holdResult = true
        }
        compose.onNodeWithText("Speak your idea").performClick()
        compose.runOnIdle { blocked.value = true }
        compose.onNodeWithText("Waiting for voice input…").assertIsNotEnabled()
        compose.runOnIdle { registry.deliver() }
        compose.onNodeWithTag("voice-draft").assertTextEquals("Keep my typed description")
        compose.onNodeWithText("Speak your idea").assertIsNotEnabled()
    }
}

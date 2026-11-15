package app.pub.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration

/** Dictation belongs to the builder, never to the generated project's microphone grant. */
@Composable
fun VoicePromptButton(
    prompt: String,
    onPromptChange: (String) -> Unit,
    enabled: Boolean,
    globallyBlocked: Boolean
) {
    val currentPrompt by rememberUpdatedState(prompt)
    val currentChange by rememberUpdatedState(onPromptChange)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentBlocked by rememberUpdatedState(globallyBlocked)
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    var awaitingResult by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }

    val recognizer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        awaitingResult = false
        when {
            // Recheck policy on return; no stale speech result may bypass a new block.
            currentBlocked -> message = "Voice input is blocked in Settings → Access."
            !currentEnabled -> message = "Voice text was not added while the builder was busy."
            result.resultCode == Activity.RESULT_CANCELED -> message = "Voice input cancelled."
            result.resultCode != Activity.RESULT_OK -> message = "Voice input could not finish. Try again or type your idea."
            else -> {
                val words = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull { it.isNotBlank() }?.trim()
                if (words.isNullOrEmpty()) {
                    message = "No speech was returned. Try again or type your idea."
                } else {
                    val existing = currentPrompt
                    val separator = if (existing.isBlank() || existing.lastOrNull()?.isWhitespace() == true) "" else "\n"
                    val combined = existing + separator + words
                    // Match the builder's existing limit and avoid cutting a UTF-16 surrogate pair.
                    var end = minOf(combined.length, MAX_DESCRIPTION)
                    if (end > 0 && end < combined.length && combined[end - 1].isHighSurrogate()) end--
                    currentChange(combined.substring(0, end))
                    message = if (combined.length > MAX_DESCRIPTION) "Voice text added up to the description limit. Review it before generating."
                    else "Voice text added. Review or edit it before generating."
                }
            }
        }
    }

    Column {
        OutlinedButton(
            enabled = enabled && !globallyBlocked && !awaitingResult && prompt.length < MAX_DESCRIPTION,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                message = null
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Describe the app you want to build")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                try {
                    awaitingResult = true
                    recognizer.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    awaitingResult = false
                    message = "No speech service is available. Enable one in Android settings, or type your idea."
                } catch (_: SecurityException) {
                    awaitingResult = false
                    message = "Your speech service could not access the microphone. Check its Android permissions or type your idea."
                } catch (_: RuntimeException) {
                    awaitingResult = false
                    message = "Voice input could not open. Try again or type your idea."
                }
            }
        ) {
            Text(if (awaitingResult) "Waiting for voice input…" else "Speak your idea")
        }
        val status = when {
            globallyBlocked -> "Voice input is blocked in Settings → Access."
            prompt.length >= MAX_DESCRIPTION -> "Your description has reached its 8,000-character limit."
            else -> null
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

}

private const val MAX_DESCRIPTION = 8000

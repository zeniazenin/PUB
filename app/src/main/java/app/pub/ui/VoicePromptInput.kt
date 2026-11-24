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
import androidx.compose.runtime.remember
import android.widget.Toast
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import java.util.concurrent.atomic.AtomicReference

/**
 * Dictation belongs to the builder, never to the generated project's microphone grant. [label] and [speechPrompt] word
 * the button and the speech service's prompt for a new idea (the default) or for a change to a draft or saved app.
 */
@Composable
fun VoicePromptButton(
    prompt: String,
    onPromptChange: (String) -> Unit,
    enabled: Boolean,
    globallyBlocked: Boolean,
    label: String = "Speak your idea",
    speechPrompt: String = "Describe the app you want to build",
    addedMessage: String = "Voice text added. Review or edit it before generating."
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
                    val combined = joinPrompt(currentPrompt, words)
                    currentChange(clipPrompt(combined))
                    message = if (combined.length > MAX_DESCRIPTION) "Voice text added up to the description limit. Review it before generating."
                    else addedMessage
                }
            }
        }
    }

    Column {
        OutlinedButton(
            enabled = enabled && !globallyBlocked && !awaitingResult && prompt.length < MAX_DESCRIPTION,
            modifier = Modifier.fillMaxWidth().testTag("voice-input"),
            onClick = {
                message = null
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, speechPrompt)
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
            Text(if (awaitingResult) "Waiting for voice input…" else label)
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

/** [addition] after [existing], on a new line unless [existing] is empty or already ends in whitespace. Not clipped. */
internal fun joinPrompt(existing: String, addition: String): String {
    val separator = if (existing.isBlank() || existing.lastOrNull()?.isWhitespace() == true) "" else "\n"
    return existing + separator + addition
}

/** [text] cut to the builder's 8,000-character limit, without splitting a UTF-16 surrogate pair. */
internal fun clipPrompt(text: String): String {
    var end = minOf(text.length, MAX_DESCRIPTION)
    if (end > 0 && end < text.length && text[end - 1].isHighSurrogate()) end--
    return text.substring(0, end)
}

/**
 * Dictation for a running app's inputs ([Dictation]): the same system speech service as the builder's voice input,
 * offline where the phone supports it. PUB never records audio and the app only receives the recognized text, so no
 * microphone permission is involved. Returns null while voice input is blocked in Settings → Access; a result that
 * arrives after a block is dropped.
 */
@Composable
fun rememberAppDictation(blocked: Boolean): Dictation? {
    val context = LocalContext.current
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    val currentBlocked by rememberUpdatedState(blocked)
    val pending = remember { AtomicReference<((String) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val deliver = pending.getAndSet(null)
        if (currentBlocked || result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull { it.isNotBlank() }?.trim()
            ?.let { words -> deliver?.invoke(words) }
    }
    val dictation = remember(launcher, language) {
        Dictation { label, onText ->
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PROMPT, label.take(80))
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            pending.set(onText)
            val failure = try { launcher.launch(intent); null }
                catch (_: ActivityNotFoundException) { "No speech service is available. Enable one in Android settings, or type instead." }
                catch (_: RuntimeException) { "Voice input could not open. Try again or type instead." }
            if (failure != null) { pending.set(null); Toast.makeText(context, failure, Toast.LENGTH_LONG).show() }
        }
    }
    return if (blocked) null else dictation
}

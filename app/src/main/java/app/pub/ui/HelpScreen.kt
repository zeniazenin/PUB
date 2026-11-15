package app.pub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.pub.HostModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HelpScreen(host: HostModel) {
    var contextOpen by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().testTag("help-screen").verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        HelpSection("Build your first app",
            "1. Connect your ChatGPT account in Settings. Automatic model selection prefers the newest available Astra; you can choose another available model.",
            "2. Choose Create app from the launcher. Type your idea or tap Speak your idea. Describe the task, inputs, results and any data you want the app to remember.",
            "3. Review the description, then tap Generate app. Building needs an internet connection and uses eligible ChatGPT plan usage.",
            "4. Review the generated app’s requested access and tap Preview app. Try its controls and check its results before choosing Save locally. Closing an unsaved preview discards its temporary data; you can preview the draft again.",
            "5. Open saved apps from their launcher icons. Tap ✕ Close or use Android Back to return to the launcher. Long-press any launcher app icon for Edit app or Delete app. Edit sends the current app code with your requested changes, then lets you preview and save an update to the same app. Existing saved data and photos are retained; changes made while testing an edit preview are discarded. Delete asks for confirmation and removes the app and its saved data. The bundled tuner can also be edited or removed from the launcher.")
        HelpSection("What you can build today",
            "Small tools with text, inputs, buttons, choices, photos, illustrations, interactive diagrams and a pitch meter: calculators, checklists, tuners, photo notes and simple sensor displays. Saved apps run locally without another model request and share your Style accent.",
            "Apps can save their own small amount of local data when built with storage support. Hardware features depend on your phone, Android permissions and the access you allow each app. Describe the feature you need when building; existing apps do not gain new controls automatically.")
        HelpSection("Build while doing something else",
            "Tap Generate, then switch apps or return to the launcher. PUB keeps a visible build notification while it generates, captures a private preview, and completes automatic visual repairs. The preview contains only the generated app, never another app or your phone screen.",
            "Allow notifications to receive a completion alert. Tap it, or choose View build result on PUB's launcher, to review and save the draft. If notifications are disabled, building still works; return to PUB to check the result. Cancel from the progress notification or builder screen.",
            "One build runs at a time. Completed drafts and edit baselines are saved privately before completion is announced. Android force-stop, reboot, process termination, network failure or time limits can interrupt work. PUB keeps the last completed draft and reports interruption without silently repeating model requests. An unsent description may still be lost if Android closes PUB.")
        HelpSection("Each app’s construction history",
            "Long-press an app icon, choose Edit app, then Prompt history. This shows only that app’s prompts, model, build outcomes and visual reviews. A new app has its own draft history; saving attaches its earlier attempts to that app.",
            "The latest 200 requests per app or draft session stay privately on this phone. If the combined history reaches its storage limit, PUB preserves it and reports that storage is full. Older prompts from before history was added cannot be recovered. History is not automatically sent to the builder; edits use the current app code or latest draft plus your instruction.")
        HelpSection("Automatic visual review",
            "After building, PUB runs a clean copy with empty storage and no hardware access, renders its initial screen at your phone’s size, and captures only the generated app viewport. It sends that screenshot, the submitted request and generated code to your selected ChatGPT model to check layout and visible requirements. Saved app data, captured photos and PUB account controls are excluded.",
            "The review covers the initial visible screen. It cannot prove scrolling content, button behavior, live microphone results or other hardware behavior. Preview and test those yourself. PUB automatically fixes concrete visible issues and takes a fresh screenshot to check each revision. It allows up to two repair attempts per build, then clearly reports any remaining issues. Optional redesign suggestions and behavior that cannot be seen in the screenshot do not trigger repairs.",
            "Screenshot review requires image-input support from your ChatGPT connection and uses plan usage. If unavailable, PUB explains the failure and keeps the draft for manual preview. Screenshot bytes are temporary and are not added to your photo library; review findings and each automatic repair request remain in that app’s history. You can cancel the loop at any time. A failed repair keeps the latest valid draft; saving is still your choice.")
        HelpSection("Graphics, illustrations and icons",
            "Ask for real diagrams and icons. PUB can generate native vector shapes with separately highlightable parts, such as individual guitar strings, and PNG illustrations using the connected ChatGPT account. Use vectors for diagrams that react to app state; generated PNGs are static images.",
            "The model can specify a launcher icon and up to eight graphical assets, including two generated PNGs. Assets are saved locally with the app and work offline. Image generation depends on account/model access and consumes generation usage; PUB reports a failure if the image tool is unavailable instead of substituting ASCII art. Existing apps need a new edit to use these components.",
            "For a tuner, the highlighted string is inferred from pitch. Matching frequencies cannot prove which physical string was plucked. Highlights clear when the signal becomes unreliable or listening stops.")
        HelpSection("Camera, microphone and location",
            "Camera access opens PUB’s camera screen. Choose an Android-exposed camera or physical lens, adjust supported resolution, JPEG quality, zoom, flash, exposure, focus and white balance, then tap Capture photo. Still photos stay inside that app’s private storage and can appear in its interface. Photos retain their captured pixel dimensions. Highest available resolution is the default; camera controls offer Compact (70), Balanced (85), High (95) and Maximum (100) JPEG quality. The quality choice is remembered across captures. Higher quality uses more storage and does not change resolution. Each app can keep 20 photos within 1 GiB, up to 256 MiB per photo. Personal metadata is removed; orientation and color information are kept for correct display. Some lens/resolution combinations and manufacturer-only modes are unavailable. Video, raw camera frames and text recognition (OCR) are not available.",
            "Microphone access supplies live pitch measurements for tools such as tuners. Audio is processed on the phone, not recorded or uploaded by PUB. Generated apps do not receive recordings or speech transcription.",
            "Location supports a current fix or temporary updates while PUB is in the foreground. Android’s approximate location choice is respected; precise GPS requires precise permission and a usable signal. Location may be unavailable indoors or when Android location is off.")
        HelpSection("Hardware inventory",
            "Settings → Hardware inventory detects cameras and lens routes, all Android-reported sensors (including vendor sensor types), hardware features, displays, audio endpoints and available accessory metadata. Expand each section for details and refresh after connecting devices.",
            "Detection does not grant access or mean that PUB can operate a feature. The inventory distinguishes supported adapters from detected-only hardware. Android can hide manufacturer-only hardware and controls. Accessory labels stay local; generation receives a bounded summary without readings or accessory names." )
        HelpSection("Sensors and phone controls",
            "Apps can use available motion and orientation sensors, light, proximity, pressure, temperature, humidity and step sensors. Many phones do not include every sensor; step access also needs Android’s physical activity permission. Live readings stop when access ends.",
            "Vibration, flashlight, battery status and infrared transmission are available where supported. Infrared requires an IR emitter and the correct device codes. Raw health/body sensors and biometrics are not exposed.")
        HelpSection("Bluetooth, sound and accessories",
            "For Bluetooth data, PUB shows a native device chooser. Apps can use a selected BLE device’s GATT services or a paired Classic Bluetooth device’s RFCOMM service. The app needs the accessory’s supported protocol and service identifiers; a Bluetooth connection alone cannot make every device controllable.",
            "Audio controls can select compatible routes, play tones and use an installed offline text-to-speech voice. Connect or pair your headphones in Android first. Available input and output routes depend on Android and the accessory; an offline voice may need installing.",
            "Galaxy Buds can serve as an Android audio route when available. Proprietary functions such as active noise cancellation, ambient mode or vendor settings are not provided by PUB. The same limitation applies to unsupported vendor protocols for watches and other smart devices.",
            "NFC reads NDEF records from a tag while PUB’s reader screen is open; writing tags and card emulation are unavailable. USB host access uses PUB’s device chooser, Android’s device permission and a selected device’s bulk-transfer interface. A compatible cable, host support and the accessory’s protocol are required; PUB is not a general USB driver.")
        HelpSection("Voice instructions",
            "Speak your idea uses your phone’s speech service, which may process audio online. PUB requests offline recognition when available, but the provider decides whether it can fulfill that request.",
            "PUB receives the recognized text and appends it to your description. Review or edit it before tapping Generate. Dictation alone does not send an app-generation request to OpenAI.",
            "If voice input is unavailable, enable a speech service in Android settings or type your description. Settings → Access → Block microphone everywhere also blocks builder dictation.")
        HelpSection("Permissions and local data",
            "Settings → Access provides global blocks, default rules and per-app rules for supported capabilities. A global block takes priority over app permission. Android permissions and device-specific approval are also required where applicable. The microphone block includes voice input for building apps.",
            "Apps can request only the capabilities they declare. Camera capture and device selection use PUB’s own controls. A session-only grant is temporary; a saved per-app rule remains until you change it. You can revoke access in PUB or Android settings.",
            "Closing an app, leaving PUB or revoking access stops its hardware activity and connections. Disconnecting an accessory ends that connection; select it again when needed. There is no background capture, location tracking or accessory control.",
            "Each app’s saved state and photos stay in its own local storage. Apps may save readings if built to do so. Saving a preview preserves its local data and photos; closing an unsaved preview discards them. Deleting an app removes its local state and photos. Sending commands or bytes to a chosen Bluetooth or USB device communicates with that device.")
        HelpSection("What is sent to OpenAI",
            "When you tap Generate, PUB sends your description, app-building instructions and context about your phone and preferences: manufacturer and model, Android version, screen and text size, language, theme, accent, hardware availability, globally blocked capabilities and supported PUB features.",
            "Hardware context describes availability, not live sensor readings, GPS coordinates, photos, recordings, Bluetooth device names or saved app data. PUB does not automatically attach unique device identifiers. Anything you type or dictate into the description is included when you generate.",
            "Connecting ChatGPT and loading models also require internet access. Disconnecting your ChatGPT account stops using that connection for building; saved apps still run locally.")
        HelpSection("Current limits",
            "Generated apps cannot use general internet requests, Wi-Fi controls, arbitrary phone files, notifications, scheduled tasks, background services, raw health data or biometrics. Camera and accessory support is limited to the features described above; hardware presence does not guarantee protocol support.",
            "Generated apps use PUB’s supported controls and capabilities. Test their behavior, calculations and accessory commands before relying on them. Unsaved descriptions and drafts may be lost if Android closes PUB.")
        TextButton(onClick = { contextOpen = !contextOpen }, modifier = Modifier.testTag("help-context")) {
            Text(if (contextOpen) "Hide phone context" else "View current phone context")
        }
        if (contextOpen) {
            // Building the context scans the hardware inventory; never do that during composition.
            val context by produceState<String?>(null) { value = withContext(Dispatchers.IO) { runCatching { host.generationContext() }.getOrNull() } }
            SelectionContainer {
                Text(context ?: "Reading phone context…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun HelpSection(title: String, vararg paragraphs: String) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    HorizontalDivider()
}

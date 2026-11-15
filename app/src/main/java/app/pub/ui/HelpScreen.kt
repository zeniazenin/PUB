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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.pub.BuildConfig
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
            "3. Review the description, then tap Generate app. PUB first researches similar apps and best practices, plans the screens, then builds. Building needs an internet connection and uses eligible ChatGPT plan usage.",
            "4. Review the generated app’s requested access and tap Preview app. Try its controls and check its results before choosing Save locally. Closing an unsaved preview discards its temporary data; you can preview the draft again.",
            "5. Open saved apps from their launcher icons. Tap ✕ Close or use Android Back to return to the launcher. Long-press any launcher app icon for Edit app or Delete app. Edit sends the current app code, the app’s saved research and behavior checks and your requested changes, then lets you preview and save an update to the same app. Existing saved data, files and photos are retained; changes made while testing an edit preview are discarded. Delete asks for confirmation and removes the app and its saved data.",
            "A new install starts with one sample app, Tip Calculator, copied into Your apps on first launch. It is an ordinary saved app: open, edit or delete it like any other. Deleting it is permanent; it is not added again. Upgrades and installs that already have apps don’t get it.")
        HelpSection("What you can build today",
            "Small tools with text, inputs, buttons, choices, photos, illustrations, interactive diagrams and a pitch meter: calculators, checklists, tuners, photo notes and simple sensor displays. Saved apps run locally without another model request and share your Style accent.",
            "Apps can save their own small amount of local data when built with storage support, keep files in a private folder, and open or save files you choose on your phone. Hardware features depend on your phone, Android permissions and the access you allow each app. Describe the feature you need when building; existing apps do not gain new controls automatically.")
        HelpSection("Each app’s private files",
            "Apps built with file support get their own private folder on your phone for notes, documents, logs, collections and other files. An app can create folders and read, write, rename, move, copy and delete its files there, without asking each time. The folder holds up to 256 MiB and 5,000 files and folders per app, in addition to its photos.",
            "The folder also lists the app’s camera photos in a read-only photos folder, so a photo app finds its pictures again when you reopen it. The app can delete or copy its photos, but not change them.",
            "Only that app can see its folder: not other PUB apps, other Android apps or the Files app, and nothing is uploaded. An unsaved preview’s files are temporary: Save locally keeps them and closing discards them. An edit preview starts with a copy of the app’s files; Save changes keeps the saved app’s files and discards the preview’s changes. Deleting an app deletes its folder.")
        HelpSection("Files elsewhere on your phone",
            "Apps built with phone file support can open and save files you choose, with your permission. When you tap a button such as Open file, Open folder or Save as, Android’s own file picker opens. You can pick a file, several files, a folder, or a place and name for a new document, in your phone’s storage, Downloads or apps that offer their documents to the picker, such as Drive. The first time in a session PUB asks whether the app may use files on your phone.",
            "The app can use only what you pick: it sees the names, sizes and types of those files and of what is inside a picked folder, never where they are stored, and it cannot browse your phone, look into other folders or read other apps’ private data. PUB itself has no permission to your storage. Within what you picked, the app can read, change, create, rename and delete, and copy files into or out of its private folder. Deleting always starts from a tap in the app. Online-only documents such as Google Docs files can’t be opened.",
            "A saved app keeps access to what you picked, also after a restart, until the app gives it up, you choose Settings → Access → Files on this phone → Forget file access for that app, or you delete the app. Rules in Settings → Access can also block or allow file access per app or for all apps. A preview’s access ends when you close it and is not kept when you save the app, so the saved app asks you to pick again. An edit preview can read the files the saved app was given but not change them.",
            "Your files are never deleted when you delete an app or close a preview; only the app’s access to them ends. If you remove access elsewhere (for example by deleting the file in another app), the app is told and can ask you to pick again.")
        HelpSection("Research before building",
            "Before every new app or edit, PUB asks your selected ChatGPT model to research comparable apps, the features people expect, common complaints, domain rules such as formulas and reference values, and Android design guidance. It then fits the findings to what PUB apps can actually do and passes them to the builder as a plan.",
            "When your ChatGPT connection allows it, the model uses web search. The searches are performed by OpenAI's web search, not by PUB or your browser. Research receives only your description and, for an edit, the app's name, description and saved research: never your phone details, hardware, app code, saved data or photos.",
            "The Research card shows whether the web was used, the planned features, what PUB can't do, the assumptions made, and the sources as links that open in your browser. Without web search, research uses the model's own knowledge and says so. If research fails, the build continues from your description alone. Research uses plan usage and can be cancelled with the build.")
        HelpSection("Planning the screens",
            "After research, PUB asks the model to plan the app's interface before it writes any code: which screens the app has and how you move between them, what you see first without scrolling on your phone, the inputs, buttons and wording on each screen, and what happens when a list is empty, a file is loading or something goes wrong.",
            "The plan uses only what PUB apps can show and is sized for your screen and font size. The builder follows it, and the automatic screen check compares the first screen with it. The UI plan card in the builder shows it; edits update the same plan, so screens you did not ask to change stay as they are.")
        HelpSection("Build while doing something else",
            "Tap Generate, then switch apps or return to the launcher. PUB keeps a visible build notification while it researches, plans the screens, generates, tests the app’s behavior, captures a private preview and completes automatic repairs. The preview contains only the generated app, never another app or your phone screen.",
            "Allow notifications to receive a completion alert. Tap it, or choose View build result on PUB's launcher, to review and save the draft. If notifications are disabled, building still works; return to PUB to check the result. Cancel from the progress notification or builder screen.",
            "One build runs at a time. Completed drafts and edit baselines are saved privately before completion is announced. Android force-stop, reboot, process termination, network failure or time limits can interrupt work. PUB keeps the last completed draft and reports interruption without silently repeating model requests. An unsent description may still be lost if Android closes PUB.")
        HelpSection("Each app’s construction history",
            "Long-press an app icon, choose Edit app, then Prompt history. This shows only that app’s prompts, model, build outcomes, how each request was researched, how many behavior checks passed, and visual reviews. A new app has its own draft history; saving attaches its earlier attempts to that app.",
            "The latest 200 requests per app or draft session stay privately on this phone. If the combined history reaches its storage limit, PUB preserves it and reports that storage is full. Older prompts from before history was added cannot be recovered. History is not sent to the builder; edits use the current app code or latest draft, the app’s saved research and checks, and your instruction.")
        HelpSection("Edits build on the app’s saved plan",
            "When you save an app, PUB keeps its original request and the research and behavior checks of that build in a private file on your phone, separate from the app’s data. When you later edit the app, research starts from that saved research and returns a complete updated plan for the changed app, the builder receives the original request and the saved checks and returns an updated set (keeping the checks that still apply and adding checks for the change), and the visual review judges the result against the full updated plan. The builder shows “Uses this app’s saved research and N behavior checks” when this applies.",
            "Saving an edit keeps the original request and replaces the research and checks with the new build’s. Apps saved before this feature start without them until their next saved edit. The saved plan never includes the app’s saved data, photos or phone details, is sent to ChatGPT only with an edit of that app, and is deleted with the app. If it can’t be read, PUB leaves it untouched and the edit researches only the change.")
        HelpSection("Automatic behavior checks",
            "Along with the app, the builder writes a few behavior checks, such as “enter a bill of 100 and choose 15% → Total: 115.00”, based on the research’s acceptance checks. PUB runs each check on your phone in a fresh, isolated copy of the app with empty storage and no hardware, taps and types the steps, and checks the text that appears. Nothing the app tries to save or access is carried out.",
            "If a check fails, PUB automatically asks for a fix and runs the checks again before the visual review. Behavior and visual fixes share the limit of two repair attempts per build. The draft shows how many checks passed and, under Show details, what failed. Checks are written by the same model as the app and cannot test hardware, permissions, saved data or timing, so a pass is not a guarantee: preview and test the app yourself before saving. When you edit a saved app, its saved checks are carried over and updated for the change.")
        HelpSection("Automatic visual review",
            "After building, PUB runs a clean copy with empty storage and no hardware access, renders its initial screen at your phone’s size, and captures only the generated app viewport. It sends that screenshot, the submitted request, the research findings and generated code to your selected ChatGPT model to check layout and visible requirements. Saved app data, files, captured photos and PUB account controls are excluded.",
            "The review covers the initial visible screen. It cannot prove scrolling content, button behavior, live microphone results or other hardware behavior. Preview and test those yourself. PUB automatically fixes concrete visible issues and takes a fresh screenshot to check each revision. It allows up to two repair attempts per build, shared with behavior fixes, then clearly reports any remaining issues. Optional redesign suggestions and behavior that cannot be seen in the screenshot do not trigger repairs.",
            "Screenshot review requires image-input support from your ChatGPT connection and uses plan usage. If unavailable, PUB explains the failure and keeps the draft for manual preview. Screenshot bytes are temporary and are not added to your photo library; review findings and each automatic repair request remain in that app’s history. You can cancel the loop at any time. A failed repair keeps the latest valid draft; saving is still your choice.")
        HelpSection("Graphics, illustrations and icons",
            "Ask for real diagrams and icons. PUB can generate native vector shapes with separately highlightable parts, such as individual guitar strings, and PNG illustrations using the connected ChatGPT account. Use vectors for diagrams that react to app state; generated PNGs are static images.",
            "Every app gets a launcher icon: a simple pictogram of what the app is for, designed in a short separate step of the build and drawn in the same outline style and theme colours as every other icon. Edits keep the icon; an app built before icons existed gets one with its next edit and shows a neutral app symbol until then. Apps can also use up to eight graphical assets, including two generated PNGs. Assets are saved locally with the app and work offline. Image generation depends on account/model access and consumes generation usage; PUB reports a failure if the image tool is unavailable instead of substituting ASCII art. Existing apps need a new edit to use these components.",
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
            "If voice input is unavailable, enable a speech service in Android settings or type your description. Settings → Access → Block microphone everywhere also blocks builder dictation.",
            "Apps can offer the same dictation in their text fields, shown as a microphone in the field. The recognized text is added as if you typed it; the app never receives audio and needs no microphone permission. The microphone block in Settings → Access turns it off for every app.")
        HelpSection("Permissions and local data",
            "Settings → Access provides global blocks, default rules and per-app rules for supported capabilities. A global block takes priority over app permission. Android permissions and device-specific approval are also required where applicable. The microphone block includes voice input for building apps.",
            "Apps can request only the capabilities they declare. Camera capture and device selection use PUB’s own controls; choosing files uses Android’s file picker. A session-only grant is temporary; a saved per-app rule remains until you change it. You can revoke access in PUB or Android settings.",
            "Closing an app, leaving PUB or revoking access stops its hardware activity and connections. Disconnecting an accessory ends that connection; select it again when needed. There is no background capture, location tracking or accessory control.",
            "Each app’s saved state, files and photos stay in its own local storage. Apps may save readings if built to do so. Saving a preview preserves its local data, files and photos; closing an unsaved preview discards them. Deleting an app removes its local state, files, photos and saved research and checks, and ends its access to files you picked elsewhere on the phone without deleting them. Sending commands or bytes to a chosen Bluetooth or USB device communicates with that device.")
        HelpSection("What is sent to OpenAI",
            "When you tap Generate, PUB sends your description, app-building instructions and context about your phone and preferences: manufacturer and model, Android version, screen and text size, language, theme, accent, hardware availability, globally blocked capabilities and supported PUB features.",
            "Hardware context describes availability, not live sensor readings, GPS coordinates, photos, recordings, Bluetooth device names, saved app data or files, including files you picked for an app. PUB does not automatically attach unique device identifiers. Anything you type or dictate into the description is included when you generate.",
            "Before generating, PUB sends a separate research request with only your description (and, for edits, the app's name, description and saved research). With web search, OpenAI performs the searches; the sources it used are shown in the builder. When you edit an app, generation also receives the current app code and the app’s original request and saved behavior checks.",
            "Connecting ChatGPT and loading models also require internet access. Disconnecting your ChatGPT account stops using that connection for building; saved apps still run locally.")
        val context = LocalContext.current
        TextButton(onClick = { openInBrowser(context, BuildConfig.PRIVACY_POLICY_URL) }, modifier = Modifier.testTag("help-privacy-policy")) {
            Text("Read the privacy policy")
        }
        HorizontalDivider()
        HelpSection("Reporting content",
            "If a build, its research or a saved app shows something offensive, sexual, dangerous or misleading, report it from inside PUB: Report content on the build result, Report research on the Research card, or long-press an app icon and choose Report app.",
            "Choose a reason, add details if you like, and choose whether to include the app’s name, description and code, or the research summary and sources. Reports go to PUB’s developer, not to OpenAI, and never include your phone details, account, saved data, files or photos.")
        HelpSection("Current limits",
            "Generated apps cannot use general internet requests, Wi-Fi controls, files you have not picked for them, notifications, scheduled tasks, background services, raw health data or biometrics. Camera and accessory support is limited to the features described above; hardware presence does not guarantee protocol support.",
            "Generated apps use PUB’s supported controls and capabilities. Test their behavior, calculations and accessory commands before relying on them. An unsent description may be lost if Android closes PUB; build drafts are kept until you save or discard them.")
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

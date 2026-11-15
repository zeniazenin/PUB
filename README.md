# PUB — Personal Utility Builder

Android host for small, locally stored JavaScript apps. Version 0.7.0 adds background building, completion notifications and durable build drafts to the launcher, ChatGPT builder, automatic visual repair and native hardware capabilities. The bundled guitar tuner runs offline with native pitch detection.

## Use

1. PUB opens to **Your apps**, with icons for the built-in Guitar Tuner and locally saved apps. No project starts automatically. Long-press any app tile for **Edit app** or **Delete app**, including the bundled tuner.
2. Tap an icon to open its full-screen interface. The pinned **✕ Close** button or Android Back stops its hardware sessions, shuts down its runtime and returns to the launcher.
3. Use **Settings** in the top-right for your ChatGPT connection, model preference, Style, global/per-app Access, and deleting saved apps. Changing a permission does not run that app.
4. In Settings, **Continue with ChatGPT**, complete browser consent and return to PUB. Automatic model selection prefers the newest Astra returned by your account; if none is available it uses the first catalog model. An explicit model choice is saved, and Automatic restores the default.
5. Tap **+ Create app**. Type your instructions or use **Speak your idea**. Dictation appends editable text and never generates automatically. Android's speech service may process audio online even though PUB requests offline recognition when available. PUB receives text, not a recording. The global microphone block also disables dictation.
6. Tap **Generate app**. You can switch apps or turn off the screen while PUB generates and visually reviews the draft. Tap the completion notification (or **View build result**) to return, review requested capabilities, and open **Preview app**. Test its controls, then **Save locally**. Closing an unsaved preview returns to the builder and discards its temporary app data; the generated draft remains available to preview again.
7. Saved apps run locally and retain their private state. Delete from Settings removes the package, state and permission override. The built-in tuner can also be removed from the launcher.
No API key is used. ChatGPT authorization/plan eligibility and available models are controlled by OpenAI. One connected account is supported; disconnect before switching accounts. Cancel stops the current sign-in or generation. Drafts are in memory until saved; restarting Android/PUB during sign-in requires a fresh attempt.

## Data and boundaries

- Generation sends the user's app description, phone manufacturer/model, Android version, API level, screen dimensions, font scale, locale, theme, hardware availability and PUB's supported runtime capabilities to OpenAI. Settings → Help & guide includes the exact phone context. It does not automatically include unique hardware identifiers, GPS coordinates, recordings, photos, saved app data, Bluetooth device names or credentials. Text the user puts in the description is sent when generating.
- The host has INTERNET permission for ChatGPT. Generated JavaScript still runs under a separate Android isolated UID with **no internet permission**, Java/Android bridge, DOM, or arbitrary filesystem access.
- Supported hardware: camera still photos, foreground location, available standard sensors, BLE GATT and paired RFCOMM, headphone/audio routing, short tones/offline speech, haptics, torch, battery, read-only NFC NDEF, chosen USB bulk endpoints and infrared where physically available. `microphone.pitch` still sends only pitch/confidence/level. See `docs/hardware-runtime.md` for scope and limitations.
- Settings → Hardware inventory lists Android-exposed cameras, sensors, features, displays and accessory metadata, with detected-only hardware distinguished from PUB-supported adapters. Public metadata summaries inform generation without accessory names or readings.
- Settings → Hardware check opens a local test app through the same isolated runtime and permission broker as generated apps. No model call is needed.
- Native UI components: column, text, button, choices, input, scoped captured images, native vector/generated-PNG graphics (four photos/graphics combined per screen), and guitar tuning meter. All inherit the host theme. Each dispatch is bounded; the native process watchdog also covers interpreter stalls.
- `storage.project` permits at most 8 KiB of JSON state per app. Preview data is temporary until saved. Host-generated UUIDs identify files; model-provided names never become paths.
- Every hardware group has global block/default and per-project rules enforced by the native host. New hardware access starts from a user action; native camera/device pickers cannot be opened by script callbacks. Switching apps, backgrounding, revocation and closing stop hardware sessions and invalidate queued results. Preview grants are session-only. Android permissions and explicit device selection remain additional requirements.
- Photos are private JPEG assets scoped to the host project identity: full captured resolution, remembered JPEG quality, maximum 20 photos / 1 GiB per project, 256 MiB each. Saving streams the JPEG without resizing or recompressing; only display previews are sampled. Personal metadata is removed, with orientation and color profiles retained. Previews are temporary; save copies their assets, close removes them, and startup removes abandoned temporary captures.
- ChatGPT tokens are encrypted using AES-GCM with a non-exportable Android Keystore key, atomically stored in the host's no-backup directory. Tokens never enter prompts, projects, script events, logs, or source control.
- Disconnect revokes the renewable session and clears local tokens. If remote revocation fails, PUB reports it and directs the user to ChatGPT Settings. Saved apps are retained.

## Build and test

Requirements: JDK 17, Android SDK 35/build-tools 35.0.0. Android 12/API 31 minimum. Gradle 8.11.1, AGP 8.9.2, Kotlin 2.1.20. On this prepared machine:

```powershell
.\tools\dev.ps1 :core:test :app:assembleDebug :app:lintDebug
.\tools\dev.ps1 :app:assembleDebugAndroidTest :app:installDebug
```

With your own Android toolchain, use `gradlew.bat` and set `JAVA_HOME`, `ANDROID_HOME`, and `local.properties`. The local `.toolchain` directory and credentials are ignored by Git.

APK: `app/build/outputs/apk/debug/app-debug.apk`.

```powershell
.\.toolchain\android-sdk\platform-tools\adb.exe install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
.\.toolchain\android-sdk\platform-tools\adb.exe shell am instrument -w -r app.pub.test/androidx.test.runner.AndroidJUnitRunner
```

Unlock the phone and keep PUB foreground for microphone instrumentation. Default tests use synthetic local project fixtures. Explicitly opted-in live ChatGPT tests use the connected account and submit generation/review requests. The credential-storage test uses a separate test file. See `docs/chatgpt-builder.md` for the protocol and validation status; `docs/architecture.md` describes the original runtime proof.

## Layout

- `core`: isolated JS engine, UI/package validation, pitch detection, permission policy, OAuth/identity and response-stream validation.
- `app/auth`: ChatGPT OAuth, encrypted session storage, refresh, revocation, model catalog and Responses stream.
- `app/builder`: generation prompt/contract and cancellable account/generation state.
- `app/projects`: atomic local package library.
- `app/runtime`: isolated service and bounded Binder protocol.
- `app/hardware`: capability broker, native adapters, camera/media storage and Hardware check.
- `app/permissions`, `app/audio`, `app/ui`: native broker, microphone processing, Compose renderer.
- `app/assets/projects/guitar-tuner`: bundled tuner package, copied to private storage on first launch.

## Remaining limits

Generated apps are limited to PUB's component/effect contract, not arbitrary Android apps. Video, raw camera frames, OCR, private/health sensors, biometric templates, Wi-Fi configuration, arbitrary USB drivers, NFC writes/payments, proprietary accessory protocols, scheduled/background work inside generated apps, package sharing and general networking are not implemented. Builder generation/review can run in the background; submitted build drafts are checkpointed, but idle unsent prompt changes are not autosaved. Bluetooth audio routing does not implement Galaxy Buds noise-cancellation controls. Accessory compatibility requires its documented protocol; hardware absence returns an error. The user must test each generated app; a valid initial UI is not a correctness guarantee. OAuth process-death recovery starts a fresh login; it never persists an unfinished authorization code/verifier. Hardware tuner accuracy against a real guitar/reference tuner remains a manual test.

## Launcher editing (0.4.3)

Long-press an icon, choose Edit app, and describe the changes (typing or voice). Generation receives the current app package as context; saved data/photos are not included. Preview uses temporary state initialized from existing saved data and read-only access to that app's existing photo assets. Camera captures and state changes made in preview are discarded. Save changes atomically updates the package under the same app ID, preserves original data/photos/permission rules, and refuses a stale update if the package changed or was deleted. Review requested capabilities before previewing. The bundled tuner supports an editable local package override; deleting it persists its removal from the launcher and clears saved data/rules.

Verification: 57 JVM tests and seven targeted on-phone tests pass. Device checks cover menu accessibility, edit context, delete cancellation/confirmation, preview isolation, same-ID updates, saved data/photos/permissions, launcher and builder regressions. No paid model-generation request was made during verification; tests exercise prompt assembly and use fixture output for the local edit/save flow. Build/lint pass with zero errors.

## Prompt history and graphics (0.5.0)

Launcher → Edit app → Prompt history shows construction history for that app. New unsaved drafts have their own session history, attached to the app on first save. The local store retains the latest 200 submitted requests with timestamp, model, app linkage and outcome. Records are atomic/private/local; earlier unrecorded requests cannot be recovered. Failed/cancelled/interrupted requests remain reviewable. App deletion keeps history; history does not contain generated code, device context, photographs or credentials and is not automatically included in generation.

Packages optionally contain `assets` (up to eight) and `icon` (an asset ID). Assets are strict native vectors or PNG-generation requests. Vector parts have unique IDs; UI graphic nodes can change their `highlight` list without changing the asset. Schematics should use vectors so individual strings/parts can react to state. The bundled tuner now uses a guitar diagram and highlights the nearest confident string by pitch, clearing on silence/stop. A user-edited tuner override is preserved and needs a new edit to use graphics.

The builder invokes the Responses image-generation tool for up to two requested PNG assets through the existing ChatGPT OAuth connection. Images are stored with the local app, decoded at bounded preview sizes and reused on edits when ID/prompt match. Pixel data is omitted from edit prompts. Tool failures refuse the draft instead of silently replacing imagery with ASCII. Tool access is account/model-dependent; no API key is introduced.

Graphics never grant network, files, SVG/HTML execution or arbitrary Android access to JavaScript. The host draws only validated shapes and decodes bounded PNG data. Package storage is bounded to16MB, source remains64KiB, script UI packets remain32KiB. At most two generated PNGs, each4MiB and4MP/2048px per side, fit within a package.

### Device verification and ChatGPT image-tool restriction

On November 5, 2026 the connected ChatGPT subscription-sharing route returned HTTP400 `subscription_sharing_unsupported_capability` for `tools`. This is a confirmed access restriction for this connection, not a successful PNG-generation test. PUB caches this specific restriction, shows it in the builder, and tells the model to use native vectors for diagrams, icons and simple illustrations. It will not repeatedly submit known-unsupported image calls or silently replace PNG requests with ASCII. Reconnecting clears the cached capability state for a new authorization check.

Live text-model generation successfully produced a guitar vector asset, launcher icon and functioning highlight button. Device tests verified the actual rendered pixels change when the string highlight changes. Four history storage tests, one PNG fixture storage test, one live vector-generation test and four UI/edit-regression tests passed (10 distinct passed device tests). PNG live test is explicitly unavailable/skipped after the confirmed rejection. All86 JVM tests and Android build/lint passed. The phone's original stay-awake setting was restored.


## PUB 0.6.0: app history and visual review

Prompt history is scoped to each app via launcher Edit app, with draft-session lineage attached on first save. New builds automatically capture a clean native viewport and request advisory screenshot review through existing ChatGPT OAuth. Effects are disabled and private storage/photos excluded during review. The current connection successfully accepted image input; this is separate from its unavailable image-generation tool. See docs/per-app-history-visual-review.md.

Validation: 86 core tests and 17 distinct device tests passed; Android build/lint passed (zero lint errors).


## PUB 0.6.1: automatic visual repairs and tuner startup

Visual findings now trigger up to two automatic repairs, each followed by a fresh native screenshot review. Strict decisions stop on invalid/unverifiable output; the latest valid draft is preserved. Repair entries stay in per-app history. Every generation receives an explicit runtime/platform brief and actual device/layout/permission constraints. Microphone preparation no longer emits a misleading stopped status that caused saved tuners to stop immediately.

Validation: 94 JVM tests and 21 focused device tests passed, including live ChatGPT repair/recheck with full phone context and sustained real microphone input in the saved tuner. See docs/automatic-visual-repair.md.


## PUB 0.7.0: background building

Generation, native screenshot review and automatic repairs continue when the Activity stops or the screen turns off. A foreground progress notification offers Cancel; completion opens the durable pending draft for preview/save. Private virtual-display rendering never captures other apps. Separate isolated JS service instances prevent background review from disrupting an open PUB app. Force-stop/reboot recovery preserves the last checkpoint without automatically repeating a paid request. See docs/background-builds.md for architecture, behavior and device validation.

Validation: all 94 core tests, Android build/lint (zero errors), and targeted device checks passed, including live ChatGPT background generation, notification navigation, screen-off repair/review, cancellation, recovery and renderer/runtime isolation.

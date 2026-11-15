# PUB 0.3.0 — launcher, voice instructions and Settings

Implemented November 5, 2026 in `C:\Projects\PUB_app`.

## Behavior

- Initial launch shows an adaptive icon grid for the bundled tuner and saved projects. The host does not start an isolated runtime until an icon or Preview is selected.
- Projects occupy the app content area, with a host-owned pinned **✕ Close** control. Android Back closes the project too. Closing shuts down the runtime, stops capture, clears pending permission requests and session grants, and returns to the launcher. Unsaved previews return to the builder with temporary preview data discarded.
- **Settings** is at the upper right of the launcher and builder. It contains ChatGPT connection management, model selection, Style, global/per-app microphone Access, saved-app deletion and context review. Permission edits never require running a project.
- **Create app → Speak your idea** invokes Android's speech-recognition activity. Recognized text is appended to the editable description (maximum 8,000 UTF-16 characters), preserving typed content. Recognition does not submit a generation request. Cancellation preserves the draft, and a newly applied global microphone block rejects a pending result.
- Dictation requests offline recognition as a preference, not a guarantee. The installed Android speech provider may process audio online. PUB receives the transcript; PUB does not record or upload audio to OpenAI. Missing/disabled speech providers produce a recoverable message and leave typing available.
- Automatic model selection chooses the newest numeric Astra version in the connected account's actual catalog. With no Astra it falls back to the first server-listed model. An explicit available selection is saved across launches and catalog refreshes. Selecting Automatic restores Astra preference. No unavailable model ID is invented.

## Compatibility

The package remains `app.pub`, version code 3. Existing locally saved apps, their private state, theme, microphone policies and encrypted ChatGPT credentials are retained during upgrade. No project format migration or sign-in replacement is needed.

## Validation

- Debug application and instrumentation APKs build successfully.
- 48 JVM tests pass, including seven model-selection cases: server-order independence, numeric version ordering, explicit selection and unavailable-model fallbacks.
- Android lint: zero errors, 21 warnings and two informational findings (pinned dependency updates and optional style/resource conventions).
- All nine instrumentation tests passed across the full run and targeted reruns on the connected Samsung SM-S948U running Android 17/API 37. Coverage includes idle startup, native launcher/open/close navigation, preview/save/reopen/delete, per-project permission isolation, closed-session grant revocation, real microphone lifecycle, encrypted storage and speech callback success/cancellation/blocking through an injected ActivityResultRegistry.
- Test-only AndroidX dependencies were updated to Espresso 3.7.0 and runner/rules 1.7.0 for Android 17 input compatibility. The Settings test waits for the asynchronous account catalog before typing.
- Visually inspected the launcher, full-screen saved Tip Calculator, Settings and builder. Existing calculator values and ChatGPT connection survived the update; the connected account selected **Automatic · GPT-6-Astra**.
- The user completed a live speech-provider round trip on the phone and confirmed that recognized voice text appeared in the description box. The separate injected callback tests cover cancellation and policy changes without sending audio to a provider.

## Implementation areas

- `HostModel`: explicit project lifecycle and settings-only permission access.
- `ui/LauncherScreen`, `MainActivity`: icon launcher, navigation and pinned Close control.
- `ui/SettingsScreen`, `ui/BuilderScreen`: separated global controls and creation flow.
- `ui/VoicePromptInput`: Android speech activity/result handling.
- `core/ModelSelection`, `BuilderModel`: account-aware automatic model choice and persisted overrides.

Reference contracts: Android [RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent); OpenAI [model selection](https://developers.openai.com/api/docs/guides/model-selection) and [account model catalog](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference).

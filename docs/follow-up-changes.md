# Follow-up changes by prompt or voice

Implemented November 24, 2026 in `C:\Projects\PUB_app`.

## Behavior

- **Review step.** Once a draft exists, the builder shows its second step, Review (see `docs/design-language.md`): the draft (icon, name, description, and its checks, visual check and access side by side) first, then **Adjust** with a **Request changes** box, and How it was built folded away below. **Try it** and **Continue** (to Publish) are pinned at the bottom.
- **Change box.** Label "What should change?", three lines to start, **Speak a change** and **Apply changes**. The box is the same prompt the builder always had (same `app-instructions` field, 8,000-character limit, same disabled states while a build runs). While a change builds, its progress replaces the box.
- **Builds on the draft.** **Apply changes** runs the normal build with the current draft as the package to change. This was already what `BuilderController.generate()` did whenever a draft existed; the new layout makes it visible. Research updates the draft's brief, the planner updates its UI plan, and the builder receives the draft's code, the original request and the draft's behavior checks, exactly like an edit of a saved app (`EditBasis`).
- **Cleared for the next change.** When a build commits its draft, the box is cleared (`prompt=""`), because the request is now part of the draft. A build that fails or is cancelled before its draft is ready keeps the text for a retry and leaves the previous draft as it was.
- **What you asked for.** Under the box, the requests behind the draft are listed oldest first: "Idea" and "Change 1…n" for a new app, "Change 1…n" for an edit of a saved app. The last four show by default; long requests are cut to three lines, with **Show all**. Automatic visual and behavior repairs are not user requests and are not listed (Prompt history still shows them).
- **Quick fixes.** When the draft's behavior checks still fail, or its latest visual review still asks for fixes, chips add those findings to the box as editable text ("Fix the app so these behavior checks pass. Keep the checks themselves unchanged: …", "Fix these issues from the visual review: …", at most 2,000 characters each). A chip is disabled once its text is in the box. Nothing is sent until **Apply changes**.
- **From the preview.** A builder preview has **Request changes** beside **Save locally** / **Save changes**. It closes the preview (discarding its temporary data, as Close does) and returns to the builder with the change box scrolled into view. The Settings → Hardware check preview does not show it.
- **From a saved app.** An open saved app has **Edit** beside **✕** (Close). It closes the app and opens the builder for it, the same as launcher long-press → Edit app. It is hidden while a build is running, or while the builder holds a build result for a different app (opening the builder would only show that result).
- **Voice.** **Speak a change** uses the same Android speech activity as **Speak your idea** (`VoicePromptButton`, with its own label and speech prompt "Say what to change"). Recognized text is appended to the box and never starts a build, and the Settings → Access microphone block disables it.

## Data and persistence

No new data is sent anywhere. Quick fixes only copy text the builder already shows into the box, and a change request goes to ChatGPT with the same contents as any build on a draft.

The requests behind the draft are kept with the build draft (`files/builder-draft.json`) as `requests`: snapshot version 6, at most 100 requests of at most 8,000 characters each (past 100, the first request and the latest 99 are kept). Version 1–5 snapshots still load; their list starts with what is known: a new app's original request, or an edit's latest change. Builds before this change left the request that built the draft in the box. A restored draft whose box holds exactly that request opens with an empty box, so it doesn't look like a pending change. The list is cleared with the draft (Discard draft, starting a new app or edit, and saving the draft as an app).

The interrupted-build message on restore now depends on whether the request was still in the box. Before the draft was ready it says "Build it again to retry", as before. After it was ready it says "Preview it, retry its review or describe a change".

## Limits

- A change that completes replaces the draft. There is no undo to an earlier version of a draft: describe the reversal as another change, or discard the draft.
- Each change runs the full research, planning, build and check pipeline, like any build on a draft.

## Implementation areas

- `builder/BuilderController`: clears the prompt on commit, keeps `draftRequests`, persists and restores them.
- `builder/BuildDraftStore`: snapshot version 6 with `requests`.
- `ui/BuilderScreen`: refine layout, change box, Quick fixes and the request trail (`DraftCard`, `QuickFixes`, `RequestTrail`, `BuilderAccount`).
- `ui/VoicePromptInput`: label, speech prompt and confirmation text parameters; shared `joinPrompt`/`clipPrompt`.
- `MainActivity`: **Request changes** in builder previews, **Edit** for open saved apps, and bringing the change box into view.

## Validation

- `.\tools\dev.ps1 :core:test :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest` succeeds. The app and the instrumentation APK compile, and lint reports 0 errors (its warnings in the touched files are on unchanged lines). `core` is unchanged, so its 231 tests were up to date and not re-run.
- New device tests:
  - `FollowUpChangesTest` (offline fixtures). Changes build on the current draft and keep their requests; a failed change keeps its text and the draft; Discard starts a new app. A draft saved before this change restores with an empty box and its original request listed. The refine layout and trail appear, **Request changes** returns from the preview with the box text intact, and **Edit** on a saved app opens the builder for it.
  - `BuildDraftStoreTest`: version 6 round trip, version 5 loading and the bounds of `requests`.
- Adjusted for the new layout: `LauncherUiTest` finds the builder's voice button by its `voice-input` tag, since it reads "Speak a change" when PUB holds a draft. `GraphicsUiTest` scrolls to Prompt history, and `LiveBackgroundBuildTest` looks for the refine heading. `LauncherEditingTest` skips its long-press edit test when PUB already holds a build result, which Edit app then opens instead.
- On November 24, 2026, on the Samsung SM-S948U (Android 17), all 35 tests in nine classes were run: `FollowUpChangesTest`, `LauncherUiTest`, `LauncherEditingTest`, `VoiceInputUiTest`, `GraphicsUiTest`, `BuilderDeviceTest`, `AutomaticVisualRepairTest`, `BackgroundBuildTest` and `BuildDraftStoreTest`. 34 passed. `LauncherEditingTest`'s long-press edit test was skipped, because the phone held a real, unsaved build result from an earlier session; it was left untouched. The live ChatGPT tests were not run.
- To run them on a connected phone (unlocked, PUB in the foreground; this phone's 30-second screen timeout can lock it during a run):

```powershell
.\tools\dev.ps1 :app:installDebug :app:installDebugAndroidTest
.\.toolchain\android-sdk\platform-tools\adb.exe shell am instrument -w -r -e class app.pub.FollowUpChangesTest,app.pub.BuildDraftStoreTest,app.pub.LauncherUiTest,app.pub.LauncherEditingTest,app.pub.VoiceInputUiTest,app.pub.GraphicsUiTest,app.pub.BuilderDeviceTest,app.pub.AutomaticVisualRepairTest,app.pub.BackgroundBuildTest app.pub.test/androidx.test.runner.AndroidJUnitRunner
```

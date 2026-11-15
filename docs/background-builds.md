# PUB 0.7.0 — background building

Tap Generate, then switch to another app. PUB continues generation, private preview capture, visual review and automatic repair in an Android foreground service (since then also research and behavior checks; see docs/research.md and docs/behavior-checks.md). A progress notification includes Cancel. A completion notification opens the draft for preview and saving; the launcher also has View build result. Notifications are enabled on the connected phone.

The renderer uses an app-owned private virtual display. It renders only the generated app and never captures another app or the physical phone screen. Its isolated JavaScript process is separate from any PUB app you are currently running. Generated apps themselves still have foreground-only hardware access.

Completed drafts, edit baselines, prompts and review results are checkpointed privately before completion is announced. Closing PUB's activity or switching apps does not cancel the build. After process termination, force-stop or reboot, PUB preserves the last checkpoint and reports interruption without automatically repeating a paid request. One build runs at a time; the user still chooses whether to save the result as an app.

If notifications are disabled later, building continues; return to PUB to see the result. Android network, battery and foreground-service time restrictions can still interrupt work.

Validated on the connected Samsung SM-S948U (Android 17):

- Live ChatGPT generation and screenshot review completed after leaving PUB; tapping the completion notification opened the generated draft.
- The complete generation / repair / fresh screenshot / review flow passed with the physical screen off and did not wake it.
- Private native previews rendered correctly without an Activity and with a stopped Activity; concurrent isolated runtimes remained independent.
- Notification cancellation, atomic draft storage, interruption recovery without an automatic paid retry, and save-result adoption passed device checks.
- Foreground vector/raster screenshots, automatic repair, launcher editing and graphical UI regression checks passed.
- All 94 core JVM tests passed; debug app/test builds and Android lint passed with zero lint errors.

The installed version is 0.7.0 (build 11). Notification permission is enabled. The phone's original USB stay-awake setting was restored. Verification used temporary projects and did not replace your saved apps.

Implementation: the application-owned BuilderController outlives Activities; BuildService supplies foreground execution, progress/cancel/completion notifications and a bounded partial wake lock. OffscreenPreview renders the actual native UI on an app-owned private virtual display, with a separate isolated JS runtime and hardware effects disabled. BuildDraftStore atomically checkpoints pending results before successful completion notifications. Android may still interrupt work after force-stop, reboot, loss of network or system execution limits; reopening PUB recovers the latest checkpoint and lets you explicitly retry.

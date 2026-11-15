# PUB 0.6.0

## App construction history

Long-press a launcher icon, choose **Edit app**, then **Prompt history**. History shows only that app: submitted prompts, selected model, outcomes and completed visual reviews. The global Settings history entry is removed.

Each new-app session has its own history. Saving links all its attempts, including earlier failed/cancelled requests, to the saved app. Up to 200 records are retained per app or draft session. Existing linked records remain readable. Earlier unlinked legacy requests cannot be reliably assigned to an app. Aggregate storage is bounded; capacity failures preserve existing histories.

## Automatic screenshot review

After a successful generation, PUB runs the draft in its isolated JavaScript runtime with empty storage and settings. File requests are answered from an empty in-memory folder (`SimulatedFiles`), so an app that loads its files is captured in its empty state rather than while loading; all other effects are ignored, no hardware access is provided, and saved photos are not loaded. It renders the initial viewport using the native renderer and current theme, waits for graphical assets, and captures only the generated surface.

The screenshot is bounded to 1280 pixels on its longest side and 4 MiB. PUB sends it, the submitted request and generated app code to the selected model through the existing ChatGPT connection (now also the build's research brief and a behavior-check summary as data; see docs/research.md and docs/behavior-checks.md). Screenshot bytes are temporary, not saved to the user's photo library. Review findings are stored with the exact prompt.

In 0.6.0, **Use review in next edit** placed the findings into an editable instruction. Since 0.6.1 that button is gone: concrete findings trigger automatic repairs instead (docs/automatic-visual-repair.md). Manual preview and save remain available when screenshot review fails.

Review covers only the initial visible screen. It does not prove button behavior, scrolling content, calculations, microphone detection, hardware operations, persistence or accessibility. These still require interactive testing. Image-input review and raster-image generation are different provider capabilities.

## Validation

- Android build and lint passed; zero lint errors.
- 86 core JVM tests passed.
- 17 distinct device tests passed: seven history-storage tests, three clean-preview capture tests, two history/graphics UI tests, two launcher edit/delete tests, and three vision-review tests.
- The live ChatGPT test identified the heading from screenshot pixels without receiving the heading or source in its text input. It returned concrete feedback about diagram clarity, string contrast and selection labeling.
- An initial raster assertion was corrected after inspecting its PNG: the 64-pixel fixture shrank to a valid 27-pixel square, below the test threshold. The larger fixture passed; production capture code did not need changing.
- Installed on the connected phone without clearing saved apps or the ChatGPT connection.

The review example and screenshot are controlled test fixtures, not changes to your saved guitar tuner.

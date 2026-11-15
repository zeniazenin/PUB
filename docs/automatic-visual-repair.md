# PUB 0.6.1

## Automatic visual repair

Each successful build now runs this sequence automatically:

1. Render a clean native preview with empty private storage and no hardware access.
2. Capture the generated viewport and ask the selected ChatGPT model to check the original requirements.
3. If concrete visible issues are found, generate a corrected package using the current draft, original requirements, review findings and full build environment (and the build's research brief, as background).
4. Capture the corrected app again and review the fresh screenshot.

Current behaviour: a research step comes before generation, and the build's behavior checks (with their own automatic repairs) run before step 1 and again after every repair; visual and behavior repairs share the two-repair budget, and the review also receives the research brief and a checks summary. See docs/research.md and docs/behavior-checks.md.

Up to two repair attempts are permitted per build. The loop stops on a visible pass, an unverifiable screenshot, cancellation, failure, unchanged output, additional permission requests, or the repair limit. It keeps the latest valid draft and reports unresolved issues. Saving remains a user action.

Every automatic repair and completed review is recorded in that app's construction history. No copying findings into a new prompt is required. Initial-screen review cannot verify hardware behavior, calculations, interactions, scrolling content, persistence or other screen states.

## Guitar tuner fix

Current builds no longer bundle the tuner; it was a development fixture. Apps saved from it keep working.

The microphone broker previously sent listening=false while preparing the input route. The saved tuner treated this as a stop and cancelled immediately when the broker announced recording. Preparation no longer sends this misleading terminal status; actual denial, failure and stop events remain unchanged. The saved tuner source and preferences were preserved.

## Context supplied to the builder

Every creation, edit and automatic repair receives the PUB deployment target, Kotlin/Java Android host, Jetpack Compose Material 3 renderer, isolated Rhino ES6 runtime, exact UI/event/effect contracts, hardware inventory, supported broker operations and permission rules. It also receives phone/OS details, screen and measured builder viewport dimensions, density, orientation, font scaling, language, theme and accent.

The brief explicitly covers unsupported browser/Node/native APIs, no general networking or background execution, source and message limits, execution budget, UI complexity, graphical assets, local state/photo quotas, and the current 65–400 Hz pitch-estimation range. Detected hardware is distinguished from exposed functionality and permission. Current app code/assets accompany edits; saved personal data, photos and account credentials do not. Full original requirements remain attached during repairs.

## Validation

- 94 JVM tests passed.
- 21 distinct focused device tests passed: six automatic-loop tests, seven scoped-history tests, two build-context tests, two microphone-preparation tests, the real saved-tuner test, a live automatic repair test, and two history/graphics UI tests.
- The live test used the complete phone context: ChatGPT found an intentionally wrong heading, generated its correction, and passed a new screenshot of the revised app.
- The saved tuner continued recording and receiving fresh samples for the sustained two-second regression check.
- Android build/lint passed with zero lint errors. Installed without clearing saved apps or ChatGPT sign-in.

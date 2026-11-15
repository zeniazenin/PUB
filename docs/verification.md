# Verification record — PUB 0.2.0

Verified November 4, 2026, on Samsung SM-S948U, Android 17/API 37.

## Current builder milestone

- Debug APK and Android test APK build successfully.
- **41 JVM tests pass**, zero failures. Coverage includes the original tuner/pitch/sandbox/policy tests plus package validation, generic input/storage behavior, PKCE/state/client binding, ID-token signature and claim rejection, and completed/incomplete/refused streaming responses.
- Android lint: **0 errors, 19 warnings** (dependency updates, icon/resource conventions and optional Kotlin style).
- **4 physical-device tests pass**: isolated JavaScript Binder session; generated-app preview/interaction/save/reopen/delete with separate permission identities and denied INTERNET permission for the isolated UID; Android Keystore encrypted credential roundtrip in a dedicated dummy test file; real microphone capture, global revocation, and foreground/background lifecycle behavior.
- The initial encrypted-storage test attempted to use the instrumentation package's directory while running under the target UID. The fixture now uses a separate file in the target context. It never reads or overwrites a real account session.
- PUB 0.2.0 is installed on the connected phone. Account credentials were not taken from other apps, tools or the environment.

## Prior tuner milestone evidence

PUB 0.2.0 passed the physical microphone capture/revocation/background test after the phone was unlocked. The full four-test Android suite passed in 4.191 seconds. The Build tab was opened and its ChatGPT sign-in button verified through the native UI hierarchy. All original JVM tuner/pitch tests still pass. Synthetic pitch tests cover 42 frequency/detuning/sample-rate combinations and additional harmonic/noise cases; this does not establish acoustic guitar accuracy.

## Live ChatGPT generation verified

- User completed ChatGPT browser login and returned to PUB; model catalog loaded.
- Fixed an HTTP 400 by sending Responses input as a message array, as required for ChatGPT plan usage.
- Generated a real Tip Calculator using the selected GPT-6.1-Sol model. Its isolated preview rendered successfully.
- Verified $100 bill + 20% tip = $120 total, $60 each for two people, and $30 each for four people.
- Verified zero people produces a validation message instead of a division result.
- Saved the app locally, force-stopped/reopened PUB, and reopened it from My apps. The four-person setting and $30 result persisted.
- ChatGPT credentials also survived app replacement/restart without another sign-in.
- Follow-up display-text adaptation and generation keep-awake improvements installed. The corrected calculator layout was visually inspected and captured. A locked-phone microphone recheck failed because foreground capture was blocked; after unlocking, the final microphone recheck passed in 2.436 seconds.

## Additional manual checks

- Acoustic guitar accuracy against a calibrated reference, noisy rooms, external microphones, and battery use.
- Expired/rotating sessions, cancellation, plan limits, remote disconnect, account switching, and Android process death during authorization/generation.
- More generated-app types, all supported models, and cross-manufacturer/large-font accessibility behavior.

Live sign-in and one real generated app are verified. This does not establish correctness for every generated app, all models, expired sessions, or account limits. Device networking was not globally disabled; offline execution is supported by local packages/state and the isolated runtime's verified lack of INTERNET permission.
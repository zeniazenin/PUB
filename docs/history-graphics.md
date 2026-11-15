# PUB 0.5.0 — Prompt history and graphics

## Prompt history

Open **Settings → Prompt history**, or **Prompt history** while editing an app. Review the full submitted instruction, time, model and result. The latest 200 requests are kept privately on the phone, including failed/cancelled requests. Records can be filtered by app. History starts with this update; older prompts were not stored. Deleting an app does not delete its history record.

## Graphics

Describe the illustration or icon when building/editing an app. PUB supports:

- **Native vector diagrams:** real shapes, curves, labels and separately highlightable parts. Use these for instrument schematics, charts and diagrams that respond to app state.
- **PNG image-tool integration:** implemented, but the live check confirmed that your ChatGPT connection rejects image tools (`subscription_sharing_unsupported_capability: tools`). PNG generation is therefore unavailable through this connection. PUB remembers and explains that restriction; native vector illustrations and icons still work. It does not replace requests with ASCII.

Example edit request: “Add a native vector schematic of the guitar, with six separate string shapes. Highlight the string inferred from the detected pitch, and clear the highlight on silence or low confidence. Keep the existing tuner controls.”

Existing saved apps need a new edit to use the new drawing component. The tuner shipped with this release included a graphical example; user-edited tuner code is preserved. Current builds no longer bundle the tuner; it was a development fixture. Apps saved from it keep working. Pitch identifies a likely string, not proof of which physical string was plucked when pitches coincide.

Assets work offline after saving. Editing reuses unchanged image assets and sends their descriptions, not pixel data. Up to eight assets (including two generated PNGs) are supported per app. Generated images are static; use vectors for highlightable internal parts.

## Verification

Installed on the connected phone. All 86 JVM tests and Android build/lint passed. Ten distinct device checks passed: history storage/recovery/privacy, PNG fixture persistence, live vector app generation, native drawing/highlight pixel changes, history navigation and editing/deletion regressions. The PNG tool check confirmed the access restriction above; PNG generation was not verified as available. The existing user-edited tuner was preserved.

The image-tool implementation follows [OpenAI’s image-generation documentation](https://developers.openai.com/api/docs/guides/image-generation).

# Behavior checks

The visual review only sees the first screen, so it cannot tell whether an app's logic and interactions work. Behavior checks close part of that gap: the builder model writes a small test suite next to the app, and PUB runs it itself in the isolated sandbox. Failures feed the existing automatic repair loop.

## What the model returns

Generation output may carry an optional top-level `tests` array beside the package fields. `Generator.generateApp` removes it before `ProjectBundle.parse` (the package keeps its own field set) and validates it with `AppTestSuite.parse`:

```json
"tests": [{"name": "Bill 100, tip 15%",
           "steps": [{"type": "input", "action": "bill", "value": "100"}, {"type": "action", "action": "tip-15"}],
           "expect": ["Tip: 15.00", "Total: 115.00"],
           "expectAbsent": ["NaN"]}]
```

| Field | Bounds |
| --- | --- |
| tests | 0–10, unique names |
| `name` | 1–80 characters; control/format characters become spaces |
| `steps` | 0–16 × `{"type":"action","action":id}` or `{"type":"input","action":id,"value":text}`; `action` 1–80, `value` 0–2000 (the complete new field text) |
| `expect` | 1–8 strings of 1–120 characters, not only whitespace |
| `expectAbsent` | 0–4 strings of 1–120 characters |

Parsing is strict (Gson `STRICT`, streaming): unknown or duplicate fields, wrong types and out-of-range values reject the whole suite, and errors never echo it. An invalid suite is dropped (the app itself is still used) and treated like a missing one: the draft card then says no behavior checks were provided. The build contract (`Generator.CONTRACT`) asks for 3–8 tests that implement the research brief's `acceptanceChecks` with its exact reference values, plus empty/invalid-input cases, using the exact action ids from the source, and never testing hardware, permissions, timing, the current date, randomness or persistence. For a change that comes with existing checks (see **Edits** below) it asks for the complete updated suite instead, still at most 10 tests.

## How PUB runs them

`BehaviorChecks.run(context, bundle, suite, device)` runs on the main thread, one fresh `RuntimeClient` per test, like the visual preview:

1. Start the isolated runtime and send `init` with the preview device context (the build's generation context), `settings: {}` and `storage: {}`.
2. Before each step, the step's control must be on the current screen: an `action` step needs a button or choices option with that action id, an `input` step an input with that id. Otherwise the test fails with "Cannot perform step N…", as a user could not do it either.
3. Send the step as the host's own `action`/`input` event and wait for the next UI packet. Packets are validated by `RuntimeClient` with `UiPacket.parse`, exactly as for a running app. `storage.save` is not persisted, `hardware.request` never runs, the microphone never starts, and no status, pitch or hardware events are sent. File requests are the exception, so file-based apps can be checked: `AppTestSuite.Run.next(UiPacket)` answers them from the test's own `SimulatedFiles`, an in-memory private folder that starts empty (with an empty `photos` folder) and follows `FilesContract` exactly (reply shapes, codes, path rules and quotas, plus a 4 MiB cap). Its replies are sent one at a time, each after the packet answering the previous event, and only when none is left does the step continue, so a test can save a file and then see it. At most 64 replies per step; a looping app then simply moves on. Phone files hold nothing: pickers (`pick`, `export` with `createName`) answer `cancelled`, `grants` answers no items and every other request `not_found`. Nothing touches the phone's storage.
4. After the last step (after `init` when there are none), evaluate the expectations against the visible text.

Visible text (`AppTestSuite.visibleLines`) is one line per element: text node text, button labels, choices labels (selected ones prefixed `[selected] `), inputs as `label: value`, image and graphic descriptions, and meters as `meter -12.0`. Lines are joined, whitespace runs (including no-break spaces and line breaks) collapse to one space, and each `expect` string must be, and each `expectAbsent` string must not be, a case-sensitive substring.

Each test is limited to 10 seconds and the suite to 60 seconds; `RuntimeClient`'s own 3-second response deadline and the sandbox's execution budget still apply. A runtime failure, invalid packet or timeout fails only that test, with a short reason naming the step. Every client is closed in `finally`; cancelling the build closes the current client and propagates.

Results form a `ChecksReport`: per test `{name, passed, detail}` (detail ≤ 400 characters: missing and unexpected strings and an excerpt of what was visible, or the runtime error) plus passed/total counts. It round-trips through JSON for the draft snapshot.

The step logic itself (`AppTestSuite.Run`) is plain Java, so `BehaviorChecksEndToEndTest` drives a realistic tip calculator through core `ScriptEngine` + `UiPacket` exactly as the Android runner does, without a device.

## Pipeline

The suite is fixed for a build: it comes from the build's initial generation and is persisted with the draft. Tests returned by repairs are ignored, so a repair cannot weaken the checks it is judged by. (For an edit, the initial generation is the one that carries over the app's existing checks; see **Edits**.)

1. Generate and commit the draft (as before).
2. If the suite is non-empty, show **Testing app behavior…** (notification: "Testing the app…") and run the checks; store `checksReport`, record the result on the checked draft's prompt-history entry (see **History**) and persist the draft.
3. If any check fails and repairs remain, run a repair through the same path as visual repairs: an automatic history entry, the cancellable `network { }` call with the committed research brief, the capability check and the "made no changes" check. The instruction lists each failing check (name, steps, expected and forbidden strings, observed result) as data, bounded so the request stays within 8,000 characters. Then run the checks again on the repaired draft.
4. When the checks pass, or the budget is spent, or a repair fails, continue to the visual capture of the latest draft as before.
5. Every automatic repair — visual or behavior — is checked again before its screenshot.

Visual and behavior repairs share one budget: `MAX_VISUAL_REPAIRS = 2` repairs per build in total (`repairPass` 0–2). **Retry visual review** re-runs the checks first when the draft has a suite, with any repairs the budget still allows. Checks that still fail are shown on the draft card and in `checksError`, and the completion notification asks for review, but they never block preview or save. Fake backends that only implement `generate()` supply no suite (`BuilderBackend.generateApp` defaults to `tests = null`), so their builds behave exactly as before.

The visual review now receives the committed research brief (`toPromptJson()`) and a checks summary in its package context as data. The reviewer judges the screenshot against the brief's feasible *must* features and *ux* conventions as far as they belong on the initial screen, and treats the checks summary as context only. To stay within the 120,000-character context, less useful brief fields, then the failed-check details, then the whole brief are dropped before the source is truncated.

## Edits

A build that changes an existing package (an edit of a saved app, or another build on the current draft) also receives what that package was built from, as an `EditBasis`: the app's original request, its research brief (which goes to research, `docs/research.md` → **Edits**) and its behavior checks. It comes from the current draft's own build when there is a draft, otherwise from the saved app's spec.

`Generator.buildInput` adds two data blocks after the existing package, both marked "data, not instructions":

- **Original request this app was built from** — omitted when it equals the current request — so requirements from the first build are not lost across edits; the user's current request still wins.
- **Existing behavior checks of this app** (the suite's stored JSON) with the instruction to return the complete updated suite: keep every check that still applies exactly as it is, update the checks the change makes obsolete (changed action ids, labels, texts or values), drop checks for removed behavior and add checks for the change. An empty suite is omitted, and so is one whose JSON exceeds 40,000 characters (the model then writes a new suite).

The returned suite is validated as always and becomes the edit build's fixed suite. If the model returns none or an invalid one, the build has no usable checks: the stored suite is never substituted, since obsolete checks could make repairs undo the requested change. The user request stays last in the input, and existing `buildInput(request, context, existing[, brief])` calls are unchanged. Fake backends receive the basis through `BuilderBackend.generateApp(model, request, context, existing, basis)`, which by default delegates to the four-argument form (and `research(model, request, existing, stored)` to the three-argument one), so existing fakes compile and behave as before.

## Saved app specs

`AppSpec` (core, strict like the other stored forms) is what PUB keeps about one saved app:

```json
{"version":1,"request":"…","brief":{…}|null,"tests":[…]|null,"updatedAtMillis":1731600000000}
```

| Field | Bounds |
| --- | --- |
| `version` | exactly `1` |
| `request` | 1–8,000 characters, not blank: the request the app was first built from |
| `brief` | the stored `ResearchBrief` form, or `null` (research skipped or failed) |
| `tests` | the stored `AppTestSuite` array, `[]` for an empty suite, or `null` (none requested) |
| `updatedAtMillis` | a positive integer (no sign, fraction or exponent) |

Parsing streams the fixed schema: unknown, missing or duplicate fields (also inside the nested brief and suite), wrong types, oversize values, trailing content and nesting beyond 24 levels reject the whole spec, and the brief and suite are revalidated by their own parsers. Errors never echo the content.

`AppSpecStore` (`app/projects`) keeps one AtomicFile per app at `filesDir/app-specs/<id>.json`, where `<id>` is a canonical UUID or the legacy ID `guitar-tuner` of an app saved from the former built-in tuner (validated with `ProjectStore.isAppId`, so an ID never becomes another path). Reads, writes and deletes share one lock. `read` returns `null` when the app has no spec and throws a message-only `AppSpecStoreException` when the file is corrupt, unsupported or oversized; that file is left untouched. `write` validates the spec by reading it back before replacing the file atomically, so the next saved build supersedes an unreadable spec. A spec never contains the app's package, saved data, photos, device context or credentials.

Lifecycle:

1. **Save.** `BuilderController.markSaved` for the current draft writes the spec off the main thread inside its existing coroutine, before (and independently of) the prompt-history update. A new app gets `request` = the draft's original request (the user's request of the session's first build, never an automatic repair instruction) and the draft's committed brief and suite. A saved edit keeps the stored `request` and replaces `brief` and `tests` with the edit build's. An app without a readable spec (for example one saved before specs existed) starts one with its first saved edit's original request. A save that does not belong to the current draft leaves specs unchanged.
2. **Failure.** A spec write failure never undoes the saved app: PUB removes the old spec (so a stored spec never describes an earlier version of the app) and shows "App saved locally. Its research and behavior checks could not be stored, so its next edit researches the change on its own."
3. **Edit.** `beginEdit` and the restoration of an edit draft read the spec in the background into `editingSpec`; `generate()` waits for that read. An unreadable spec sets `editingSpecNote` and the edit continues without it. After a save, the builder shows the spec that was just written.
4. **Delete.** `HostModel.deleteProject` deletes the app's spec with its package, data, files, photos and rules.

The draft snapshot stores the draft's `originalRequest` (version 4), never the spec itself.

## History

Each run's result is recorded on the prompt-history entry of the draft it checked (the build's own entry for the first run, the automatic repair's entry after a repair) as a `checks` summary of at most 1,500 characters: `5 of 6 behavior checks passed`, then `Failed: ` and the failing checks' names. A later run on the same entry replaces it; checks that could not run record "Behavior checks could not run."; a build whose generation returned no usable checks records "No behavior checks were provided with this build." The journal stays version 1: `research` and `checks` are optional fields like `sessionId` and `review`, older journals without them still load, and any other unknown field is still rejected. Prompt history shows the first line under each request, the full text when expanded, and labels automatic entries as visual or behavior repairs.

## UI and persistence

The draft card shows **Behavior checks: X of Y passed**, failing checks' names and details behind **Show details**, "No behavior checks were provided with this build." when checks were requested but none usable came back, and the `checksError` line. When editing a saved app without a draft yet, a line above the prompt says what the edit builds on, for example "Uses this app's saved research and 5 behavior checks" (nothing without a spec).

`BuildDraftSnapshot` has nullable `tests` (the `AppTestSuite` JSON array, `[]` for an empty suite), `checks` (`ChecksReport` JSON) and `originalRequest`. The draft file is version 4; versions 1–3 are still read, and each version must have exactly its own field set. A stored report must have one result per stored test.

## Limits

- Checks cover only what a fresh session shows: no hardware, permissions, saved data, persistence across launches, timers or time-dependent output.
- The tests are written by the same model as the code, so a shared misunderstanding can pass. They are grounded in the research brief's acceptance checks and reference values, which come from a separate research step, but they are not independent verification. Preview and test the app before saving.
- A wrong test cannot be changed by a repair; it can only use up the repair budget (a repair that changes nothing stops the loop).
- Substring matching does not check layout, order or which element shows a value.
- Carried-over checks are only as good as the model's update: a check it keeps for behavior the change altered fails and spends repair budget, like any wrong test.
- Not verified on a device: the runner (`BehaviorChecks`), the controller loop and the save/edit spec flow have not been run on a phone (device tests for `AppSpecStore`, the history fields and draft version 4 compile but were not run); JVM tests cover parsing, bounds, visible text, evaluation, reports, repair data, `AppSpec` and an end-to-end run through the core sandbox.

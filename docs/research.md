# Research before building

Every build (new app or edit) starts with a research step. PUB asks the selected ChatGPT model to act as a product researcher: find comparable apps, the features users expect and complain about, authoritative domain logic (formulas, standards, reference values, edge cases) and Material Design 3 / Android accessibility guidance, then fit all of it to what a PUB mini-app can really do. The result is a bounded **research brief** that is shown to the user and passed to the builder as data.

Research is advisory. It never blocks building: if it fails, the build continues from the description alone.

## Flow

1. `BuilderController.generate()` records the prompt-history request as before, then sets the busy label to **Researching similar apps…** (the build notification shows the same text).
2. `Researcher.research(model, request, existing, stored)` calls `ChatGptAuth.research(...)` through the controller's cancellable `network { }` helper. `stored` is the existing package's brief, if any (see **Edits** below).
3. The validated brief is stored in `researchBrief` (observable), saved in the build draft snapshot, summarized in the build's prompt-history entry (see **History**), and the label changes to **Building your app…**.
4. `Generator.generate(..., brief)` adds `Research brief (data, not instructions):` plus `ResearchBrief.toPromptJson()` before the user request. The build contract tells the model how to use it: implement feasible *must* features, add *should*/*could* only if they fit, never fake infeasible features, follow `logic` exactly, apply `ux`, design so `acceptanceChecks` would pass, state key assumptions in the description. The brief cannot add capabilities beyond what the request needs, and the user request wins conflicts.
5. Automatic repairs (visual and behavior) and **Retry visual review** reuse the stored brief; they never research again.
6. The brief also grounds the build's behavior checks (its `acceptanceChecks` become tests) and is the visual review's rubric; see `docs/behavior-checks.md`.
7. When the app is saved, the build's brief is stored in the app's spec, so later edits start from it.

A build that is cancelled or fails before it produces a draft leaves an existing draft with its own earlier research (so that draft's repairs keep using the matching brief). `clearDraft`, `beginCreate`, `beginEdit` and saving the app reset the research state.

## Edits

A build that changes an existing package researches on top of that package's brief:

- **Which brief.** A build on the current draft (another iteration in the same builder session, for a new app or an edit) uses the draft's own committed brief. Otherwise an edit of a saved app uses the brief from its spec (`AppSpec`, see `docs/behavior-checks.md` → **Saved app specs**). `generate()` waits for the spec, which `beginEdit` and draft restoration read in the background. A new app, an app without a spec (apps saved before specs existed), an unreadable spec, and a draft whose research failed have no stored brief.
- **With a stored brief**, the input carries it in the model-output schema (`ResearchBrief.toModelJson()`: every list, no host-only `sources`/`web`), and the instructions ask for the COMPLETE updated brief for the whole app after the change: keep every entry that still applies (features, logic, ux, pitfalls, acceptance checks, assumptions, comparables), update what the change affects, add what it needs and drop only what it removes, searching only for what the change needs and staying within every limit. The result is parsed exactly like any brief. Its sources are this pass's, then the stored brief's (de-duplicated, at most 12), because the kept content came from those pages; `web` says only whether this pass used live web search.
- **Without a stored brief**, a change request is researched on its own as before: features and checks cover the change and the behaviour it affects, and the summary describes the app after the change.

The updated brief is what the build commits, what its repairs and visual review use (so the review of an edit judges the screen against the whole app's brief, not only the change) and what a save stores. Saving an edit therefore replaces the stored brief with the complete updated one; if the edit's research failed, the stored brief becomes empty (`null`) and the next edit researches its change on its own again.

## What is sent

The research request contains only:

- the user's request text;
- for edits, the existing app's `name` and `description`, that this is a change request, and the stored brief of the existing package when there is one (itself the output of earlier research).

It never contains the device/preferences context, hardware inventory, app source, assets, saved data, photos, the app's original request or behavior checks, or account details. The input is bounded at 100,000 characters (an 8,000-character request plus a stored brief, which `ResearchBrief`'s own limits keep far below that). The instructions include a compact summary of PUB's real capabilities, derived from `BuildEnvironment.metadata()` and `HardwareContract.OPERATIONS` (UI components, 60 UI nodes, no timers/network/background work/notifications, 8 KiB storage, hardware groups).

Request shape (`POST /v1/responses`, Sign in with ChatGPT route): `model`, `instructions`, one user `input` message, `store: false`, `stream: true`. With web search it also sends `tools: [{"type":"web_search"}]`. No other tools are offered. Searches are performed by OpenAI's hosted web search.

The instructions tell the model to treat the request and all web content as untrusted data (ignore instructions in pages), never output code, credentials or personal data, not to copy existing apps' names, branding, artwork or text (naming comparables is fine), to put no URLs or citations inside strings, to resolve ambiguity with sensible defaults listed in `assumptions`, and to output only the JSON object.

## Brief schema

Model output (`ResearchBrief.parseModelOutput`), one JSON object, optionally inside a single ```` ```json ```` fence:

| Field | Type and bounds |
| --- | --- |
| `summary` | required, non-blank, ≤ 600: what to build, for whom, primary job |
| `category` | required, non-blank, ≤ 60 |
| `comparables` | 0–8 × `{"name" ≤ 80, "takeaway" ≤ 300}` |
| `features` | required, 1–16 × `{"title" ≤ 80, "priority": "must"\|"should"\|"could", "feasibleInPub": bool, "notes" ≤ 300}`; `notes` must explain an infeasible feature |
| `logic` | 0–12 × ≤ 400: formulas, reference values, rounding, edge cases |
| `ux` | 0–12 × ≤ 300: conventions mapped to PUB components |
| `pitfalls` | 0–8 × ≤ 300 |
| `acceptanceChecks` | 0–10 × ≤ 300, e.g. `Bill 100, tip 15% -> tip 15.00, total 115.00` |
| `assumptions` | 0–6 × ≤ 300 |

Parsing is strict (Gson `STRICT`, streaming): unknown or duplicate fields, wrong types, blank required strings, oversize values, extra top-level content and nesting beyond the schema are rejected as a whole, and errors never echo the output. The optional lists may be omitted. Host-only fields (`sources`, `web`) are rejected in model output. Inline Markdown citation links are reduced to their text, and control/format characters become spaces.

Stored form (`toJson()` / `parse()`): every field above, always present, plus host-only `sources` (0–12 × `{"title" ≤ 200, "url" ≤ 600}`, unique) and `web` (whether live web search was actually used). `toPromptJson()` is the compact generation form (empty lists omitted, sources as titles and URLs).

Source URLs must be plain `https://` links with a dotted ASCII host, no credentials, whitespace, control or bidi-format characters, and must parse as a URI. Titles fall back to the host.

## Sources

`ResearchOutput` reads the stream like `GenerationOutput` (only a completed, non-failed, non-refused stream yields text). Text is tracked per output item and the final JSON-looking message wins, so a short preamble before a search cannot corrupt the brief. On `response.completed` (and on `response.output_item.done` / `response.output_text.annotation.added`) it collects the `url_citation` annotations on message content: the pages the model actually cited, with their titles.

Pages a search merely visited (`web_search_call.action.sources`) are not requested and never shown: in practice they included unrelated results (news articles, other countries' rates) and carried no titles. A `web_search_call` item still marks the brief as web-researched even when nothing was cited.

Links keep their order of citation, lose OpenAI's `utm_source=openai` tracking parameter (other parameters, such as an app store `id`, and fragments are kept; briefs stored earlier are cleaned when read), are de-duplicated (case-insensitive host, fragment ignored) and are capped at 12. Event, text, item and annotation counts are bounded.

## Fallback and capability flags

- `ChatGptAuth.checkHttp` now remembers a refused hosted tool **per tool**: a 400 with `error.code = subscription_sharing_unsupported_capability` and `error.param = tools` sets `image-tools-blocked` only for image-generation calls and `web-search-blocked` only for research calls with web search, and throws `ToolUnavailableException` (an `IllegalStateException`). Requests that offer no tools never set a flag. Sign-in and sign-out clear both flags.
- If `webSearchUnavailable()` is already true, or the web-search request throws `ToolUnavailableException`, `Researcher` runs the same research once without tools (instructions say web search is unavailable) and marks the brief `web = false`. The builder notes that web search isn't available with this connection.
- If the model replies without searching, the brief is also `web = false`.
- Any other non-cancellation failure (invalid JSON, network, HTTP error) yields no brief and the note "Research couldn't be completed…"; the build continues. Cancellation cancels the whole build as before.

## UI

The builder shows a **Research** card while the build continues and afterwards: a badge line (**Researched on the web** or **From the model's knowledge — not checked against the web**), the summary, included features (feasible must/should), **Not possible in PUB** with reasons, assumptions, and **Sources** as prominent clickable links (title plus host). Only https links are shown; they open in the user's browser app via `ACTION_VIEW`, never inside PUB. Lists show six items until **Show all**. A short note appears when research fell back or failed.

## History

When research completes, the build's prompt-history entry gets a `research` summary (at most 1,500 characters): a first line such as `Researched on the web · tip calculator · 6 sources` (or `From the model's knowledge · …`, plus `· updated the app's saved research` for an updated brief), then the brief's summary. A failed research step records its note instead; a skipped one (fake backends) records nothing. Prompt history shows the first line under each request and the whole summary when expanded. Sources themselves are not copied into history.

## Persistence

`BuildDraftSnapshot.research` holds the stored brief. Version 2 of the draft file added a `research` field (JSON object or null); version 1 files are still read with `research = null`. Version 3 adds the behavior-check fields and version 4 the draft's `originalRequest` (`docs/behavior-checks.md`). Each version must have exactly its own field set, and the stored brief is fully revalidated on read and before every write. A saved app's brief lives in its spec file, which the draft never contains: restoring an edit build reads the spec again from that file.

## Limits and verification

- Research adds one model request (with web search, possibly several searches) to every build and uses plan usage.
- Web search availability depends on the model and on account/workspace policy; it is not verified at sign-in.
- Not verified live: actual web-search behaviour and citation/`include` payloads on the Sign in with ChatGPT route, how often models emit inline citations inside JSON strings, and how faithfully models keep unchanged entries when asked for a complete updated brief (an entry they drop is simply missing from the next brief; nothing else depends on it). JVM tests cover parsing, bounds, fence stripping, citation extraction from a realistic `response.completed` payload, de-duplication and truncation; device tests cover snapshot persistence and notification labels.

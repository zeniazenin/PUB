# UI planning, interface building blocks and launcher icons

A build has four model steps, each a request with its own instructions and context:

1. **Research** (`Researcher`): comparable apps, features, domain rules and UX conventions, fitted to PUB's limits. See `research.md`.
2. **UI planning** (`UiPlanner`): an interface designer plans every screen of the app before any code exists.
3. **Building** (`Generator`): the app package, implementing the brief and the plan, plus its behavior checks.
4. **Icon** (`IconDesigner`): the launcher pictogram. This runs only when the package has no icon yet.

After building, PUB runs the behavior checks and the visual review, with repairs where needed. Both use the plan.

## The planning step

The planner receives:

- the user's request and the research brief (data);
- PUB's component catalog and limits (`UiGuide.COMPONENTS`, shared with the builder, so the plan uses only what can be built);
- approximate rendered sizes of each component (`UiGuide.LAYOUT`);
- the phone's **screen budget** (`UiGuide.screenBudget`), computed from the generation context:
  - the measured app viewport in dp (else the screen size), content width, font scale and orientation;
  - estimated body characters per line;
  - the first-view height and the space left while the keyboard is open;
- PUB's other capabilities and limits (`Researcher.platformFacts`).

Its instructions ask for a best-in-class interface within those limits:

- **First screen:** the primary job in the first view, budgeted in dp.
- **Navigation:** one screen per task, with a consistent way back.
- **Inputs:** the right input for each kind of data (multiline, numeric keyboards, choices, dictation).
- **States and feedback:** empty, loading, error, validation and success states, and feedback after every action, with confirmation before anything destructive.
- **Content:** compact lists, exact wording and accessible labels. The app's name is never repeated as a heading, since PUB's title bar already shows it, and nothing is shown twice in one place.
- **Limits:** no component, gesture or navigation that PUB does not have.

The answer is a `UiPlan` (core), strict and bounded:

```json
{"layout":"…","screens":[{"name":"…","purpose":"…","firstView":"…","elements":["…"],"states":["…"]}],
 "interactions":["…"],"content":["…"],"accessibility":["…"]}
```

- **Limits:** 1-8 screens with unique names; up to 24 elements and 8 states per screen; every string non-blank and bounded.
- **Rejection:** unknown or missing fields reject the whole plan, and error messages never echo it.
- **Planning failure:** if planning fails, the build continues without a plan and the builder shows "UI planning couldn't be completed, so PUB designed the screens while building."

### How the plan is used

- **Building:** the generator gets the plan as data, under the instruction to implement its screens, element order, first views, states and interactions faithfully.
- **Repairs:** automatic repairs get the draft's plan too.
- **Visual review:** the review gets the plan's layout, its first screen and its accessibility notes. A primary input, result or action missing from the planned first view, or clearly wrong planned wording, is grounds for a fix.
- **Builder screen:** a **UI plan** card shows the structure and each screen's purpose; "Show every screen in detail" lists the elements, states, interactions, wording and accessibility notes.

### Persistence and edits

The plan is kept the same way as the behavior checks:

- **Drafts:** it is saved with the draft (`BuildDraftStore` version 5; versions 1-4 still load, without a plan).
- **Saved apps:** it is stored in the app's spec (`AppSpec` version 2, `files/app-specs/<id>.json`; version 1 specs still load).
- **Edits:** an edit gives the planner the stored plan and asks for the complete updated plan. Unaffected screens and wording stay as they are, so people find their way around as before. An app saved before plans existed has no stored plan, so the planner reads its current source (up to 70,000 characters) to see its screens.

## Interface building blocks

`UiPacket` validates, and `NativeRenderer` draws, these components and options on top of the original ones. Screens are start-aligned like any Material screen; `display` headlines (results, big numbers) are centered.

| Component | Option | Effect |
|---|---|---|
| `button` | `style` | `primary` (filled, the screen's main action; the default), `secondary` (outlined), `text` (quiet, for Back, Cancel or About), `danger` (filled in the error colour, for destructive actions). Primary, secondary and danger span the width; text buttons and buttons in a row take their natural width |
| `card` | `children`, `action?`, `label?` | A raised surface grouping related content. With `action` the whole card is one button with `label` as its spoken name, and it may hold no controls of its own |
| `row` | `children` (1-8 text or buttons) | Short texts or small buttons side by side, wrapping when they do not fit; also a compact series such as one short mark per day of a week |
| `item` | `title`, `supporting?`, `trailing?`, `action?`, `checked?`, `selected?` | A compact list row (56-72 dp). With `action` it is tappable; `checked` shows a checkbox (the app flips the state on the action); `selected` tints it. One node per row, so lists stay within the 60-node limit |
| `text` | `align` | `start` or `center`, overriding the default |
| `text` | `style:'paragraph'` | Start-aligned reading text, up to 4,000 characters (other styles are centered labels, up to 512) |
| `input` | `multiline:true` | 5 visible lines, growing to 14; Enter adds a new line; value up to 8,000 characters (single-line: 2,000) |
| `input` | `keyboard` | `text`, `number`, `decimal`, `phone` or `email` keyboard |
| `input` | `voice:true` | A microphone in the field that dictates text into it |

Unknown text and button style values still render as the default, so apps saved before these options keep working.

When an open app comes back on screen after the user left it, the host sends `{type:'resume'}` (`HostModel.setVisible`). Apps refresh whatever depends on the current date or time then, without a Refresh button.

Behavior checks read the new components:
- **Items:** as "title · supporting · trailing", prefixed `[checked] `, `[unchecked] ` or `[selected] `.
- **Cards and rows:** as their contents.
- **Tapping:** an `action` step can tap tappable items and cards.

How dictation works:

- **Same service as the builder:** it uses Android's speech service (`RecognizerIntent`, offline preferred), like the builder's "Speak your idea".
- **Text only:** the recognized words are added to the field as if typed, and the app receives an ordinary `input` event. Apps never get audio, and need no capability or permission.
- **Blocking:** Settings → Access → Block microphone everywhere turns dictation off for every app. The microphone is then shown disabled, as it is in the visual review.

The research step is told about these options, so research can recommend dictation or multiline text where they fit.

## Launcher icons

Every app gets a pictogram launcher icon (`AppIcon`): a vector on a 48-unit grid, at most 16 shapes, no text.

- **Who designs it:** the builder no longer draws icons. The icon step (`IconDesigner`) designs one from the app's name, description and research whenever a build ends without a pictogram. The result is one concrete object or familiar metaphor for the app's purpose, never letters.
- **Edits and repairs:** they keep the existing icon.
- **Failure:** a failed icon step never fails the build; the app then has no icon.
- **Drawing (`AppIconTile`):** every pictogram is drawn the same way, whatever colours or widths the model used:
  - one tint from the theme palette, chosen from the app's name, so the draft and the saved app match;
  - every outline 3 units wide with round caps;
  - a fill of `surface` cuts a shape out of the tile.
- **Apps without an icon:** a built-in pictogram (tuner, calculator) or a neutral app symbol. Two-letter monograms are gone.
- **Where it shows:** the builder's draft card shows the icon before saving.

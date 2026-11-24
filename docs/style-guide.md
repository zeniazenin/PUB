# Style guide

PUB has one style guide, set in **Settings → Style → Edit style guide**. PUB applies it itself, when it draws, to its own screens and to every generated app: their components, graphics and launcher tiles. It replaces the former Style accent.

## What it sets

| Setting | Choices | Where it shows |
|---|---|---|
| Mode | Dark, Light, Match phone | Every colour; the status and navigation bars follow |
| Main colour | Eight presets or any `#RRGGBB` | Primary buttons, highlights, icons, eyebrow text, graphics |
| Second colour | Automatic or any `#RRGGBB` | Selected rows, filter chips, secondary graphic parts |
| Third colour | Automatic or any `#RRGGBB` | Contrasting accents, such as a meter's needle |
| Corners | Sharp, Rounded, Round | Buttons, cards, chips, fields, list rows, launcher tiles, dialogs |
| Font | Sans, Serif, Mono (system fonts) | All text, including text shapes in graphics |

- **Live:** the screen shows a sample app in the current style, and every change applies at once, to saved apps too.
- **Reset:** "Reset to PUB's default style" returns to the default: dark, moss, apricot third colour, rounded, sans.
- **Upgrade:** an install that had chosen an accent before style guides keeps it as its guide: that accent on PUB's dark theme.

Apps built before style guides were designed for PUB's dark theme. Their components switch to light mode cleanly, but fixed hex colours in their graphics and generated pictures don't change, so a light-cream drawing can be hard to see on a light background. Turning the style guide off for such an app keeps it in PUB's default dark style.

## Apps that don't follow it

Any saved app can be set not to follow the style guide:

- **Where:** long-press the app on the launcher and turn off **Use style guide**, or use the switches under "Apps that follow it" on the style guide screen.
- **What changes:** the app's screen, including its title bar, then uses PUB's default style; PUB's own screens keep the style guide.
- **Defaults:** new apps follow the style guide. A preview of an edit follows its saved app's setting.
- **Deleting an app** removes its setting.

## How it is applied

The style guide is data PUB draws with, not instructions to the model. Generated apps cannot set colours, fonts or shapes of their own; the host owns all of them.

- **`StyleGuide` (core):** a strict, bounded JSON object: `{"version":1,"mode":"dark","primary":"#BDD990","tertiary":"#FFC084","corners":"rounded","font":"sans"}`.
  - `secondary` and `tertiary` are optional; without them they are derived from `primary`.
  - Unknown fields, other versions, invalid colours and unknown choices reject the whole guide.
  - It holds no free text, so nothing the user writes can reach a prompt through it.
- **`StyleColors` (core):** derives the complete Material 3 colour scheme for light and dark from tonal palettes.
  - A palette keeps one hue and chroma (CIE LCh) and varies the tone, which is CIE L* as in Material's own palettes.
  - A picked colour is used exactly when it suits the mode: in dark mode when it is light (tone 60-95), in light mode when white text on it reads at 4.5:1. Otherwise the palette's standard tone of the same hue is used (80 in dark mode, 40 in light mode).
  - Text on every role colour keeps at least 4.5:1 contrast; JVM tests check this for many colours in both modes.
  - Error colours are Material's standard ones.
- **`PubTheme` (app):** applies the scheme, a type scale in the chosen font family and a shape scale for the corners. It is used by:
  - PUB's screens;
  - the running app (in that app's style);
  - the private preview that the visual review captures.
- **Neutrals:** backgrounds and surfaces are tinted slightly by the main colour, so a blue guide has blue-grey surfaces. Upgraded installs keep their exact main and third colours; their backgrounds are now these tinted neutrals.
- **Launcher mark:** in light mode PUB's own logo sits on its dark pine icon background, which it was drawn for.
- **Components:** `NativeRenderer` gives buttons and list rows the style's corners. Material's buttons are always pill-shaped otherwise, so PUB's own buttons stay pill-shaped.

## Graphics

Vector shapes can use theme colour names instead of fixed hex colours:

- **Names:** the Material colour roles of the style guide (`GraphicAsset.THEME_COLORS`): `primary`, `onPrimary`, the `secondary`, `tertiary` and `error` families with their containers, `surface`, `onSurface`, `surfaceVariant`, `onSurfaceVariant`, `surfaceContainer`, `surfaceContainerHigh`, `surfaceContainerHighest`, `outline`, `outlineVariant`, `inverseSurface`, `inverseOnSurface` and `transparent`.
- **When they are resolved:** names are looked up when the graphic is drawn, so a graphic follows the style, its light or dark mode, and later changes. Hex colours never change.
- **Saved graphics:** graphics saved before this release keep their colours. The original five names (`primary`, `surface`, `onSurface`, `outline`, `transparent`) still work.
- **Highlights** are drawn in `primary`.
- **Text shapes** use the style's font.

The building instructions list the names (`GraphicGuide`).

## What is sent

The style the app is drawn in goes with each build as part of the phone context:

- `uiTheme` (`light` or `dark`, resolved for "Match phone");
- `styleGuide` (the fields above).

For an app that does not follow the style guide this is PUB's default style. The running app receives the same two fields in its `init` device context. The guide contains colours and choices only, no text; see `privacy-policy.md`.

## Not yet

Phase 1 is applied by the host only. The model is not yet asked to design for the guide:

- the UI planner, builder, icon step and visual review get the guide only as context data, with no style instructions or rubric;
- there is no illustration style;
- there is no host-enforced "themed" mode for model-drawn graphics;
- there are no chart components.

## Verification

- **JVM tests:** `StyleGuideTest` covers the schema, legacy accent migration, exact legacy colours, contrast in both modes for 16 colours, picked-colour fidelity and theme colour names in graphics. All core tests pass.
- **Build and lint:** the debug build, lint and compilation of the device tests pass.
- **Colours:** the generated light and dark schemes of every preset were rendered and checked visually.
- **Not run on a phone:** the screens have not been tested on a device or emulator; none was connected.

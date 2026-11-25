# PUB design language

Implemented November 24, 2026. PUB's own screens and every app it runs share one look: old-school software, made precise: pixel and LCD type, pixel-art icons, crisp layouts. The style guide (`docs/style-guide.md`) supplies the colours, light or dark, corners and type pairing; this document describes what PUB does with them.

## Principles

- **Type carries the hierarchy, not boxes.** Pixel, LCD dot-matrix or terminal faces for headlines and text, and tiny Silkscreen pixel capitals ("eyebrows") for sections, metadata and numbers. Few filled surfaces; hairlines separate.
- **Crisp, never bubbly.** Buttons are rectangles with a small radius (square, 4 dp or 10 dp for Sharp, Rounded or Round), never pills. Cards are one tone above the background with a 1 dp hairline edge, not a shadow.
- **Pixels, not strokes.** Interface icons are 11 × 11 pixel glyphs with one-pixel lines, and app icons are rasterized onto a 24-cell grid and drawn as LCD cells, so icons and type share one pixel language.
- **One thing at a time.** The builder is a sequence of steps with one main action each, pinned at the bottom of the screen. Detail that isn't needed to decide is folded away.
- **Proportional, not fixed.** Grids, tiles and glyphs are sized from the space available; text follows the user's font scale; touch targets stay at least 48 dp.

## References

- **Teenage Engineering:** monospaced labels and metadata at small sizes ([design guide](https://blakecrosley.com/de/guides/design/teenage-engineering)).
- **Old-school software and hardware:** DEC terminals (VT323 is modelled on the VT320), LCD dot-matrix displays and the 16-pixel icons of early desktop software, chosen by the user over a modern grotesque set.
- **2026 mobile reviews:** the clean interfaces they single out (Linear among them) show only what is needed now and keep a clear visual hierarchy ([UXPin](https://www.uxpin.com/studio/blog/mobile-app-design-examples), [Muzli](https://muz.li/blog/whats-changing-in-mobile-app-design-ui-patterns-that-matter-in-2026/)).

## Type

The three pairings are described in `docs/style-guide.md`. PUB bundles four open-source font families (SIL Open Font License 1.1, about 750 KB):

| Font | Axes used | Role |
|---|---|---|
| Pixelify Sans | weight 400-700 | Text and UI in Pixel and LCD; headlines in Pixel |
| Silkscreen (Regular, Bold) | (static) | Small labels in every pairing |
| Doto | weight, round dots (ROND 100) | Headlines and big numbers in LCD |
| VT323 | (static) | Everything in Terminal |

- **Instances:** every weight the type scale uses is a real instance; single-weight faces are declared for every weight. Synthesis is off everywhere, because a faked bold smears pixels.
- **Scale:** line heights follow Material's type scale, so `UiGuide`'s screen-space figures stay true. VT323's glyphs are small for their size, so Terminal sets them a quarter larger inside the same lines.
- **Tracking:** none on pixel headlines and text; Silkscreen labels open up slightly (+4% to +5%).
- **Text shapes in graphics:** these use the pairing's text face.

## PUB's own icon

The home-screen icon (`res/drawable/ic_launcher_foreground.xml`, with a matching monochrome layer for themed icons) is a pixel "P" with an amber terminal cursor. It is drawn on a 12 × 12 cell grid in phosphor green on PUB's dark pine background, with a hairline gap between cells. The launcher header shows the same mark (`pub_mark`) on a small dark plate. The drawables are generated so the cells stay on whole-number coordinates and the paths short.

## Components (`app/ui/PubComponents.kt`)

- `PubButton`, `PubOutlinedButton`, `PubTextButton`: Material buttons with the guide's button shape. Main actions are filled; others are outlined with a hairline or are text only.
- `PubCard`: a grouped surface with a hairline edge.
- `Eyebrow`: a small Silkscreen pixel-capital label.
- `PubListRow`, `PubSection`: settings and details as titled groups of rows with chevrons.
- `PubIcons`: back, close, plus, check, chevrons, settings (two sliders), mic, play, spark (a starburst), publish, history and edit: 11 × 11 pixel glyphs with one-pixel lines, each row's runs merged into solid rectangles so no seams show.

## App icons

Launcher icons are pictograms on a 48-unit grid (`core/AppIcon`).

- **Fitting:** when drawn, every pictogram is measured exactly (curves at their extremes, not their control points) and scaled and centred into the live area 9..39, with growth capped at 1.6×. An icon a model drew too large, too small or off centre therefore sits at the same optical size as the rest. Stored icons are never rewritten.
- **Pixel art:** the fitted pictogram is rasterized without anti-aliasing onto a 24-cell grid (2 grid units per cell), with every outline exactly one cell wide whatever the fit. Each lit cell is drawn as an LCD cell: a square with a hairline gap, or a round dot in the LCD pairing. Unlit cells of the live area show faintly, like the segments of an LCD that are off.
- **Size:** the grid fills 80% of the tile, so the glyph takes about half of it.
- **Tile:** the tile is a 10% tint of the app's colour over the surface, with a faint hairline.
- **Duotone:** a fill of `primaryContainer` lights its cells in a soft colour (the tint at 30% over the tile) behind the outline. A `primary` fill is a solid accent; `surface` clears the cells under it.
- **Built-in pictograms:** apps without a designed icon get a minimal built-in one in the same style: a calculator, a sounding tuning fork, or a neutral "apps" symbol of three rounded squares and a circle.

### How an icon is designed

Building a new app ends with the icon step (`IconDesigner`). Edits and repairs keep the app's existing icon; **Redesign icon** in a saved app's long-press menu runs the same steps for it on request:
- **Before anything is sent:** a dialog says what is sent and that it uses one or two plan requests, and nothing is sent until **Design icon**.
- **Designing:** the builder's ChatGPT connection is used, and the app's saved research is given as context when it has some.
- **Choosing:** the current and new icons appear side by side, with **Use new icon**, **Try again** and **Keep current**.
- **Saving:** **Use new icon** stores the icon through the host only if the app is unchanged since the design started (`HostModel.useIcon`). It changes only the icon asset, and an open edit of that app (without a draft) continues from the new package, so its later save is not refused as stale.
- **When it isn't offered:** while a build runs, while the builder is busy, or while that app has an unsaved edit draft.

1. **Design.** One short request asks for a single minimalist line pictogram, like SF Symbols or Phosphor at a light weight; its clean geometry is what survives the pixel grid well. The request sets out the style:
   - **Process:** consider three metaphors, choose the one recognised fastest at 24 px that needs the fewest shapes, then remove every shape that doesn't change what the icon is.
   - **Keylines:** a round object fills a circle 30 across, a square one 26 × 26, a tall one 20 × 30, a wide one 30 × 20, so every icon looks equally big.
   - **Minimalism:**
     - one object (two only when the pair is the idea);
     - 2-5 shapes, never more than 6;
     - one closed path per silhouette;
     - at most two interior lines;
     - geometric primitives, in a flat front or side view.
   - **Precision:**
     - whole or half units;
     - one corner-radius family;
     - symmetry where the object has it;
     - 3 units between parallel outlines;
     - nothing overlapping or drawn twice.
   - **Colour:** one optional duotone fill on the silhouette and at most one tiny solid accent.
   - **Never:** letters, logos, faces, hands, frames, badges, background circles, sparkles.
   - **Pixel grid:** the request says the icon is drawn as pixel art on a 24 × 24 grid (`AppIcon.PIXELS`, 2 units per pixel) with one-pixel outlines. So the object stays upright, lines are horizontal, vertical or at 45 degrees, coordinates are even, and every detail is at least 4 units across.
   - **Examples:** three reference designs (`AppIcon.STYLE_EXAMPLES`: a timer, a journal, plant care) show the level of reduction. Each has 2-4 shapes, and the request says never to copy them.
2. **Refine** (`AppIcon.refine`, on the phone, no model call):
   - every coordinate goes on half units and stroke widths are dropped;
   - outlines become `primary` or `transparent`;
   - fills are reduced to the icon palette: none, solid, cut-out, or soft (any `…Container` colour);
   - shapes that draw nothing, are under half a unit or repeat another shape are removed.
3. **Simplicity check** (`AppIcon.tooBusy`): a design with more than 6 shapes, or a busyness above 30, gets one more request. Busyness counts 1 per line, 2 per rectangle or ellipse, and 1 per path command. That request shows the design (as data) with its counts and asks for the silhouette alone, merged into one closed path, without secondary objects or interior details. The simpler of the two designs is kept, and a failed retry keeps the first. So an icon costs one request, or two when the first is too busy.
4. **Draw:** fitting, the pixel grid and the tile, as above, so even a design that ignores the size rules matches the rest.
- **App vector assets (`GraphicGuide`):** the builder asks for one light outline weight (about 0.5% of the canvas width), flat theme-colour fills, half-unit coordinates, an 8% margin and few shapes.

## Layout and proportion

- **Launcher grid:** columns = (width − 40 dp) / 88 dp, between 3 and 6 (four on a typical phone). Tiles are 70% of a column, clamped to 48-64 dp. App names are 12 sp in two lines.
- **Spacing:** screens use 20 dp side margins. Sections are 28-32 dp apart, with 10-16 dp within a section.
- **Top bar:** back and settings are icon buttons, and the screen's name is set in the display face.

## The builder: Describe → Review → Publish

The step follows the builder's state, so a notification lands on the right one. Only going on from Review to Publish is the user's choice, and it resets when the draft changes.

1. **Describe:** a headline question, one sentence on what PUB does, the description box with **Speak your idea** and a character count, then the model and Prompt history as rows. **Build app** is pinned below.
   - **While building:** the box is replaced by the five phases (Research, Plan screens, Build, Test behavior, Check the screen). Done phases are ticked, with what research and planning found, and **Cancel build** is pinned below.
2. **Review:** the draft, then **Adjust**, then **How it was built**. **Try it** and **Continue** are pinned below.
   - **The draft:** its icon, name and description, with three facts side by side: checks, visual check and access.
   - **Adjust:** the change box, **Speak a change**, Quick fixes, **Apply changes** and What you asked for. A change in progress shows the phases here instead.
   - **How it was built:** folded rows for research (with sources and Report research), the screen plan, the behavior checks and the visual review.
   - **Footer:** Prompt history, Discard draft and Report content.
3. **Publish:** the app's icon and name, then a short table of what saving does:
   - where the app lives;
   - its data (new, or kept for an edit);
   - the access it asks for (its own storage is not counted);
   - the style;
   - its checks;
   - that the app opens once it is saved.

   **Add to Your apps** (or **Save changes**) opens the draft and saves it once it has rendered without a failure. That is the same save, with the same checks, as **Save locally** in a preview; the saved app then stays open.

## Verification

`DesignScreenshots` (device, offline fixtures) renders the launcher, every builder step, Settings, the style guide, a type specimen per pairing (with a line of glyphs the fonts lack, to check fallbacks), an icon sheet at three sizes in dark and light, and the launcher and builder steps on a 340 dp screen at font scale 1.4. It saves full-screen PNGs to `cache/design-shots`. It runs only when asked for, because the shots include the phone's saved apps and account address:

```powershell
.\.toolchain\android-sdk\platform-tools\adb.exe shell am instrument -w -e class app.pub.DesignScreenshots -e pubDesignShots true app.pub.test/androidx.test.runner.AndroidJUnitRunner
```

`LiveIconDesignTest` designs icons with the connected ChatGPT account through the real workflow and saves the answers, the refined designs and renders to `cache/live-icons`. It uses plan usage and runs only when asked for (`-e pubLiveIcons true`; `-e pubLiveIconsOnly <name start>` limits it to some of its app ideas).

On November 24, 2026, with GPT-6.2-Astra and the user's approval, it designed three icons in 3 requests. None was too busy, so none needed the simplify retry:
- **Habit Tracker:** a calendar with a check, 5 shapes.
- **Mortgage Calculator:** a house with an equals sign, 3 shapes.
- **Bird Log:** a bird silhouette with a wing, 2 shapes.

Every icon used the duotone fill. The calendar and house read clearly as pixel art at every size. The first bird was drawn tilted, so it blurred on the pixel grid. The pixel-grid guidance above was added after that run, and one more request produced an upright bird with a round head, a beak and a single wing line, which reads better. Total: 4 requests.

@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package app.pub.ui

import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import app.pub.R
import app.pub.core.StyleColors
import app.pub.core.StyleGuide

/** The style guide that the surrounding [PubTheme] draws in; components read its corners and font from here. */
val LocalPubStyle = staticCompositionLocalOf { StyleGuide.DEFAULT }

/**
 * Draws [content] in [style]: its complete colour scheme in light or dark ([dark] defaults to the guide's mode and the
 * phone's dark theme setting), its type pairing and its corners. Shared by PUB's screens, running apps and private build
 * previews, so an app looks the same everywhere.
 */
@Composable
fun PubTheme(style: StyleGuide, dark: Boolean = style.dark(isSystemInDarkTheme()), content: @Composable () -> Unit) {
    val colors = remember(style, dark) { PubColors.scheme(style, dark) }
    val typography = remember(style.font) { PubType.typography(style.font) }
    val shapes = remember(style.corners) { PubShapes.of(style.corners) }
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes) {
        CompositionLocalProvider(LocalPubStyle provides style, content = content)
    }
}

/** Shared by the visible host and private build previews. */
object PubColors {
    /** The guide's complete Material colour scheme ([StyleColors]) in light or dark. */
    fun scheme(style: StyleGuide, dark: Boolean): ColorScheme {
        val roles = StyleColors.scheme(style, dark)
        fun c(role: String) = Color(roles.getValue(role))
        return ColorScheme(
            primary = c("primary"), onPrimary = c("onPrimary"), primaryContainer = c("primaryContainer"),
            onPrimaryContainer = c("onPrimaryContainer"), inversePrimary = c("inversePrimary"),
            secondary = c("secondary"), onSecondary = c("onSecondary"), secondaryContainer = c("secondaryContainer"),
            onSecondaryContainer = c("onSecondaryContainer"),
            tertiary = c("tertiary"), onTertiary = c("onTertiary"), tertiaryContainer = c("tertiaryContainer"),
            onTertiaryContainer = c("onTertiaryContainer"),
            background = c("background"), onBackground = c("onBackground"), surface = c("surface"), onSurface = c("onSurface"),
            surfaceVariant = c("surfaceVariant"), onSurfaceVariant = c("onSurfaceVariant"), surfaceTint = c("surfaceTint"),
            inverseSurface = c("inverseSurface"), inverseOnSurface = c("inverseOnSurface"),
            error = c("error"), onError = c("onError"), errorContainer = c("errorContainer"), onErrorContainer = c("onErrorContainer"),
            outline = c("outline"), outlineVariant = c("outlineVariant"), scrim = c("scrim"),
            surfaceBright = c("surfaceBright"), surfaceContainer = c("surfaceContainer"), surfaceContainerHigh = c("surfaceContainerHigh"),
            surfaceContainerHighest = c("surfaceContainerHighest"), surfaceContainerLow = c("surfaceContainerLow"),
            surfaceContainerLowest = c("surfaceContainerLowest"), surfaceDim = c("surfaceDim"))
    }

    /** A graphic's theme colour name ([app.pub.core.GraphicAsset.THEME_COLORS]) in [colors]; null for any other name. */
    fun graphicColor(colors: ColorScheme, name: String): Color? = when (name) {
        "primary" -> colors.primary; "onPrimary" -> colors.onPrimary
        "primaryContainer" -> colors.primaryContainer; "onPrimaryContainer" -> colors.onPrimaryContainer
        "secondary" -> colors.secondary; "onSecondary" -> colors.onSecondary
        "secondaryContainer" -> colors.secondaryContainer; "onSecondaryContainer" -> colors.onSecondaryContainer
        "tertiary" -> colors.tertiary; "onTertiary" -> colors.onTertiary
        "tertiaryContainer" -> colors.tertiaryContainer; "onTertiaryContainer" -> colors.onTertiaryContainer
        "error" -> colors.error; "onError" -> colors.onError
        "errorContainer" -> colors.errorContainer; "onErrorContainer" -> colors.onErrorContainer
        "surface" -> colors.surface; "onSurface" -> colors.onSurface
        "surfaceVariant" -> colors.surfaceVariant; "onSurfaceVariant" -> colors.onSurfaceVariant
        "surfaceContainer" -> colors.surfaceContainer; "surfaceContainerHigh" -> colors.surfaceContainerHigh
        "surfaceContainerHighest" -> colors.surfaceContainerHighest
        "outline" -> colors.outline; "outlineVariant" -> colors.outlineVariant
        "inverseSurface" -> colors.inverseSurface; "inverseOnSurface" -> colors.inverseOnSurface
        "transparent" -> Color.Transparent
        else -> null
    }
}

/**
 * PUB's bundled type families (SIL Open Font License, see assets/licenses): pixel and LCD faces for an old-school
 * software look. Every weight the type scale asks for is declared as a real instance (single-weight faces are declared
 * for every weight), so nothing is synthesized: a faked bold would smear the pixels.
 */
object PubFonts {
    private val WEIGHTS = listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

    /** Pixelify Sans: a readable pixel sans, weights 400-700. Text, buttons and the Pixel pairing's headlines. */
    val pixelify = FontFamily(WEIGHTS.map { Font(R.font.pixelify_sans, it, variationSettings = FontVariation.Settings(FontVariation.weight(it.weight.coerceIn(400, 700)))) })
    /** Silkscreen: tiny pixel capitals made for small sizes. Labels in every pairing. */
    val silkscreen = FontFamily(WEIGHTS.map { Font(if (it.weight >= 600) R.font.silkscreen_bold else R.font.silkscreen, it) })
    /** Doto with round dots: an LCD dot-matrix face for the LCD pairing's headlines and big numbers. */
    val doto = FontFamily(WEIGHTS.map { Font(R.font.doto, it, variationSettings = FontVariation.Settings(
        // Doto's weight is its dot size: heavy weights keep the dot matrix legible, even for "light" requests.
        FontVariation.weight(when (it) { FontWeight.Light -> 700; FontWeight.Normal -> 750; FontWeight.Medium -> 800; FontWeight.SemiBold -> 900; else -> 900 }),
        FontVariation.Setting("ROND", 100f))) })
    /** VT323: a DEC VT320 terminal face, one weight. The Terminal pairing. */
    val vt323 = FontFamily(WEIGHTS.map { Font(R.font.vt323, it) })
}

/**
 * The guide's font choice as a retro type pairing: a display face for headlines, a text face for reading and UI, and
 * Silkscreen pixel capitals for small labels in every pairing. Line heights stay those of Material's type scale, so the
 * space the builder tells the model each element takes stays true; VT323, whose glyphs are small for their size, is set
 * a quarter larger inside the same lines.
 * - sans (Pixel): Pixelify Sans headlines and text.
 * - serif (LCD): Doto dot-matrix headlines, Pixelify Sans text.
 * - mono (Terminal): VT323 headlines and text.
 */
object PubType {
    /** The display face of [font]'s pairing. */
    fun display(font: String): FontFamily = when (font) { "serif" -> PubFonts.doto; "mono" -> PubFonts.vt323; else -> PubFonts.pixelify }
    /** The text face of [font]'s pairing. */
    fun text(font: String): FontFamily = if (font == "mono") PubFonts.vt323 else PubFonts.pixelify

    fun typography(font: String): Typography {
        val display = display(font); val text = text(font); val labels = PubFonts.silkscreen
        val terminal = font == "mono"
        // VT323's glyphs are about three quarters the size of other faces at the same size.
        val textScale = if (terminal) 1.25f else 1f
        val headScale = if (terminal) 1.25f else 1f
        val headWeight = if (font == "serif") FontWeight.SemiBold else FontWeight.Bold
        fun head(size: Int, line: Int) = style(display, headWeight, (size * headScale).sp, line.sp, 0.em)
        fun body(weight: FontWeight, size: Int, line: Int, tracking: Double = 0.0) = style(text, weight, (size * textScale).sp, line.sp, tracking.em)
        return Typography(
            displayLarge = head(57, 62), displayMedium = head(45, 50), displaySmall = head(36, 42),
            headlineLarge = head(32, 38), headlineMedium = head(28, 34), headlineSmall = head(24, 30),
            titleLarge = head(22, 28),
            titleMedium = body(FontWeight.Medium, 16, 24),
            titleSmall = body(FontWeight.Medium, 14, 20),
            bodyLarge = body(FontWeight.Normal, 16, 24),
            bodyMedium = body(FontWeight.Normal, 14, 20),
            bodySmall = body(FontWeight.Normal, 12, 16, 0.01),
            // Buttons and chips use labelLarge: the text face, slightly heavier.
            labelLarge = body(FontWeight.Medium, 14, 20, 0.01),
            // Small labels (eyebrows, metadata, counts) are always Silkscreen pixel capitals.
            labelMedium = style(labels, FontWeight.Normal, 10.sp, 16.sp, 0.04.em),
            labelSmall = style(labels, FontWeight.Normal, 9.sp, 16.sp, 0.05.em))
    }

    private fun style(family: FontFamily, weight: FontWeight, size: TextUnit, line: TextUnit, tracking: TextUnit) = TextStyle(
        fontFamily = family, fontWeight = weight, fontSize = size, lineHeight = line, letterSpacing = tracking,
        fontSynthesis = FontSynthesis.None,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))

    /** The pairing's text face for text drawn on a canvas, such as text shapes in graphics. */
    fun typeface(context: Context, font: String): Typeface = runCatching {
        ResourcesCompat.getFont(context, if (font == "mono") R.font.vt323 else R.font.pixelify_sans)
    }.getOrNull() ?: Typeface.MONOSPACE
}

/**
 * The guide's corners. Crisp by default: buttons are rectangles with a small radius, never pills, and surfaces share
 * one radius family.
 * - sharp: square corners everywhere.
 * - rounded (default): 4 dp buttons, 6-14 dp surfaces.
 * - round: soft 10 dp buttons, 12-24 dp surfaces.
 */
object PubShapes {
    fun of(corners: String): Shapes = when (corners) {
        "sharp" -> Shapes(extraSmall = RoundedCornerShape(0.dp), small = RoundedCornerShape(0.dp), medium = RoundedCornerShape(2.dp),
            large = RoundedCornerShape(2.dp), extraLarge = RoundedCornerShape(4.dp))
        "round" -> Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(24.dp))
        else -> Shapes(extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(6.dp), medium = RoundedCornerShape(10.dp),
            large = RoundedCornerShape(12.dp), extraLarge = RoundedCornerShape(14.dp))
    }

    /** Buttons: crisp rectangles, softened only by the guide's corners. */
    fun button(corners: String): Shape = when (corners) { "sharp" -> RectangleShape; "round" -> RoundedCornerShape(10.dp); else -> RoundedCornerShape(4.dp) }

    /** Corner radius of a tappable or selected list row. */
    fun item(corners: String): Dp = when (corners) { "sharp" -> 0.dp; "round" -> 14.dp; else -> 6.dp }

    /** Corner radius of an app's launcher tile, as a fraction of its size. */
    fun tile(corners: String): Float = when (corners) { "sharp" -> .08f; "round" -> .34f; else -> .22f }
}

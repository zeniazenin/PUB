package app.pub.ui

import android.graphics.Typeface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pub.core.StyleColors
import app.pub.core.StyleGuide

/** The style guide that the surrounding [PubTheme] draws in; components read its corners and font from here. */
val LocalPubStyle = staticCompositionLocalOf { StyleGuide.DEFAULT }

/**
 * Draws [content] in [style]: its complete colour scheme in light or dark ([dark] defaults to the guide's mode and the
 * phone's dark theme setting), its font and its corners. Shared by PUB's screens, running apps and private build
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

/** The guide's font: Material's type scale in the system sans-serif (Material's default), serif or monospace family. */
object PubType {
    fun typography(font: String): Typography {
        val family = when (font) { "serif" -> FontFamily.Serif; "mono" -> FontFamily.Monospace; else -> return Typography() }
        val t = Typography()
        return Typography(
            displayLarge = t.displayLarge.copy(fontFamily = family), displayMedium = t.displayMedium.copy(fontFamily = family),
            displaySmall = t.displaySmall.copy(fontFamily = family), headlineLarge = t.headlineLarge.copy(fontFamily = family),
            headlineMedium = t.headlineMedium.copy(fontFamily = family), headlineSmall = t.headlineSmall.copy(fontFamily = family),
            titleLarge = t.titleLarge.copy(fontFamily = family), titleMedium = t.titleMedium.copy(fontFamily = family),
            titleSmall = t.titleSmall.copy(fontFamily = family), bodyLarge = t.bodyLarge.copy(fontFamily = family),
            bodyMedium = t.bodyMedium.copy(fontFamily = family), bodySmall = t.bodySmall.copy(fontFamily = family),
            labelLarge = t.labelLarge.copy(fontFamily = family), labelMedium = t.labelMedium.copy(fontFamily = family),
            labelSmall = t.labelSmall.copy(fontFamily = family))
    }

    /** The same family for text drawn on a canvas, such as text shapes in graphics. */
    fun typeface(font: String): Typeface = when (font) { "serif" -> Typeface.SERIF; "mono" -> Typeface.MONOSPACE; else -> Typeface.SANS_SERIF }
}

/** The guide's corners: Material's shape scale (cards, chips, fields, menus, dialogs), buttons, list rows and icon tiles. */
object PubShapes {
    fun of(corners: String): Shapes = when (corners) {
        "sharp" -> Shapes(extraSmall = RoundedCornerShape(2.dp), small = RoundedCornerShape(4.dp), medium = RoundedCornerShape(4.dp),
            large = RoundedCornerShape(6.dp), extraLarge = RoundedCornerShape(8.dp))
        "round" -> Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(36.dp))
        else -> Shapes()
    }

    /** Material's pill-shaped buttons, or nearly square ones for sharp corners. */
    fun button(corners: String): Shape = if (corners == "sharp") RoundedCornerShape(4.dp) else CircleShape

    /** Corner radius of a tappable or selected list row. */
    fun item(corners: String): Dp = when (corners) { "sharp" -> 4.dp; "round" -> 20.dp; else -> 12.dp }

    /** Corner radius of an app's launcher tile, as a fraction of its size. */
    fun tile(corners: String): Float = when (corners) { "sharp" -> .16f; "round" -> .5f; else -> .3f }
}

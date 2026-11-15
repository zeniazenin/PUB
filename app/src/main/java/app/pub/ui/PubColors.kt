package app.pub.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Shared by the visible host and private build previews. */
object PubColors {
    fun scheme(accent: String): ColorScheme {
        val primary = when (accent) {
            "blue" -> Color(0xFF9EC8FF)
            "violet" -> Color(0xFFD0BCFF)
            else -> Color(0xFFBDD990)
        }
        return darkColorScheme(
            primary = primary, onPrimary = Color(0xFF17220C),
            primaryContainer = lerp(Color(0xFF191D17), primary, .2f), onPrimaryContainer = primary,
            secondaryContainer = lerp(Color(0xFF191D17), primary, .15f), onSecondaryContainer = primary,
            background = Color(0xFF10130F), surface = Color(0xFF191D17),
            surfaceContainerHighest = Color(0xFF252B21), tertiary = Color(0xFFFFC084)
        )
    }
}

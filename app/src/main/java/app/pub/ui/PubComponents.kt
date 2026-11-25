package app.pub.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/*
 * PUB's own controls. Material 3 always draws buttons as pills; these take their shape from the style guide's corners
 * (PubShapes.button), so PUB's screens and running apps share one crisp shape language.
 */

private val ButtonPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)

/** The screen's main action: filled, crisp corners. */
@Composable
fun PubButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
              colors: ButtonColors = ButtonDefaults.buttonColors(), contentPadding: PaddingValues = ButtonPadding,
              content: @Composable RowScope.() -> Unit) =
    Button(onClick, modifier.heightIn(min = 48.dp), enabled, shape = PubShapes.button(LocalPubStyle.current.corners), colors = colors,
        contentPadding = contentPadding, content = content)

/** Other actions: a hairline outline. */
@Composable
fun PubOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                      colors: ButtonColors = ButtonDefaults.outlinedButtonColors(), contentPadding: PaddingValues = ButtonPadding,
                      content: @Composable RowScope.() -> Unit) =
    OutlinedButton(onClick, modifier.heightIn(min = 48.dp), enabled, shape = PubShapes.button(LocalPubStyle.current.corners), colors = colors,
        border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant),
        contentPadding = contentPadding, content = content)

/** Quiet actions: text only. */
@Composable
fun PubTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                  colors: ButtonColors = ButtonDefaults.textButtonColors(), contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
                  content: @Composable RowScope.() -> Unit) =
    TextButton(onClick, modifier, enabled, shape = PubShapes.button(LocalPubStyle.current.corners), colors = colors,
        contentPadding = contentPadding, content = content)

/** A grouped surface: one step tonally above the background, with a hairline edge instead of a shadow. */
@Composable
fun PubCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    OutlinedCard(modifier, shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), content = content)

/** A list row that opens something: a title, an optional line under it, an optional value and a chevron. */
@Composable
fun PubListRow(title: String, supporting: String?, modifier: Modifier = Modifier, value: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    androidx.compose.foundation.layout.Row(modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) colors.onSurface else colors.onSurfaceVariant)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        }
        value?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = colors.primary) }
        androidx.compose.material3.Icon(PubIcons.ChevronRight, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
    }
}

/** A titled group of settings or details: a small label, a title, then its content. */
@Composable
fun PubSection(eyebrow: String, title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Eyebrow(eyebrow)
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        content()
    }
}

/** A small uppercase Geist Mono label above a section or value. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text.uppercase(), modifier = modifier, style = MaterialTheme.typography.labelMedium, color = color)

/**
 * PUB's interface icons as pixel art: 11 x 11 cells with one-cell lines, drawn as solid squares (runs of a row merged,
 * so no seams), so they match the pixel type at its weight.
 */
object PubIcons {
    private fun pixels(name: String, vararg rows: String): ImageVector {
        require(rows.size == 11 && rows.all { it.length == 11 }) { "Pixel icon $name must be 11 x 11" }
        return ImageVector.Builder(name, 24.dp, 24.dp, 11f, 11f).path(fill = SolidColor(Color.Black)) {
            rows.forEachIndexed { y, row ->
                var x = 0
                while (x < row.length) {
                    if (row[x] != '#') { x++; continue }
                    var end = x; while (end < row.length && row[end] == '#') end++
                    moveTo(x.toFloat(), y.toFloat()); lineTo(end.toFloat(), y.toFloat()); lineTo(end.toFloat(), y + 1f); lineTo(x.toFloat(), y + 1f); close()
                    x = end
                }
            }
        }.build()
    }

    val Back = pixels("back",
        "...........",
        "...........",
        "....#......",
        "...#.......",
        "..#........",
        ".#########.",
        "..#........",
        "...#.......",
        "....#......",
        "...........",
        "...........")
    val Close = pixels("close",
        "...........",
        ".#.......#.",
        "..#.....#..",
        "...#...#...",
        "....#.#....",
        ".....#.....",
        "....#.#....",
        "...#...#...",
        "..#.....#..",
        ".#.......#.",
        "...........")
    val Plus = pixels("plus",
        "...........",
        ".....#.....",
        ".....#.....",
        ".....#.....",
        ".....#.....",
        ".#########.",
        ".....#.....",
        ".....#.....",
        ".....#.....",
        ".....#.....",
        "...........")
    val Check = pixels("check",
        "...........",
        "...........",
        "...........",
        ".........#.",
        "........#..",
        ".#.....#...",
        "..#...#....",
        "...#.#.....",
        "....#......",
        "...........",
        "...........")
    val ChevronDown = pixels("chevron-down",
        "...........",
        "...........",
        "...........",
        ".#.......#.",
        "..#.....#..",
        "...#...#...",
        "....#.#....",
        ".....#.....",
        "...........",
        "...........",
        "...........")
    val ChevronRight = pixels("chevron-right",
        "...........",
        "...........",
        "...#.......",
        "....#......",
        ".....#.....",
        "......#....",
        ".....#.....",
        "....#......",
        "...#.......",
        "...........",
        "...........")
    /** Settings as two sliders. */
    val Settings = pixels("settings",
        "...........",
        "...........",
        "..###......",
        "###.#######",
        "..###......",
        "...........",
        "......###..",
        "#######.###",
        "......###..",
        "...........",
        "...........")
    val Mic = pixels("mic",
        "....###....",
        "...#...#...",
        "...#...#...",
        "...#...#...",
        "...#...#...",
        ".#..###..#.",
        ".#.......#.",
        "..#.....#..",
        "...#####...",
        ".....#.....",
        "...#####...")
    /** Try the app: a play triangle. */
    val Play = pixels("play",
        "...........",
        "..#........",
        "..##.......",
        "..#.#......",
        "..#..#.....",
        "..#...#....",
        "..#..#.....",
        "..#.#......",
        "..##.......",
        "..#........",
        "...........")
    /** Build: a starburst. */
    val Spark = pixels("spark",
        "...........",
        ".....#.....",
        "..#..#..#..",
        "...#.#.#...",
        "....###....",
        ".####.####.",
        "....###....",
        "...#.#.#...",
        "..#..#..#..",
        ".....#.....",
        "...........")
    /** Publish: an arrow into a tray. */
    val Publish = pixels("publish",
        ".....#.....",
        ".....#.....",
        ".....#.....",
        "..#..#..#..",
        "...#.#.#...",
        "....###....",
        ".....#.....",
        "...........",
        "#.........#",
        "#.........#",
        "###########")
    val History = pixels("history",
        "...........",
        "...#####...",
        "..#.....#..",
        ".#...#...#.",
        ".#...#...#.",
        ".#...###.#.",
        ".#.......#.",
        ".#.......#.",
        "..#.....#..",
        "...#####...",
        "...........")
    val Edit = pixels("edit",
        "........#..",
        ".......#.#.",
        "......#...#",
        ".....#...#.",
        "....#...#..",
        "...#...#...",
        "..#...#....",
        ".#...#.....",
        ".##.#......",
        ".###.......",
        "...........")
}

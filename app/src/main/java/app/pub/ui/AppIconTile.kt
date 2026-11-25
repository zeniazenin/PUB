package app.pub.ui

import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pub.core.AppIcon
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * An app's launcher icon: a tile in a quiet tint of one of the theme's colours (chosen from the app's name, so a draft
 * and the saved app match) with the app's pictogram drawn in that colour as pixel art. Every pictogram, designed by a
 * build or built in ([AppIcon]), is fitted to the same live area and rasterized onto a [PIXELS]-cell grid where an
 * outline is exactly one cell wide, then drawn as LCD cells: squares with a hairline gap, or round dots in the LCD type
 * pairing, over faintly lit unlit cells. Apps without one get a matching built-in pictogram (tuner, calculator) or a
 * neutral app symbol, never letters. Icons from older builds that are not pictograms keep their own drawing.
 */
@Composable
fun AppIconTile(name: String, icon: JsonObject?, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val colors = MaterialTheme.colorScheme
    val palette = listOf(
        colors.primary,
        lerp(colors.primary, colors.tertiary, .4f),
        lerp(colors.primary, colors.onSurface, .28f),
        lerp(colors.primary, colors.tertiary, .75f)
    )
    val tint = palette[(name.hashCode() ushr 1) % palette.size]
    val background = lerp(colors.surfaceContainer, tint, .1f)
    val pictogram = remember(icon, name) {
        icon?.takeIf { AppIcon.isPictogram(it) } ?: if (icon == null) builtIn(name) else null
    }
    val shape = RoundedCornerShape(size * PubShapes.tile(LocalPubStyle.current.corners))
    val dots = LocalPubStyle.current.font == "serif"
    Box(modifier.size(size).clip(shape).background(background).border(1.dp, lerp(colors.outlineVariant, tint, .15f).copy(alpha = .6f), shape)
        .testTag("app-icon"), contentAlignment = Alignment.Center) {
        if (pictogram != null) PixelPictogram(pictogram, tint, lerp(background, tint, SOFT_FILL), background, dots, Modifier.size(size * GLYPH_GRID), "$name icon")
        else NativeGraphic(icon!!, "$name icon", modifier = Modifier.size(size * .85f), maxDecodeSize = 256)
    }
}

/** Strength of a soft (duotone) fill: the tint over the tile. */
private const val SOFT_FILL = .3f

/** The pictogram's 48-unit grid as a share of the tile; its live area then fills about half of the tile. */
private const val GLYPH_GRID = .8f

/** Cells across the pictogram's grid: each is 2 grid units, the width of one outline, so lines are one pixel wide. */
private const val PIXELS = AppIcon.PIXELS

/** Strength of unlit cells, like the segments of an LCD that are off. */
private const val UNLIT = .07f

/**
 * [icon] as pixel art: lit cells in the tint (outlines and solid details) or the soft colour (duotone fills), drawn
 * with a hairline gap between cells, as squares or [dots]. Unlit cells of the glyph's area show faintly.
 */
@Composable
private fun PixelPictogram(icon: JsonObject, tint: Color, soft: Color, background: Color, dots: Boolean, modifier: Modifier, description: String) {
    val tintArgb = tint.toArgb(); val softArgb = soft.toArgb()
    val cells = remember(icon, tintArgb, softArgb) { rasterize(icon, tintArgb, softArgb) }
    val unlit = lerp(background, tint, UNLIT)
    Canvas(modifier.semantics { contentDescription = description }) {
        val cell = size.minDimension / PIXELS
        val gap = cell * if (dots) .22f else .12f
        val left = (size.width - cell * PIXELS) / 2; val top = (size.height - cell * PIXELS) / 2
        val box = Size(cell - gap, cell - gap)
        val radius = if (dots) CornerRadius(box.width / 2) else CornerRadius(cell * .08f)
        for (y in 0 until PIXELS) for (x in 0 until PIXELS) {
            val argb = cells[y * PIXELS + x]
            val color = if (argb == 0) { if (!inLiveArea(x, y)) continue; unlit } else Color(argb)
            drawRoundRect(color, Offset(left + x * cell + gap / 2, top + y * cell + gap / 2), box, radius)
        }
    }
}

/** Unlit cells are drawn only inside the live area, so the glyph sits in a small LCD panel rather than a full grid. */
private fun inLiveArea(x: Int, y: Int): Boolean {
    val from = (AppIcon.LIVE_MIN / 2).toInt(); val to = (AppIcon.LIVE_MAX / 2).toInt()
    return x in from until to && y in from until to
}

/**
 * The fitted pictogram drawn without anti-aliasing onto a [PIXELS] x [PIXELS] grid, outlines one cell wide. Returns one
 * ARGB colour per cell, 0 where nothing is lit. "surface" fills clear the cells under them.
 */
internal fun rasterize(icon: JsonObject, tint: Int, soft: Int): IntArray {
    val bitmap = androidx.core.graphics.createBitmap(PIXELS, PIXELS)
    try {
        val canvas = android.graphics.Canvas(bitmap)
        val fit = AppIcon.fit(icon, AppIcon.STROKE / 2.0)
        val scale = fit[0].toFloat()
        canvas.scale(PIXELS.toFloat() / AppIcon.GRID, PIXELS.toFloat() / AppIcon.GRID)
        canvas.translate(fit[1].toFloat(), fit[2].toFloat())
        canvas.scale(scale, scale)
        val paint = Paint().apply { isAntiAlias = false; isDither = false; strokeCap = Paint.Cap.SQUARE; strokeJoin = Paint.Join.MITER }
        val clear = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        for (element in icon["shapes"].asJsonArray) {
            val shape = element.asJsonObject
            val path = if (shape["type"].asString == "path") graphicShapePath(shape) else null
            val fill = shape["fill"]?.asString
            if (shape["type"].asString != "line" && visible(fill)) {
                paint.style = Paint.Style.FILL
                paint.xfermode = if (fill == "surface") clear else null
                paint.color = if (fill!!.endsWith("Container")) soft else tint
                drawPictogramShape(canvas, shape, path, paint)
                paint.xfermode = null
            }
            // A missing stroke is drawn: outlines are the style's default. One cell wide whatever the fit.
            val stroke = shape["stroke"]?.asString
            if (stroke == null || visible(stroke)) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = AppIcon.STROKE / scale
                paint.color = tint
                drawPictogramShape(canvas, shape, path, paint)
            }
        }
        val pixels = IntArray(PIXELS * PIXELS)
        bitmap.getPixels(pixels, 0, PIXELS, 0, 0, PIXELS, PIXELS)
        for (i in pixels.indices) if (android.graphics.Color.alpha(pixels[i]) < 128) pixels[i] = 0 else pixels[i] = pixels[i] or (0xFF shl 24)
        return pixels
    } finally { bitmap.recycle() }
}

/** A colour the model gave is drawn unless it is absent, "transparent" or a fully transparent hex value. */
private fun visible(color: String?): Boolean = when {
    color == null || color == "transparent" -> false
    color.length == 9 && color.startsWith("#") -> color.substring(1, 3) != "00"
    else -> true
}

private fun drawPictogramShape(canvas: android.graphics.Canvas, shape: JsonObject, path: android.graphics.Path?, paint: Paint) {
    fun f(key: String) = shape[key].asFloat
    when (shape["type"].asString) {
        "rect" -> { val rx = shape["rx"]?.asFloat ?: 0f; canvas.drawRoundRect(f("x"), f("y"), f("x") + f("width"), f("y") + f("height"), rx, rx, paint) }
        "ellipse" -> canvas.drawOval(f("cx") - f("rx"), f("cy") - f("ry"), f("cx") + f("rx"), f("cy") + f("ry"), paint)
        "line" -> canvas.drawLine(f("x1"), f("y1"), f("x2"), f("y2"), paint)
        "path" -> path?.let { canvas.drawPath(it, paint) }
    }
}

/** Built-in pictograms for apps without an icon, on the same grid and in the same style as designed ones. */
private fun builtIn(name: String): JsonObject = JsonParser.parseString(when {
    name.contains("tuner", ignoreCase = true) -> TUNER
    name.contains("calc", ignoreCase = true) || name.contains("tip", ignoreCase = true) -> CALCULATOR
    else -> GENERIC
}).asJsonObject

/** A tuning fork, sounding. */
private const val TUNER = """{"type":"vector","width":48,"height":48,"shapes":[
    {"id":"fork","type":"path","commands":[["M",19,9],["L",19,21],["Q",19,27,24,27],["Q",29,27,29,21],["L",29,9]],"fill":"transparent","stroke":"primary"},
    {"id":"stem","type":"line","x1":24,"y1":27,"x2":24,"y2":39,"fill":"transparent","stroke":"primary"},
    {"id":"left","type":"path","commands":[["M",13.5,13],["Q",10.5,17,13.5,21]],"fill":"transparent","stroke":"primary"},
    {"id":"right","type":"path","commands":[["M",34.5,13],["Q",37.5,17,34.5,21]],"fill":"transparent","stroke":"primary"}]}"""

/** A calculator: a soft body, a display line and four keys. */
private const val CALCULATOR = """{"type":"vector","width":48,"height":48,"shapes":[
    {"id":"body","type":"rect","x":14,"y":8,"width":20,"height":32,"rx":4,"fill":"primaryContainer","stroke":"primary"},
    {"id":"display","type":"line","x1":19,"y1":15,"x2":29,"y2":15,"fill":"transparent","stroke":"primary"},
    {"id":"k1","type":"ellipse","cx":20,"cy":23.5,"rx":1.5,"ry":1.5,"fill":"primary","stroke":"transparent"},
    {"id":"k2","type":"ellipse","cx":28,"cy":23.5,"rx":1.5,"ry":1.5,"fill":"primary","stroke":"transparent"},
    {"id":"k3","type":"ellipse","cx":20,"cy":31.5,"rx":1.5,"ry":1.5,"fill":"primary","stroke":"transparent"},
    {"id":"k4","type":"ellipse","cx":28,"cy":31.5,"rx":1.5,"ry":1.5,"fill":"primary","stroke":"transparent"}]}"""

/** A neutral "app" symbol: three rounded squares, one of them soft, and a circle. */
private const val GENERIC = """{"type":"vector","width":48,"height":48,"shapes":[
    {"id":"a","type":"rect","x":11,"y":11,"width":11,"height":11,"rx":3,"fill":"transparent","stroke":"primary"},
    {"id":"b","type":"rect","x":26,"y":11,"width":11,"height":11,"rx":3,"fill":"primaryContainer","stroke":"primary"},
    {"id":"c","type":"rect","x":11,"y":26,"width":11,"height":11,"rx":3,"fill":"transparent","stroke":"primary"},
    {"id":"d","type":"ellipse","cx":31.5,"cy":31.5,"rx":5.5,"ry":5.5,"fill":"transparent","stroke":"primary"}]}"""

package app.pub.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
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
 * An app's launcher icon: a rounded tile in one of the theme's tints (chosen from the app's name, so a draft and the
 * saved app match) with the app's pictogram drawn in that tint. Every pictogram, designed by a build or built in, is
 * drawn the same way ([AppIcon]): one colour and even outlines. Apps without one get a matching built-in pictogram
 * (tuner, calculator) or a neutral app symbol, never letters. Icons from older builds that are not pictograms keep
 * their own drawing.
 */
@Composable
fun AppIconTile(name: String, icon: JsonObject?, modifier: Modifier = Modifier, size: Dp = 80.dp) {
    val colors = MaterialTheme.colorScheme
    val palette = listOf(
        colors.primary,
        lerp(colors.primary, colors.tertiary, .4f),
        lerp(colors.primary, colors.onSurface, .28f),
        lerp(colors.primary, colors.tertiary, .75f)
    )
    val tint = palette[(name.hashCode() ushr 1) % palette.size]
    val background = lerp(colors.surface, tint, .15f)
    val pictogram = remember(icon, name) {
        icon?.takeIf { AppIcon.isPictogram(it) } ?: if (icon == null) builtIn(name) else null
    }
    val corner = PubShapes.tile(LocalPubStyle.current.corners)
    Box(modifier.size(size).clip(RoundedCornerShape(size * corner)).background(background).testTag("app-icon"), contentAlignment = Alignment.Center) {
        if (pictogram != null) Pictogram(pictogram, tint, background, Modifier.size(size * .7f), "$name icon")
        else NativeGraphic(icon!!, "$name icon", modifier = Modifier.size(size * .85f), maxDecodeSize = 256)
    }
}

@Composable
private fun Pictogram(icon: JsonObject, tint: Color, background: Color, modifier: Modifier, description: String) {
    val shapes = remember(icon) { icon["shapes"].asJsonArray.map { it.asJsonObject } }
    val paths = remember(icon) { shapes.map { if (it["type"].asString == "path") graphicShapePath(it) else null } }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND } }
    Canvas(modifier.semantics { contentDescription = description }) {
        val scale = size.minDimension / AppIcon.GRID
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val save = native.save()
            try {
                native.translate((size.width - AppIcon.GRID * scale) / 2, (size.height - AppIcon.GRID * scale) / 2)
                native.scale(scale, scale)
                shapes.forEachIndexed { index, shape ->
                    val line = shape["type"].asString == "line"
                    val fill = shape["fill"]?.asString
                    if (!line && visible(fill)) {
                        paint.style = Paint.Style.FILL
                        paint.color = (if (fill == "surface") background else tint).toArgb()
                        drawPictogramShape(native, shape, paths[index], paint)
                    }
                    // A missing stroke is drawn: outlines are the style's default.
                    val stroke = shape["stroke"]?.asString
                    if (stroke == null || visible(stroke)) {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = AppIcon.STROKE
                        paint.color = tint.toArgb()
                        drawPictogramShape(native, shape, paths[index], paint)
                    }
                }
            } finally { native.restoreToCount(save) }
        }
    }
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

private const val TUNER = """{"type":"vector","width":48,"height":48,"shapes":[
    {"id":"s1","type":"line","x1":13,"y1":7,"x2":13,"y2":41},{"id":"s2","type":"line","x1":24,"y1":7,"x2":24,"y2":41},
    {"id":"s3","type":"line","x1":35,"y1":7,"x2":35,"y2":41},
    {"id":"k1","type":"ellipse","cx":13,"cy":17,"rx":4,"ry":4,"fill":"surface","stroke":"primary"},
    {"id":"k2","type":"ellipse","cx":24,"cy":31,"rx":4,"ry":4,"fill":"surface","stroke":"primary"},
    {"id":"k3","type":"ellipse","cx":35,"cy":22,"rx":4,"ry":4,"fill":"surface","stroke":"primary"}]}"""

private const val CALCULATOR = """{"type":"vector","width":48,"height":48,"shapes":[
    {"id":"body","type":"rect","x":12,"y":6,"width":24,"height":36,"rx":4,"fill":"transparent","stroke":"primary"},
    {"id":"screen","type":"rect","x":17,"y":11,"width":14,"height":7,"rx":1.5,"fill":"transparent","stroke":"primary"},
    {"id":"k1","type":"ellipse","cx":19,"cy":25,"rx":1.6,"ry":1.6,"fill":"primary","stroke":"transparent"},
    {"id":"k2","type":"ellipse","cx":29,"cy":25,"rx":1.6,"ry":1.6,"fill":"primary","stroke":"transparent"},
    {"id":"k3","type":"ellipse","cx":19,"cy":31,"rx":1.6,"ry":1.6,"fill":"primary","stroke":"transparent"},
    {"id":"k4","type":"ellipse","cx":29,"cy":31,"rx":1.6,"ry":1.6,"fill":"primary","stroke":"transparent"},
    {"id":"k5","type":"ellipse","cx":19,"cy":37,"rx":1.6,"ry":1.6,"fill":"primary","stroke":"transparent"},
    {"id":"k6","type":"ellipse","cx":29,"cy":37,"rx":1.6,"ry":1.6,"fill":"primary","stroke":"transparent"}]}"""

/** A neutral "app" symbol: four rounded squares. */
private const val GENERIC = """{"type":"vector","width":48,"height":48,"shapes":[
    {"id":"a","type":"rect","x":9,"y":9,"width":12,"height":12,"rx":3,"fill":"transparent","stroke":"primary"},
    {"id":"b","type":"rect","x":27,"y":9,"width":12,"height":12,"rx":3,"fill":"transparent","stroke":"primary"},
    {"id":"c","type":"rect","x":9,"y":27,"width":12,"height":12,"rx":3,"fill":"transparent","stroke":"primary"},
    {"id":"d","type":"rect","x":27,"y":27,"width":12,"height":12,"rx":3,"fill":"transparent","stroke":"primary"}]}"""

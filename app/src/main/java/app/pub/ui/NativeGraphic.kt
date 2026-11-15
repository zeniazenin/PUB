package app.pub.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.pub.core.GraphicAsset
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.min

/** Draws only the closed GraphicAsset format. No SVG interpreter, URLs, file access, or WebView. */
@Composable
fun NativeGraphic(
    asset: JsonObject,
    description: String,
    highlight: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
    maxDecodeSize: Int = 1024
) {
    val type = asset["type"]?.takeIf { it.isJsonPrimitive }?.asString
    if (type == "image") {
        val data = asset["data"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val buildStatus=LocalBuildGraphicStatus.current
        val bitmap by produceState<Bitmap?>(null, asset, data, buildStatus, maxDecodeSize) {
            value = null
            // Even the cache lookup hashes the (multi-MB) base64 string the first time, so it runs off main too.
            if (data != null) value = withContext(Dispatchers.IO) {
                GraphicBitmaps.get(data, maxDecodeSize) ?: runCatching {
                    GraphicAsset.validate(asset)
                    decodeGraphicPreview(data, maxDecodeSize)
                }.getOrNull()?.also { GraphicBitmaps.put(data, maxDecodeSize, it) }
            }
            buildStatus?.invoke(asset,value!=null)
        }
        bitmap?.let {
            Image(it.asImageBitmap(), description, modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Fit)
        } ?: Text(if (data == null) "Image has not been generated yet" else "Image unavailable", modifier.semantics { contentDescription = description }, style = MaterialTheme.typography.bodySmall)
        return
    }
    val valid = remember(asset) { runCatching { GraphicAsset.validate(asset); type == "vector" }.getOrDefault(false) }
    if (!valid) {
        Text("Graphic unavailable", modifier.semantics { contentDescription = description }, style = MaterialTheme.typography.bodySmall)
        return
    }
    val width = asset["width"].asFloat.coerceAtLeast(.001f)
    val height = asset["height"].asFloat.coerceAtLeast(.001f)
    // Parsed once per asset: path geometry, ids and hex colors are not re-read from JSON on every draw.
    val shapes = remember(asset) { asset["shapes"].asJsonArray.map { element ->
        val shape = element.asJsonObject
        val isLine = shape["type"].asString == "line"
        DrawingShape(shape, if (shape["type"].asString == "path") shapePath(shape) else null, shape["id"].asString, isLine,
            shapeColor(shape["fill"]?.asString ?: if (isLine) "transparent" else "onSurface"),
            shapeColor(shape["stroke"]?.asString ?: if (isLine) "outline" else "transparent"),
            shape["strokeWidth"]?.asFloat ?: 1f)
    } }
    val actualHighlights = remember(shapes, highlight) { highlight.intersect(shapes.mapTo(HashSet()) { it.id }) }
    val accessibleDescription = description + if (actualHighlights.isEmpty()) "" else ". Highlighted: ${actualHighlights.sorted().joinToString(", ")}."
    val colors = MaterialTheme.colorScheme
    val palette = mapOf("primary" to colors.primary.toArgb(), "surface" to colors.surface.toArgb(),
        "onSurface" to colors.onSurface.toArgb(), "outline" to colors.outline.toArgb(), "transparent" to Color.Transparent.toArgb())
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND } }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val availableWidth = if (maxWidth.value.isFinite()) maxWidth else 320.dp
        val viewHeight = (availableWidth / (width / height)).coerceIn(120.dp, 360.dp)
        Canvas(Modifier.fillMaxWidth().height(viewHeight).semantics { contentDescription = accessibleDescription }) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val scale = min(size.width / width, size.height / height)
                val save = native.save()
                try {
                    native.translate((size.width - width * scale) / 2, (size.height - height * scale) / 2)
                    native.scale(scale, scale)
                    native.clipRect(0f, 0f, width, height)
                    shapes.forEach { drawing ->
                        val selected = drawing.id in actualHighlights
                        val isLine = drawing.isLine
                        val fill = if (selected) palette.getValue("primary") else drawing.fill.resolve(palette)
                        val stroke = if (selected) palette.getValue("primary") else drawing.stroke.resolve(palette)
                        val strokeWidth = drawing.strokeWidth
                        if (!isLine && android.graphics.Color.alpha(fill) != 0) {
                            paint.style = Paint.Style.FILL; paint.color = fill
                            drawShape(native, drawing, paint)
                        }
                        if (android.graphics.Color.alpha(stroke) != 0 && (selected || strokeWidth > 0)) {
                            paint.style = Paint.Style.STROKE; paint.color = stroke
                            paint.strokeWidth = if (selected) maxOf(3f, strokeWidth + 2f) else strokeWidth
                            drawShape(native, drawing, paint)
                        }
                    }
                } finally { native.restoreToCount(save) }
            }
        }
    }
}

private class DrawingShape(val source: JsonObject, val path: Path?, val id: String, val isLine: Boolean,
    val fill: ShapeColor, val stroke: ShapeColor, val strokeWidth: Float)
/** A theme palette key, resolved at draw time so theme changes apply, or a hex color parsed once. */
private class ShapeColor(private val paletteKey: String?, private val argb: Int) {
    fun resolve(palette: Map<String, Int>): Int = if (paletteKey != null) palette[paletteKey] ?: argb else argb
}
private val PALETTE_KEYS = setOf("primary", "surface", "onSurface", "outline", "transparent")
private fun shapeColor(value: String) = if (value in PALETTE_KEYS) ShapeColor(value, 0)
    else ShapeColor(null, runCatching { android.graphics.Color.parseColor(value) }.getOrDefault(0))

/**
 * Decoded generated PNGs, keyed by their base64 data (exact content match) and decode size. Entries go
 * away with the package that holds the data, so returning to the launcher or reopening an app reuses
 * them instead of base64-decoding, checksum-validating and decoding the image again.
 */
private object GraphicBitmaps {
    private val cache = Collections.synchronizedMap(WeakHashMap<String, MutableMap<Int, Bitmap>>())
    fun get(data: String, size: Int): Bitmap? = cache[data]?.get(size)
    fun put(data: String, size: Int, bitmap: Bitmap) { synchronized(cache) { cache.getOrPut(data) { java.util.concurrent.ConcurrentHashMap() }[size] = bitmap } }
}
private fun JsonObject.f(key: String) = this[key].asFloat
private fun drawShape(canvas: android.graphics.Canvas, drawing: DrawingShape, paint: Paint) {
    val shape = drawing.source
    when (shape["type"].asString) {
        "rect" -> {
            val x = shape.f("x"); val y = shape.f("y"); val rx = shape["rx"]?.asFloat ?: 0f
            canvas.drawRoundRect(x, y, x + shape.f("width"), y + shape.f("height"), rx, rx, paint)
        }
        "ellipse" -> {
            val x = shape.f("cx"); val y = shape.f("cy"); val rx = shape.f("rx"); val ry = shape.f("ry")
            canvas.drawOval(x - rx, y - ry, x + rx, y + ry, paint)
        }
        "line" -> canvas.drawLine(shape.f("x1"), shape.f("y1"), shape.f("x2"), shape.f("y2"), paint)
        "path" -> drawing.path?.let { canvas.drawPath(it, paint) }
        "text" -> { paint.textSize = shape.f("size"); canvas.drawText(shape["text"].asString, shape.f("x"), shape.f("y"), paint) }
    }
}
private fun shapePath(shape: JsonObject) = Path().apply {
    shape["commands"].asJsonArray.forEach { element ->
        val command = element.asJsonArray
        when (command[0].asString) {
            "M" -> moveTo(command[1].asFloat, command[2].asFloat)
            "L" -> lineTo(command[1].asFloat, command[2].asFloat)
            "C" -> cubicTo(command[1].asFloat, command[2].asFloat, command[3].asFloat, command[4].asFloat, command[5].asFloat, command[6].asFloat)
            "Q" -> quadTo(command[1].asFloat, command[2].asFloat, command[3].asFloat, command[4].asFloat)
            "Z" -> close()
        }
    }
}

/** Header bounds are checked before allocating pixels, then power-of-two sampling caps either side at [maxSize]. */
private fun decodeGraphicPreview(data: String, maxSize: Int): Bitmap? {
    val bytes = GraphicAsset.decodePng(data)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..GraphicAsset.MAX_IMAGE_DIMENSION || bounds.outHeight !in 1..GraphicAsset.MAX_IMAGE_DIMENSION || bounds.outWidth.toLong() * bounds.outHeight > GraphicAsset.MAX_IMAGE_PIXELS) return null
    var sample = 1
    while ((bounds.outWidth + sample - 1) / sample > maxSize || (bounds.outHeight + sample - 1) / sample > maxSize) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
        inSampleSize = sample; inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888
    })?.takeIf { it.width <= maxSize && it.height <= maxSize }
}

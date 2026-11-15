package app.pub.hardware

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.exifinterface.media.ExifInterface
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import app.pub.core.JpegPhoto

/** Photos belong to a host-selected project. Scripts receive opaque asset names, never paths. */
class ProjectMedia(context: Context) {
    private val appContext = context.applicationContext
    // Preserve the supplied storage context without retaining an Activity or ContextWrapper.
    // Construction and later containment checks must use the same canonical parent.
    private val storageParent = context.filesDir.canonicalFile
    private val root = File(storageParent, "project-media").apply { check(mkdirs() || isDirectory) }

    fun directory(projectId: String): File = synchronized(lock) {
        checkedDirectory(projectId).apply { check(mkdirs() || isDirectory) { "Photo storage is unavailable" } }
    }

    fun resolve(projectId: String, asset: String): File? = synchronized(lock) {
        if (!validProjectId(projectId) || !ASSET.matches(asset)) return@synchronized null
        val directory = checkedDirectory(projectId)
        val file = File(directory, asset)
        file.takeIf { it.isFile && it.length() in 1..MAX_PHOTO_BYTES && it.canonicalFile.parentFile == directory.canonicalFile }
    }

    /** Call once before opening any project, never from inventory or while a preview is active. */
    fun cleanupTemporary() = synchronized(lock) {
        val canonicalRoot = root.canonicalFile
        check(canonicalRoot == File(storageParent, "project-media")) { "Invalid photo storage root" }
        root.listFiles().orEmpty().forEach { entry ->
            // A symlink must never redirect cleanup to a saved project or outside photo storage.
            runCatching {
                if (entry.canonicalFile != File(canonicalRoot, entry.name)) return@runCatching
                val temporaryDirectory = (entry.name.startsWith("preview-") && isUuid(entry.name.removePrefix("preview-"))) ||
                    (entry.name.startsWith(".copy-") && isUuid(entry.name.removePrefix(".copy-")))
                if (temporaryDirectory && entry.isDirectory) {
                    deleteContainedTree(entry, canonicalRoot)
                } else if ((isUuid(entry.name) || entry.name == "guitar-tuner") && entry.isDirectory) {
                    entry.listFiles().orEmpty().filter { isTemporaryPhoto(it.name) }.forEach { temporary ->
                        if (temporary.canonicalFile == File(entry.canonicalFile, temporary.name) && temporary.isFile) temporary.delete()
                    }
                }
            }
        }
    }

    private fun deleteContainedTree(target: File, parent: File): Boolean {
        val canonical = target.canonicalFile
        if (canonical != File(parent.canonicalFile, target.name)) return false
        if (target.isDirectory) {
            val children = target.listFiles() ?: return false
            var removed = true
            children.forEach { if (!deleteContainedTree(it, canonical)) removed = false }
            if (!removed) return false
        }
        return target.delete()
    }

    /** Called before reopening a saved preview. Either the entire photo set is copied or none is. */
    fun copyPreview(fromId: String, toId: String) = synchronized(lock) {
        require(fromId.startsWith("preview-") && validProjectId(fromId)) { "Expected preview identity" }
        require(isUuid(toId)) { "Expected saved project identity" }
        val source = checkedDirectory(fromId)
        val destination = checkedDirectory(toId)
        val photos = source.listFiles().orEmpty().filter { it.isFile && ASSET.matches(it.name) }
        require(photos.size <= MAX_PHOTOS && photos.sumOf { it.length() } <= MAX_PROJECT_BYTES) { "Project photo quota exceeded" }
        require(photos.all { it.length() in 1..MAX_PHOTO_BYTES && it.canonicalFile.parentFile == source.canonicalFile }) { "Invalid photo asset" }
        if (photos.isEmpty()) return@synchronized
        check(!destination.exists() || destination.listFiles().orEmpty().isEmpty()) { "Saved photo directory is not empty" }
        val staging = File(root, ".copy-${UUID.randomUUID()}")
        check(staging.mkdir()) { "Could not prepare photo storage" }
        try {
            photos.forEach { photo ->
                FileOutputStream(File(staging, photo.name)).use { output ->
                    photo.inputStream().use { it.copyTo(output) }
                    output.fd.sync()
                }
            }
            if (destination.exists()) check(destination.delete())
            check(staging.renameTo(destination)) { "Could not save project photos" }
        } finally { if (staging.exists()) staging.deleteRecursively() }
    }

    fun delete(projectId: String) = synchronized(lock) {
        val directory = checkedDirectory(projectId)
        check(!directory.exists() || directory.deleteRecursively()) { "Could not delete project photos" }
    }

    /** Public static camera metadata only; no frames, photos, location, or personal identifiers. */
    fun inventory(): JsonObject = CameraCatalog.inventory(appContext).apply {
        addProperty("present", appContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY))
        addProperty("maxSavedPhotosPerApp", MAX_PHOTOS)
        addProperty("maxSavedBytesPerApp", MAX_PROJECT_BYTES)
        addProperty("maxSavedBytesPerPhoto", MAX_PHOTO_BYTES)
        addProperty("preservesCaptureResolution", true)
        addProperty("jpegQualityChoices", "70,85,95,100; default 95")
    }
    private fun checkedDirectory(id: String): File {
        require(validProjectId(id)) { "Invalid project identity" }
        return File(root, id).also { require(it.canonicalFile.parentFile == root.canonicalFile) }
    }

    companion object {
        const val MAX_PHOTOS = 20
        const val MAX_PROJECT_BYTES = 1024L * 1024 * 1024
        const val MAX_PHOTO_BYTES = 256L * 1024 * 1024
        private const val MAX_RAW_BYTES = MAX_PHOTO_BYTES
        private val ASSET = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg")
        private val lock = Any()
        private fun isUuid(id: String) = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
        private fun validProjectId(id: String) = id == "guitar-tuner" || isUuid(id) || (id.startsWith("preview-") && isUuid(id.removePrefix("preview-")))
        private fun isTemporaryPhoto(name: String): Boolean =
            (name.startsWith(".capture-") && name.endsWith(".jpg") && isUuid(name.removePrefix(".capture-").removeSuffix(".jpg"))) ||
                (name.startsWith(".") && name.endsWith(".jpg.pending") && isUuid(name.removePrefix(".").removeSuffix(".jpg.pending")))

        internal fun beginCapture(directory: File): File = synchronized(lock) {
            check(directory.isDirectory) { "This project is no longer open" }
            // Only one host camera dialog can capture for a project. Remove abandoned temporary writes.
            directory.listFiles().orEmpty().filter { it.isFile && (it.name.startsWith(".capture-") || it.name.endsWith(".jpg.pending")) }.forEach { it.delete() }
            checkQuota(directory, 0, addingPhoto = true)
            File(directory, ".capture-${UUID.randomUUID()}.jpg").also { check(it.createNewFile()) }
        }

        /** Preserve camera JPEG pixels/quality, sanitize metadata, then publish atomically. No full-size bitmap. */
        internal fun finishCapture(directory: File, raw: File): JsonObject = synchronized(lock) {
            require(raw.canonicalFile.parentFile == directory.canonicalFile && raw.name.startsWith(".capture-"))
            check(directory.isDirectory && raw.isFile) { "Capture cancelled" }
            val asset = "${UUID.randomUUID()}.jpg"
            val output = File(directory, ".$asset.pending")
            try {
                check(raw.length() in 1..MAX_RAW_BYTES) { "Photo exceeds the 256 MiB file limit. Choose a lower JPEG quality or resolution." }
                val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(raw.path, dimensions)
                check(dimensions.outWidth > 0 && dimensions.outHeight > 0 && dimensions.outMimeType == "image/jpeg") { "Camera returned an unreadable JPEG" }
                val orientation = ExifInterface(raw).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL).let { if (it in 1..8) it else 1 }
                FileOutputStream(output).use { file ->
                    raw.inputStream().use { JpegPhoto.copyWithoutPersonalMetadata(it, file, orientation) }
                    file.fd.sync()
                }
                check(output.length() in 1..MAX_PHOTO_BYTES) { "Photo exceeds the 256 MiB file limit. Choose a lower JPEG quality." }
                checkQuota(directory, output.length(), addingPhoto = true)
                check(output.renameTo(File(directory, asset))) { "Could not save captured photo" }
                val swap = orientation in 5..8
                JsonObject().apply {
                    addProperty("asset", asset)
                    addProperty("width", if (swap) dimensions.outHeight else dimensions.outWidth)
                    addProperty("height", if (swap) dimensions.outWidth else dimensions.outHeight)
                }
            } finally { raw.delete(); output.delete() }
        }

        /** Only UI thumbnails are sampled. The stored original remains at camera resolution. */
        fun decodePreview(file: File): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while ((max(bounds.outWidth, bounds.outHeight).toLong() + sample - 1) / sample > 1600) sample *= 2
            val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            try {
                val orientation = ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                val matrix = Matrix().apply {
                    when (orientation) {
                        2 -> setScale(-1f, 1f)
                        3 -> setRotate(180f)
                        4 -> setScale(1f, -1f)
                        5 -> { setRotate(90f); postScale(-1f, 1f) }
                        6 -> setRotate(90f)
                        7 -> { setRotate(-90f); postScale(-1f, 1f) }
                        8 -> setRotate(270f)
                    }
                }
                if (matrix.isIdentity) return bitmap
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) bitmap.recycle()
                return rotated
            } catch (failure: Exception) { bitmap.recycle(); throw failure }
        }

        private fun checkQuota(directory: File, additionalBytes: Long, addingPhoto: Boolean) {
            val saved = directory.listFiles().orEmpty().filter { it.isFile && ASSET.matches(it.name) }
            check(saved.size + (if (addingPhoto) 1 else 0) <= MAX_PHOTOS) { "This app has reached its 20-photo limit." }
            check(saved.sumOf { it.length() } + additionalBytes <= MAX_PROJECT_BYTES) { "This app has reached its 1 GiB photo storage limit. Delete photos or use a lower JPEG quality." }
        }
    }
}

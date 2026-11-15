package app.pub

import android.graphics.Bitmap
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pub.hardware.ProjectMedia
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProjectMediaDeviceTest {
    private val media = ProjectMedia(InstrumentationRegistry.getInstrumentation().targetContext)
    private fun id() = UUID.randomUUID().toString()
    private fun fixture(file: File) {
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) } }
        finally { bitmap.recycle() }
    }

    @Test fun assetResolutionIsScopedAndRejectsTraversal() {
        val first = id(); val second = id(); val asset = "${id()}.jpg"
        try {
            val firstDirectory = media.directory(first)
            fixture(File(firstDirectory, asset))
            assertNotNull(media.resolve(first, asset))
            assertNull(media.resolve(second, asset))
            assertNull(media.resolve(first, "../$asset"))
            assertNull(media.resolve(first, "/$asset"))
            assertNull(media.resolve("../$first", asset))
            assertThrows(IllegalArgumentException::class.java) { media.directory("../$first") }
        } finally { media.delete(first); media.delete(second) }
    }

    @Test fun cameraInventoryReportsPublicEndpointsWithinReplyBudget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val inventory = media.inventory()
        val ids = context.getSystemService(android.hardware.camera2.CameraManager::class.java).cameraIdList.toSet()
        assertEquals(ids.size, inventory["cameraCount"].asInt)
        assertEquals(ids, inventory.getAsJsonArray("reportedCameraIds").map { it.asString }.toSet())
        assertTrue(inventory.toString().toByteArray(Charsets.UTF_8).size < 24 * 1024)
        inventory.getAsJsonArray("cameras").forEach { entry ->
            val camera = entry.asJsonObject
            assertTrue(camera["logicalCameraId"].asString in ids)
            assertTrue(camera["facing"].asString in setOf("front", "back", "external"))
            assertTrue(camera.has("selectionKey"))
            assertFalse(camera.has("serialNumber"))
        }
    }

    @Test fun photoIsUprightAndMetadataIsRemoved() {
        val project = id()
        try {
            val directory = media.directory(project)
            val raw = ProjectMedia.beginCapture(directory)
            fixture(raw)
            ExifInterface(raw).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_USER_COMMENT, "private fixture metadata")
                setLatLong(41.0, -71.0)
                saveAttributes()
            }
            val result = ProjectMedia.finishCapture(directory, raw)
            val asset = result["asset"].asString
            assertFalse(raw.exists())
            assertEquals(40, result["width"].asInt)
            assertEquals(80, result["height"].asInt)
            assertEquals(setOf("asset", "width", "height"), result.keySet())
            val saved = media.resolve(project, asset)!!
            val decoded = ProjectMedia.decodePreview(saved)!!
            assertEquals(40, decoded.width); assertEquals(80, decoded.height); decoded.recycle()
            val exif = ExifInterface(saved)
            assertEquals(ExifInterface.ORIENTATION_ROTATE_90, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
            assertNull(exif.getAttribute(ExifInterface.TAG_USER_COMMENT))
            assertNull(exif.latLong)
            assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith(".") })
        } finally { media.delete(project) }
    }

    @Test fun fullResolutionAndJpegQualitySurviveSaveAndPreviewCopy() {
        val preview = "preview-${id()}"; val savedProject = id()
        try {
            val directory = media.directory(preview)
            val bitmap = Bitmap.createBitmap(4096, 3072, Bitmap.Config.ARGB_8888)
            // A deterministic textured row makes the fixture sensitive to JPEG recompression.
            val row = IntArray(4096) { x -> android.graphics.Color.rgb((x * 31) % 256, (x * 73) % 256, (x * 13) % 256) }
            repeat(bitmap.height) { y -> bitmap.setPixels(row, 0, row.size, 0, y, row.size, 1) }
            val lengths = mutableListOf<Long>()
            try {
                for (quality in listOf(70, 100)) {
                    val raw = ProjectMedia.beginCapture(directory)
                    raw.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)) }
                    val expected = java.io.ByteArrayOutputStream().also { output ->
                        raw.inputStream().use { app.pub.core.JpegPhoto.copyWithoutPersonalMetadata(it, output, 1) }
                    }.toByteArray()
                    val result = ProjectMedia.finishCapture(directory, raw)
                    assertEquals(4096, result["width"].asInt); assertEquals(3072, result["height"].asInt)
                    val asset = result["asset"].asString
                    val photo = media.resolve(preview, asset)!!
                    assertArrayEquals(expected, photo.readBytes()) // No resizing or second JPEG encode.
                    lengths.add(photo.length())
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(photo.path, bounds)
                    assertEquals(4096, bounds.outWidth); assertEquals(3072, bounds.outHeight)
                    val thumbnail = ProjectMedia.decodePreview(photo)!!
                    assertTrue(maxOf(thumbnail.width, thumbnail.height) <= 1600); thumbnail.recycle()
                }
            } finally { bitmap.recycle() }
            assertTrue("Maximum quality should retain more data", lengths[1] > lengths[0])
            media.copyPreview(preview, savedProject)
            directory.listFiles()!!.filter { it.extension == "jpg" }.forEach { photo ->
                assertArrayEquals(photo.readBytes(), media.resolve(savedProject, photo.name)!!.readBytes())
            }
        } finally { media.delete(preview); media.delete(savedProject) }
    }

    @Test fun savingPreviewCopiesOnlyCompletedAssetsAndDeletionIsIsolated() {
        val preview = "preview-${id()}"; val saved = id(); val asset = "${id()}.jpg"
        try {
            val source = media.directory(preview)
            fixture(File(source, asset))
            File(source, ".capture-abandoned.jpg").writeText("not a completed photo")
            media.copyPreview(preview, saved)
            assertArrayEquals(media.resolve(preview, asset)!!.readBytes(), media.resolve(saved, asset)!!.readBytes())
            assertEquals(1, media.directory(saved).listFiles()!!.size)
            media.delete(preview)
            assertNotNull(media.resolve(saved, asset))
            assertNull(media.resolve(preview, asset))
        } finally { media.delete(preview); media.delete(saved) }
    }

    @Test fun captureQuotaRejectsTwentyFirstPhotoAndInvalidRawIsCleaned() {
        val project = id()
        try {
            val directory = media.directory(project)
            val raw = ProjectMedia.beginCapture(directory)
            raw.writeText("not a JPEG")
            assertThrows(IllegalStateException::class.java) { ProjectMedia.finishCapture(directory, raw) }
            assertFalse(raw.exists())
            repeat(ProjectMedia.MAX_PHOTOS) { fixture(File(directory, "${id()}.jpg")) }
            assertThrows(IllegalStateException::class.java) { ProjectMedia.beginCapture(directory) }
            assertEquals(ProjectMedia.MAX_PHOTOS, directory.listFiles()!!.size)
        } finally { media.delete(project) }
    }

    @Test fun startupCleanupRemovesAbandonedWorkButPreservesSavedPhotos() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(context.cacheDir, "media-cleanup-${id()}").apply { check(mkdir()) }
        val isolated = ProjectMedia(object : ContextWrapper(context) { override fun getFilesDir() = sandbox })
        var testLink: File? = null
        try {
            val project = id(); val asset = "${id()}.jpg"
            val saved = isolated.directory(project)
            assertEquals(File(sandbox.canonicalFile, "project-media"), saved.canonicalFile.parentFile)
            fixture(File(saved, asset))
            val original = File(saved, asset).readBytes()
            val raw = File(saved, ".capture-${id()}.jpg").apply { writeText("partial capture") }
            val pending = File(saved, ".${id()}.jpg.pending").apply { writeText("partial encoding") }
            val unknown = File(saved, ".capture-not-a-uuid.jpg").apply { writeText("unrecognized file") }
            val preview = isolated.directory("preview-${id()}")
            fixture(File(preview, "${id()}.jpg"))
            val copying = File(saved.parentFile, ".copy-${id()}").apply { check(mkdir()) }
            fixture(File(copying, "${id()}.jpg"))
            val unrelated = File(saved.parentFile, ".copy-unknown").apply { check(mkdir()) }
            val linkedPreview = isolated.directory("preview-${id()}")
            val link = File(linkedPreview, "saved-link")
            testLink = link
            android.system.Os.symlink(saved.absolutePath, link.absolutePath)

            isolated.cleanupTemporary()

            assertFalse(preview.exists()); assertFalse(copying.exists())
            assertFalse(raw.exists()); assertFalse(pending.exists())
            assertTrue(unknown.exists()); assertTrue(unrelated.exists())
            assertArrayEquals(original, isolated.resolve(project, asset)!!.readBytes())
            // The recursive sweep refuses to traverse a link into another project.
            assertTrue(saved.isDirectory)
        } finally {
            // Test-created sandbox is a direct canonical child of the app's cache directory.
            testLink?.delete()
            check(sandbox.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            sandbox.deleteRecursively()
        }
    }
}

package app.pub

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pub.auth.ChatGptAuth
import app.pub.builder.VisualReview
import app.pub.core.ModelSelection
import app.pub.core.ProjectBundle
import app.pub.core.VisualReviewDecision
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class VisualReviewDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun png(width: Int = 32, height: Int = 48): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().use { out -> assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray() } }
        finally { bitmap.recycle() }
    }

    @Test fun acceptsBoundedPngAndRejectsWrongFormatSizeAndDimensions() {
        ChatGptAuth.validatePreviewPng(png())
        assertThrows(IllegalArgumentException::class.java) { ChatGptAuth.validatePreviewPng(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { ChatGptAuth.validatePreviewPng(ByteArray(64) { 1 }) }
        assertThrows(IllegalArgumentException::class.java) { ChatGptAuth.validatePreviewPng(ByteArray(ChatGptAuth.MAX_PREVIEW_PNG_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { ChatGptAuth.validatePreviewPng(png(ChatGptAuth.MAX_PREVIEW_DIMENSION + 1, 1)) }
    }

    @Test fun packageContextOmitsImageDataAndReviewKeepsScopeWithinHistoryBudget() {
        val encoded = java.util.Base64.getEncoder().encodeToString(png())
        val bundle = ProjectBundle.parse(JsonObject().apply {
            addProperty("name", "Review context fixture"); addProperty("description", "Fixture only")
            addProperty("source", "function dispatch(e){return JSON.stringify({ui:{type:'text',text:'Hello'},effects:[]});}")
            add("capabilities", JsonArray())
            add("assets", JsonObject().apply { add("art", JsonObject().apply {
                addProperty("type", "image"); addProperty("prompt", "A calm blue background"); addProperty("data", encoded)
            }) })
        }.toString())
        val data = VisualReview.packageContext(bundle)
        assertFalse(data.contains(encoded))
        assertFalse(data.contains("\"data\""))
        assertTrue(data.contains("A calm blue background"))
        assertTrue(data.contains("function dispatch"))
        val findings = VisualReview.formatDecision(VisualReviewDecision.parse(JsonObject().apply {
            addProperty("status", "fix"); addProperty("summary", "s".repeat(1000))
            add("issues", JsonArray().apply { repeat(5) { add("x".repeat(600)) } })
        }.toString()))
        assertTrue(findings.length <= 6_000)
        assertTrue(findings.endsWith(VisualReview.SCOPE_NOTE))
        assertTrue(findings.contains("visible fixes needed"))
        assertThrows(IllegalArgumentException::class.java) { VisualReview.formatFindings("x".repeat(100_000)) }
        assertThrows(IllegalArgumentException::class.java) { VisualReview.formatFindings("  ") }
    }

    /** Opt-in only: uploads the controlled UI fixture PNG and consumes existing ChatGPT plan usage. */
    @Test fun reviewActualCapturedPreviewWithExistingChatGptAccount() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Live vision review requires explicit pubLiveVisualReview=true", args.getString("pubLiveVisualReview") == "true")
        val screenshot = File(context.cacheDir, "build-visual-proof.png")
        val requirements = File(context.cacheDir, "build-visual-proof-prompt.txt")
        val result = File(context.cacheDir, "build-visual-proof-review.txt")
        for (file in listOf(screenshot, requirements, result)) {
            require(file.canonicalFile.parentFile == context.cacheDir.canonicalFile) { "Visual proof path escaped the cache directory" }
        }
        // Do not let an older successful result be confused with this attempt.
        result.delete()
        assertTrue("Run BuildVisualPreviewTest first to capture the controlled preview", screenshot.isFile)
        assertTrue("The screenshot requirement fixture is missing", requirements.isFile)
        assertTrue(screenshot.length() in 1..ChatGptAuth.MAX_PREVIEW_PNG_BYTES.toLong())
        assertTrue(requirements.length() in 1..16_000L)
        val bytes = screenshot.inputStream().use { input ->
            val out = ByteArrayOutputStream()
            val block = ByteArray(8192)
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                check(out.size() + count <= ChatGptAuth.MAX_PREVIEW_PNG_BYTES)
                out.write(block, 0, count)
            }
            out.toByteArray()
        }
        val request = requirements.readText() + "\nIn the JSON summary, quote the main heading exactly as it appears in the screenshot. If it cannot be read, say so."
        val auth = ChatGptAuth(context)
        assertNotNull("Connect ChatGPT before running the explicitly enabled live review", auth.accountLabel())
        auth.prepare()
        try {
            val models = auth.models()
            val selected = args.getString("pubVisualReviewModel") ?: ModelSelection.choose(models.map { ModelSelection.Model(it.id, it.name) }, null)
            assertTrue("The requested review model must belong to the account catalog", models.any { it.id == selected })
            // Omit source/labels deliberately: the main heading must come from image evidence,
            // not from copying a string out of the app's source code or package description.
            val review = auth.reviewPreview(selected, request,
                "{\"scope\":\"Controlled initial-screen visual test. No source code or UI labels are supplied; use the attached screenshot.\"}", bytes)
            val decision = VisualReviewDecision.parse(review)
            val formatted = VisualReview.formatDecision(decision)
            result.writeText(formatted)
            assertTrue("The model did not identify the known screenshot heading; inspect the local review result", decision.summary.contains("Guitar overview", ignoreCase = true))
            assertTrue(formatted.endsWith(VisualReview.SCOPE_NOTE))
        } finally { auth.cancel() }
    }
}

package app.pub.builder

import app.pub.auth.ChatGptAuth
import app.pub.core.ProjectBundle
import app.pub.core.VisualReviewDecision
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Validated visual decisions only. The caller owns consent, capture, cancellation and bounded repair. */
class VisualReview(private val auth: ChatGptAuth) {
    fun assess(model: String, request: String, bundle: ProjectBundle, pngBytes: ByteArray): VisualReviewDecision =
        VisualReviewDecision.parse(auth.reviewPreview(model, request, packageContext(bundle), pngBytes))

    fun review(model: String, request: String, bundle: ProjectBundle, pngBytes: ByteArray): String =
        formatDecision(assess(model, request, bundle, pngBytes))

    companion object {
        const val SCOPE_NOTE = "Initial visible screen only. This review is advisory; it does not test interactions, calculations, scrolling, permissions, hardware, saved data or other screen states."

        /** Preserve source for requirement checks; omit image binaries, full vector geometry and local app state. */
        fun packageContext(bundle: ProjectBundle): String {
            val data = JsonObject().apply {
            addProperty("name", bundle.name)
            addProperty("description", bundle.description)
            addProperty("source", bundle.source)
            add("capabilities", JsonArray().apply { bundle.capabilities.sorted().forEach { add(it) } })
            bundle.icon?.let { addProperty("icon", it) }
            add("assetSummaries", JsonObject().apply {
                bundle.assets.entrySet().forEach { (id, value) ->
                    val asset = value.asJsonObject
                    add(id, JsonObject().apply {
                        for (field in listOf("type", "prompt", "width", "height", "description")) asset[field]?.let { add(field, it.deepCopy()) }
                        asset["shapes"]?.takeIf { it.isJsonArray }?.let { addProperty("shapeCount", it.asJsonArray.size()) }
                        addProperty("binaryDataIncluded", false)
                    })
                }
            })
            addProperty("scope", "One initial visible screenshot; no app storage, captured-photo files or additional screens are attached. Source code is evidence of intent, not proof of runtime behavior.")
            }
            // JSON escaping can enlarge an otherwise valid source. Keep a labelled excerpt instead
            // of rejecting a legitimate app or silently claiming that its complete source was reviewed.
            var sourceLength = bundle.source.length
            while (data.toString().length > 120_000 && sourceLength > 0) {
                sourceLength /= 2
                data.addProperty("source", bundle.source.take(sourceLength))
                data.addProperty("sourceTruncated", true)
            }
            return data.toString().also { require(it.length <= 120_000) { "App review context exceeded the size limit" } }
        }

        fun formatFindings(findings: String): String = formatDecision(VisualReviewDecision.parse(findings))

        fun formatDecision(decision: VisualReviewDecision): String = buildString {
            append("Visual review — ")
            append(when (decision.status) {
                "pass" -> "no visible fixes identified"
                "fix" -> "visible fixes needed"
                else -> "screenshot could not be verified"
            })
            append("\n\n").append(decision.summary.trim())
            if (decision.issues.isNotEmpty()) {
                append("\n\n")
                decision.issues.forEachIndexed { index, issue ->
                    if (index > 0) append('\n')
                    append(index + 1).append(". ").append(issue.trim())
                }
            }
            append("\n\n").append(SCOPE_NOTE)
        }.also { check(it.length <= 6_000) { "Visual review exceeded its display limit" } }
    }
}

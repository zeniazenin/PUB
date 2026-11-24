package app.pub.builder

import app.pub.auth.ChatGptAuth
import app.pub.core.ChecksReport
import app.pub.core.ProjectBundle
import app.pub.core.ResearchBrief
import app.pub.core.UiPlan
import app.pub.core.VisualReviewDecision
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Validated visual decisions only. The caller owns consent, capture, cancellation and bounded repair. */
class VisualReview(private val auth: ChatGptAuth) {
    /** [brief], [checks] and [plan] are the draft's committed research, latest behavior checks and UI plan: review data. */
    fun assess(model: String, request: String, bundle: ProjectBundle, pngBytes: ByteArray,
               brief: ResearchBrief? = null, checks: ChecksReport? = null, plan: UiPlan? = null): VisualReviewDecision =
        VisualReviewDecision.parse(auth.reviewPreview(model, request, packageContext(bundle, brief, checks, plan), pngBytes))

    fun review(model: String, request: String, bundle: ProjectBundle, pngBytes: ByteArray): String =
        formatDecision(assess(model, request, bundle, pngBytes))

    companion object {
        /** In a formatted review's first line when the screenshot showed issues to fix. */
        const val FIXES_NEEDED = "visible fixes needed"
        const val SCOPE_NOTE = "Initial visible screen only. This review is advisory; it does not test interactions, calculations, scrolling, permissions, hardware, saved data or other screen states."

        /**
         * Preserve source for requirement checks; omit image binaries, full vector geometry and local app state.
         * The research brief and the first planned screen (the review rubric) and a behavior-check summary are included
         * as data. To stay within the context bound, the brief and summary shrink before the source is truncated.
         */
        fun packageContext(bundle: ProjectBundle, brief: ResearchBrief? = null, checks: ChecksReport? = null, plan: UiPlan? = null): String {
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
            brief?.let { add("researchBrief", JsonParser.parseString(it.toPromptJson())) }
            // The screen the app opens with, as planned: the screenshot shows only that one.
            plan?.let { add("uiPlan", JsonObject().apply {
                addProperty("layout", it.layout)
                add("firstScreen", JsonParser.parseString(it.toJson()).asJsonObject.getAsJsonArray("screens")[0])
                add("accessibility", JsonParser.parseString(it.toJson()).asJsonObject.getAsJsonArray("accessibility"))
            }) }
            checks?.let { report -> add("behaviorChecks", JsonObject().apply {
                addProperty("note", "PUB's own sandboxed interaction checks (fresh runtime, no storage or hardware, an empty simulated file folder, other effects ignored). Context only, not visible evidence.")
                addProperty("passed", report.passed); addProperty("total", report.total)
                add("failed", JsonArray().apply { report.failures().forEach { failure ->
                    add(JsonObject().apply { addProperty("name", failure.name); addProperty("detail", failure.detail) })
                } })
            }) }
            }
            // Least useful parts of the brief go first, then the check details, then the brief itself.
            val briefData = data.getAsJsonObject("researchBrief")
            for (field in listOf("sources", "comparables", "pitfalls", "assumptions", "logic", "acceptanceChecks")) {
                if (briefData == null || data.toString().length <= 120_000) break
                if (briefData.remove(field) != null) data.addProperty("researchBriefTrimmed", true)
            }
            if (data.toString().length > 120_000) data.getAsJsonObject("behaviorChecks")?.remove("failed")
            if (data.toString().length > 120_000 && data.remove("researchBrief") != null) {
                data.remove("researchBriefTrimmed"); data.addProperty("researchBriefOmitted", true)
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
                "fix" -> FIXES_NEEDED
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

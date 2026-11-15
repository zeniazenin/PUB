package app.pub.reports

import app.pub.BuildConfig
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Sends content reports ([app.pub.core.ContentReport]) to the developer's endpoint, BuildConfig.REPORT_ENDPOINT
 * (tools/report-endpoint). Builds without an endpoint cannot send reports; release builds always have one.
 */
object ReportSender {
    val available: Boolean get() = BuildConfig.REPORT_ENDPOINT.startsWith("https://")

    private val json = "application/json; charset=utf-8".toMediaType()
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS).build()
    }

    /** Blocking: call off the main thread. True only when the endpoint confirmed it stored the report. */
    fun send(report: JsonObject): Boolean {
        if (!available) return false
        val request = Request.Builder().url(BuildConfig.REPORT_ENDPOINT).post(report.toString().toRequestBody(json)).build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                // Apps Script answers through a redirect, which OkHttp follows; the final body is {"ok":true}.
                response.isSuccessful &&
                    JsonParser.parseString(response.peekBody(4096).string()).asJsonObject.get("ok")?.asBoolean == true
            }
        }.getOrDefault(false)
    }
}

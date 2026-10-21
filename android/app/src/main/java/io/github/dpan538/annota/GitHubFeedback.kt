package io.github.dpan538.annota

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Durable draft and request identity; only a validated GitHub Issue receipt acknowledges it. */
class GitHubFeedback(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    data class Draft(val id: String, val body: String)

    fun draft(): Draft =
        Draft(
            prefs.getString("feedbackId", null)
                ?: uid().also { prefs.edit().putString("feedbackId", it).commit() },
            prefs.getString("feedbackBody", "").orEmpty(),
        )

    fun save(body: String) {
        if (prefs.getString("feedbackBody", "") != body)
            prefs.edit().putString("feedbackBody", body).putString("feedbackId", uid()).commit()
    }

    fun prepare(): JSONObject {
        val draft = draft()
        require(draft.body.isNotBlank() && draft.body.codePointCount(0, draft.body.length) <= 1000)
        prefs.edit().putString("feedbackPending", draft.id).commit()
        return JSONObject()
            .put("body", draft.body.trim())
            .put("requestId", draft.id)
            .put("version", BuildConfig.VERSION_NAME)
    }

    fun pending(): String? = prefs.getString("feedbackPending", null)

    fun receipt(): String? = prefs.getString("feedbackReceipt", null)

    fun acknowledge(id: String, url: String) {
        require(validReceipt(url))
        val edit = prefs.edit().putString("feedbackReceipt", url)
        if (pending() == id) edit.remove("feedbackPending")
        if (prefs.getString("feedbackId", null) == id)
            edit.remove("feedbackBody").remove("feedbackId").remove("feedbackContact")
        edit.commit()
    }

    companion object {
        fun marker(id: String): String {
            require(id.matches(Regex("[a-zA-Z0-9-]{16,80}")))
            return "<!-- annota-feedback:$id -->"
        }

        fun validReceipt(url: String) =
            url.matches(Regex("https://github\\.com/dpan538/Annota/issues/[1-9][0-9]*"))

        fun findReceipt(issues: JSONArray, id: String): String? {
            val marker = marker(id)
            for (i in 0 until issues.length()) {
                val issue = issues.getJSONObject(i)
                val url = issue.optString("html_url")
                if (
                    !issue.has("pull_request") &&
                        validReceipt(url) &&
                        issue.optString("body").contains(marker)
                )
                    return url
            }
            return null
        }
    }
}

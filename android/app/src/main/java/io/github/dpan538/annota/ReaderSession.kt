package io.github.dpan538.annota

import org.json.JSONArray
import org.json.JSONObject

/** Small Activity state; page content and ink remain in the durable Store. */
data class ReaderSession(
    val viewport: ReaderView.Viewport?,
    val navigation: ReaderNavigator.State?,
    val history: List<ReaderView.Viewport>,
) {
    fun encode(): String =
        JSONObject()
            .apply {
                viewport?.let { put("viewport", it.json()) }
                put(
                    "history",
                    JSONArray().also { a -> history.takeLast(20).forEach { a.put(it.json()) } },
                )
                navigation?.let { n ->
                    put(
                        "navigation",
                        JSONObject()
                            .put("tab", n.tab)
                            .put("searching", n.searching)
                            .put("query", n.query)
                            .put("active", n.active)
                            .apply { n.origin?.let { put("origin", it.json()) } },
                    )
                }
            }
            .toString()

    companion object {
        private fun ReaderView.Viewport.json() =
            JSONObject()
                .put("page", pageId)
                .put("fraction", fraction.toDouble())
                .put("zoom", zoom.toDouble())
                .put("pan", panRatio.toDouble())
                .put("fit", fitWidth)
                .put("spread", spread)

        private fun viewport(j: JSONObject?) =
            j?.let {
                ReaderView.Viewport(
                    it.getString("page"),
                    it.getDouble("fraction").toFloat().coerceIn(0f, 1f),
                    it.getDouble("zoom").toFloat().coerceIn(.6f, 5f),
                    it.getDouble("pan").toFloat().coerceIn(-5f, 5f),
                    it.getBoolean("fit"),
                    it.getBoolean("spread"),
                )
            }

        fun decode(value: String?): ReaderSession? =
            runCatching {
                    if (value == null) return null
                    val j = JSONObject(value)
                    val n =
                        j.optJSONObject("navigation")?.let {
                            ReaderNavigator.State(
                                it.optInt("tab").coerceIn(0, 3),
                                it.optBoolean("searching"),
                                it.optString("query").take(80),
                                viewport(it.optJSONObject("origin")),
                                it.optInt("active").coerceIn(-1, 999),
                            )
                        }
                    val a = j.optJSONArray("history") ?: JSONArray()
                    ReaderSession(
                        viewport(j.optJSONObject("viewport")),
                        n,
                        (0 until minOf(a.length(), 20)).mapNotNull { viewport(a.optJSONObject(it)) },
                    )
                }
                .getOrNull()
    }
}

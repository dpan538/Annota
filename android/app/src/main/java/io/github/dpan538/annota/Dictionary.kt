package io.github.dpan538.annota

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject

class Dictionary(private val context: Context) {
    data class Entry(val word: String, val pronunciation: String, val meaning: String)

    data class Result(val entries: List<Entry>, val suggestions: List<String>)

    private val dir = File(context.filesDir, "dictionary").apply { mkdirs() }

    @Synchronized
    fun install() {
        val manifest =
            JSONObject(
                    context.assets.open("dictionary/manifest.json").bufferedReader().use {
                        it.readText()
                    }
                )
                .getJSONObject("files")
        listOf("en-zh.sqlite", "zh-en.sqlite").forEach { name ->
            val f = File(dir, name)
            val hash = manifest.getJSONObject(name).getString("sha256")
            if (!f.exists() || sha256(f) != hash) {
                atomicWrite(f) { out ->
                    context.assets.open("dictionary/$name").use { it.copyTo(out) }
                }
                check(sha256(f) == hash) { "词库校验失败" }
            }
        }
    }

    fun query(text: String): Result {
        require(text.length <= 80) { "暂不支持整句翻译" }
        val key =
            Normalizer.normalize(text, Normalizer.Form.NFKC)
                .trim()
                .lowercase(Locale.ROOT)
                .replace(Regex("\\s+"), " ")
        if (key.isBlank()) return Result(emptyList(), emptyList())
        val zh = key.any { it in '\u3400'..'\u9fff' }
        SQLiteDatabase.openDatabase(
                File(dir, if (zh) "zh-en.sqlite" else "en-zh.sqlite").path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            .use { db ->
                fun entries(sql: String, args: Array<String>): List<Entry> =
                    db.rawQuery(sql, args).use { c ->
                        buildList {
                            while (c.moveToNext()) {
                                fun s(n: String): String {
                                    return c.getString(c.getColumnIndexOrThrow(n)) ?: ""
                                }
                                add(
                                    if (zh) Entry(s("simplified"), s("pinyin"), s("definition"))
                                    else
                                        Entry(
                                            s("word"),
                                            s("phonetic"),
                                            s("translation").ifBlank { s("definition") },
                                        )
                                )
                            }
                        }
                    }
                fun suggestions(sql: String, args: Array<String>): List<String> =
                    db.rawQuery(sql, args).use { c ->
                        buildList { while (c.moveToNext()) add(c.getString(0)) }
                    }
                val compact = key.replace(Regex("[^a-z0-9]"), "")
                var rows =
                    if (zh)
                        entries(
                            "SELECT * FROM entries WHERE simplified=? OR traditional=? LIMIT 12",
                            arrayOf(key, key),
                        )
                    else
                        entries(
                            "SELECT * FROM entries WHERE key=? OR (compact=? AND compact!='') LIMIT 12",
                            arrayOf(key, compact),
                        )
                if (!zh && rows.isEmpty())
                    rows =
                        entries(
                            "SELECT DISTINCT e.* FROM entries e JOIN forms f ON e.id=f.entry_id WHERE f.key=? LIMIT 12",
                            arrayOf(key),
                        )
                if (rows.isNotEmpty()) return Result(rows, emptyList())
                val s = mutableListOf<String>()
                if (zh) {
                    s +=
                        suggestions(
                            "SELECT DISTINCT simplified FROM entries WHERE (simplified>=? AND simplified<?) OR (traditional>=? AND traditional<?) ORDER BY length(simplified),simplified LIMIT 6",
                            arrayOf(key, key + "\uffff", key, key + "\uffff"),
                        )
                    if (s.size < 6)
                        s +=
                            suggestions(
                                "SELECT DISTINCT simplified FROM entries WHERE instr(simplified,?)>0 OR instr(traditional,?)>0 ORDER BY length(simplified),simplified LIMIT 6",
                                arrayOf(key, key),
                            )
                } else if (compact.isNotEmpty()) {
                    s +=
                        suggestions(
                            "SELECT word FROM entries WHERE compact>=? AND compact<? ORDER BY length(compact),compact LIMIT 6",
                            arrayOf(compact, compact + "\uffff"),
                        )
                    if (s.size < 6 && compact.length in 3..40) {
                        val allowance = if (compact.length <= 5) 1 else 2
                        val head = compact.take(2)
                        val tail = compact.reversed().take(2)
                        val ranked =
                            db.rawQuery(
                                    "SELECT word,compact FROM entries WHERE ((compact>=? AND compact<?) OR (reverse_key>=? AND reverse_key<?)) AND length(compact) BETWEEN CAST(? AS INTEGER) AND CAST(? AS INTEGER) ORDER BY abs(length(compact)-?),compact LIMIT 5000",
                                    arrayOf(
                                        head,
                                        head + "\uffff",
                                        tail,
                                        tail + "\uffff",
                                        (compact.length - allowance).toString(),
                                        (compact.length + allowance).toString(),
                                        compact.length.toString(),
                                    ),
                                )
                                .use { c ->
                                    buildList {
                                        while (c.moveToNext()) {
                                            val d = distance(compact, c.getString(1))
                                            if (d <= allowance) add(d to c.getString(0))
                                        }
                                    }
                                }
                        s +=
                            ranked
                                .sortedWith(
                                    compareBy<Pair<Int, String>> { it.first }.thenBy { it.second }
                                )
                                .map { it.second }
                    }
                }
                return Result(emptyList(), s.distinct().take(6))
            }
    }

    companion object {
        fun distance(a: String, b: String): Int {
            val rows = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) rows[i][0] = i
            for (j in 0..b.length) rows[0][j] = j
            for (i in 1..a.length) for (j in 1..b.length) {
                rows[i][j] =
                    minOf(
                        rows[i - 1][j] + 1,
                        rows[i][j - 1] + 1,
                        rows[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                    )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1])
                    rows[i][j] = minOf(rows[i][j], rows[i - 2][j - 2] + 1)
            }
            return rows[a.length][b.length]
        }
    }
}

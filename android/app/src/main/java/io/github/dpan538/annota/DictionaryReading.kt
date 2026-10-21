package io.github.dpan538.annota

import android.content.Context
import org.json.JSONArray

data class DictionarySense(val label: String, val text: String)

data class DictionaryReading(
    val word: String,
    val pronunciation: String,
    val primary: List<DictionarySense>,
    val alternatives: List<DictionarySense>,
    val contextual: Boolean,
    val phrase: String = "",
)

/**
 * Conservative offline phrase/collocation and grammatical cues; uncertain senses stay available.
 */
class DictionaryReadings(context: Context) {
    private val rules =
        JSONArray(
            context.assets.open("dictionary/context-senses.json").bufferedReader().use {
                it.readText()
            }
        )

    private fun senses(entry: Dictionary.Entry): List<DictionarySense> {
        val prefix = Regex("^(?:[a-z]{1,6}\\.(?:/[a-z]{1,6}\\.)?|\\[[^]]{1,8}])(?:\\s+|$)")
        return entry.meaning
            .replace("\\n", "\n")
            .lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                if (line.startsWith("CL:") || line.startsWith("variant of ")) return@mapNotNull null
                val label = prefix.find(line)?.value?.trim().orEmpty()
                val meaning = line.removePrefix(label).trim()
                // Keep complete short equivalents; omit long usage notes and obsolete command
                // examples.
                val terms =
                    meaning
                        .split(Regex("[,，;；\\n]"))
                        .map { it.replace(Regex("\\([^)]*\\)|（[^）]*）"), "").trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                val short =
                    terms
                        .filter {
                            it.length <=
                                if (meaning.any { ch -> ch in '\u3400'..'\u9fff' }) 20 else 68
                        }
                        .ifEmpty { terms.take(1) }
                        .take(2)
                        .joinToString(if (meaning.any { it in '\u3400'..'\u9fff' }) "；" else "; ")
                if (short.isBlank()) null else DictionarySense(label, compact(short))
            }
            .distinctBy { it.text }
    }

    private fun compact(value: String): String {
        val limit = if (value.any { it in '\u3400'..'\u9fff' }) 20 else 68
        if (value.length <= limit) return value
        val end = value.take(limit).lastIndexOf(' ').takeIf { it > limit / 2 } ?: limit
        return value.take(end).trimEnd() + "…"
    }

    fun present(
        query: String,
        result: Dictionary.Result,
        context: LookupContext?,
        phrase: Pair<String, Dictionary.Entry>?,
    ): DictionaryReading? {
        val entry = result.entries.firstOrNull() ?: return null
        val all = result.entries.flatMap(::senses).distinctBy { it.text }
        val active = context?.takeIf { it.matches(query) }
        val key = LookupContext.key(entry.word)
        val rule =
            (0 until rules.length())
                .map { rules.getJSONObject(it) }
                .firstOrNull { it.getString("word") == key }
        fun fromJson(array: JSONArray) =
            (0 until array.length()).map { i ->
                array.getJSONObject(i).let {
                    DictionarySense(it.getString("pos"), compact(it.getString("text")))
                }
            }
        val defaults = rule?.getJSONArray("defaults")?.let(::fromJson).orEmpty()
        var primary =
            defaults.ifEmpty { all.filterNot { it.label.startsWith("[") }.ifEmpty { all }.take(2) }
        var contextual = false
        var selectedPhrase = ""
        if (active != null && rule != null) {
            val window = active.before.takeLast(6) + active.after.take(6)
            val variants = rule.getJSONArray("senses")
            val ranked =
                (0 until variants.length())
                    .map { index ->
                        val item = variants.getJSONObject(index)
                        fun list(name: String): List<String> =
                            item
                                .optJSONArray(name)
                                ?.let { a -> (0 until a.length()).map { a.getString(it) } }
                                .orEmpty()
                        val before = list("before").any { it == active.before.lastOrNull() }
                        val after = list("after").any { it == active.after.firstOrNull() }
                        val near = list("near").count { it in window }
                        val needed = item.optInt("minimum", 1)
                        val score =
                            (if (before) 8 else 0) +
                                (if (after) 8 else 0) +
                                (if (near >= needed) near * 3 else 0)
                        Triple(
                            score,
                            index,
                            DictionarySense(item.getString("pos"), compact(item.getString("text"))),
                        )
                    }
                    .sortedWith(
                        compareByDescending<Triple<Int, Int, DictionarySense>> { it.first }
                            .thenBy { it.second }
                    )
            val best = ranked.firstOrNull()
            if (best != null && best.first >= 3 && (ranked.getOrNull(1)?.first ?: 0) < best.first) {
                primary = listOf(best.third)
                contextual = true
            }
        }
        if (active != null && phrase != null) {
            primary = senses(phrase.second).take(1)
            contextual = primary.isNotEmpty()
            selectedPhrase = phrase.first
        }
        if (active != null && !contextual) {
            // Only strong verb cues are used; ambiguous noun/adjective cues do not force a choice.
            val verbCue =
                active.before.lastOrNull() in
                    setOf("to", "will", "would", "can", "could", "should", "must", "please")
            val verbs = all.filter { it.label in setOf("v.", "vt.", "vi.") }
            if (verbCue && verbs.isNotEmpty()) {
                primary = verbs.take(1)
                contextual = true
            }
        }
        primary = primary.ifEmpty { all.take(2) }.distinctBy { it.text }.take(2)
        fun equivalents(value: String) =
            value.split(Regex("[；;，,]")).map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val alternatives =
            (defaults + all)
                .distinctBy { it.text }
                .filter { sense ->
                    primary.none { equivalents(it.text).containsAll(equivalents(sense.text)) }
                }
                .take(12)
        return DictionaryReading(
            entry.word,
            entry.pronunciation,
            primary,
            alternatives,
            contextual,
            selectedPhrase,
        )
    }
}

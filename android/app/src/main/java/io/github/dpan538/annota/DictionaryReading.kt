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

    private val acronyms =
        JSONArray(
            context.assets.open("dictionary/technical-acronyms.json").bufferedReader().use {
                it.readText()
            }
        )

    /** Case is evidence: an uppercase initialism must not collapse into an English pronoun. */
    fun acronym(query: String, context: LookupContext?): DictionaryReading? {
        val raw =
            java.text.Normalizer.normalize(query, java.text.Normalizer.Form.NFKC)
                .trim()
                .trim { !it.isLetterOrDigit() }
                .removeSuffix("s")
        if (!raw.matches(Regex("[A-Z]{2,6}"))) return null
        val item =
            (0 until acronyms.length())
                .map { acronyms.getJSONObject(it) }
                .firstOrNull { it.getString("word") == raw } ?: return null
        val active = context?.takeIf { it.matches(query) }
        // ALL-CAPS prose still contains pronouns; explicit grammatical evidence beats casing.
        if (
            raw == "IT" &&
                active?.after?.take(2)?.joinToString(" ") in
                    setOf(
                        "is raining",
                        "was raining",
                        "is snowing",
                        "was snowing",
                        "is a",
                        "was a",
                        "will be",
                    )
        )
            return null
        val window = active?.let { it.before.takeLast(8) + it.after.take(8) }.orEmpty()
        val variants = item.getJSONArray("senses")
        val ranked =
            (0 until variants.length())
                .map { i ->
                    val v = variants.getJSONObject(i)
                    val cues = v.getJSONArray("near")
                    Triple((0 until cues.length()).count { cues.getString(it) in window }, i, v)
                }
                .sortedWith(
                    compareByDescending<Triple<Int, Int, org.json.JSONObject>> { it.first }
                        .thenBy { it.second }
                )
        val confident =
            ranked.first().first > 0 && ranked.first().first > (ranked.getOrNull(1)?.first ?: 0)
        val chosen = if (confident) ranked.take(1) else ranked.take(2)
        fun sense(v: org.json.JSONObject) = DictionarySense("abbr.", v.getString("text"))
        return DictionaryReading(
            raw,
            "",
            chosen.map { sense(it.third) },
            ranked.filterNot { it in chosen }.map { sense(it.third) },
            confident,
            chosen.joinToString(" / ") { it.third.getString("expansion") },
        )
    }

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
        acronym(query, context)?.let {
            return it
        }
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
        val primaryLimit =
            if (LookupContext.tokens(entry.word).size > 1 || '-' in entry.word) 1 else 2
        var primary =
            defaults.ifEmpty {
                all.filterNot { it.label.startsWith("[") }.ifEmpty { all }.take(primaryLimit)
            }
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
        // Curated context rules retain authority over broad upstream collocations; otherwise
        // a phrase such as "river bank" would override deliberately ambiguous local evidence.
        if (
            active != null && phrase != null && (rule == null || phrase.second.source == "annota")
        ) {
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

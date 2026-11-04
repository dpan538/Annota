package io.github.dpan538.annota

import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Conservative, deterministic weighted Lesk. Scores are evidence weights, not probabilities.
 *
 * NLTK's definition/context overlap and pyWSD's lemma/stop-word signatures provide the baseline:
 * https://www.nltk.org/_modules/nltk/wsd.html
 * https://github.com/alvations/pywsd/blob/master/pywsd/lesk.py Distance, candidate-local IDF,
 * length damping and abstention below are Annota heuristics. No source implementation is copied.
 * Supply only attested lemmas; this class performs no IO.
 */
object SenseRanker {
    data class Candidate(val id: Int, val gloss: String, val pos: String = "", val rank: Int = 0)

    data class Evidence(
        val candidateIds: Set<Int>,
        val score: Double,
        val matchedCues: Set<String>,
        val distinctiveCues: Set<String>,
    )

    data class Decision(
        val ordered: List<Candidate>,
        val confidentIds: Set<Int> = emptySet(),
        val evidence: List<Evidence> = emptyList(),
    )

    private const val MAX_CANDIDATES = 128
    private const val WINDOW = 12
    private const val MAX_SIGNATURE_WORDS = 128
    private val word = Regex("[a-z]+(?:'[a-z]+)?")
    private val stops =
        ("a an the and or but if then than so because as at by for in of on to up with " +
                "about above after again against all also am any are around be been before being " +
                "below between both can could did do does doing down during each either else " +
                "few from further had has have having he her here hers herself him himself his " +
                "how i into is it its itself just may me might more most much must my myself " +
                "neither no nor not now off once one only other our ours ourselves out over " +
                "own same she should some such that their theirs them themselves there these " +
                "they this those through too under until us very was we were what when where " +
                "which while who whom why will would you your yours yourself yourselves used")
            .split(' ')
            .toSet()

    private fun normalized(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace('’', '\'')

    private fun tokens(value: String): List<String> =
        word.findAll(normalized(value)).map { it.value }.toList()

    private fun nearbyBefore(before: List<String>): List<String> =
        before.takeLast(WINDOW).flatMap { tokens(it.take(160)) }.takeLast(WINDOW)

    private fun nearbyAfter(after: List<String>): List<String> =
        after.take(WINDOW).flatMap { tokens(it.take(160)) }.take(WINDOW)

    /**
     * Lexical overlap cannot resolve negation scope or an excluded alternative. Abstain when a
     * nearby marker is present, including contractions, instead of treating its content words as
     * positive evidence. This is deliberately conservative, not a syntactic parser: even a marker
     * applying to another clause can reduce recall. Use the same gate for non-Lesk rule fallbacks.
     */
    fun hasUnresolvedContrast(before: List<String>, after: List<String>): Boolean {
        val negations = setOf("not", "no", "never", "neither", "nor", "without", "cannot")
        return listOf(nearbyBefore(before), nearbyAfter(after)).any { side ->
            side.any { it in negations || it.endsWith("n't") } ||
                side.zipWithNext().any { (first, second) ->
                    (first == "rather" && second == "than") ||
                        (first == "instead" && second == "of")
                }
        }
    }

    private fun pos(value: String): String =
        when (val key = value.lowercase(Locale.ROOT).trim().trimEnd('.')) {
            "n",
            "noun" -> "n"
            "v",
            "vt",
            "vi",
            "verb" -> "v"
            "a",
            "s",
            "adj",
            "adjective" -> "a"
            "r",
            "adv",
            "adverb" -> "r"
            else -> key
        }

    private val determiners =
        "a an the this that these those each every either neither some any my your his her its our their"
            .split(' ')
            .toSet()
    private val nounPhraseBreaks =
        ("am is are was were be been being do does did have has had can could may might " +
                "must shall should will would to of in on at by for from with without into onto " +
                "over under through between among around after before during until since " +
                "about against beside beyond per via than as and or but if then because " +
                "when where while who whom which i you he she it we they")
            .split(' ')
            .toSet()
    private val phraseToken = Regex("[a-z]+(?:'[a-z]+)?|[^a-z\\s-]")

    /**
     * A short determiner-led phrase is incompatible with confidently selecting a verb sense. This
     * only vetoes a winner; it neither selects a noun meaning nor attempts POS parsing. Keep
     * punctuation when callers provide it, and stop at auxiliaries/prepositions instead of
     * extending a determiner across a clause. A determiner plus at most two modifiers is bounded.
     */
    internal fun hasShortDeterminerPhrase(before: List<String>): Boolean {
        val nearby =
            before.takeLast(4).flatMap { value ->
                phraseToken.findAll(normalized(value.take(160))).map { it.value }.toList()
            }
        for (token in nearby.takeLast(3).asReversed()) {
            if (token in determiners) return true
            if (!word.matches(token) || token in nounPhraseBreaks || '\'' in token) return false
        }
        return false
    }

    private data class Cue(val forms: Set<String>, val text: String, val distance: Int)

    fun rank(
        query: String,
        before: List<String>,
        after: List<String>,
        candidates: List<Candidate>,
        lemmas: (String) -> Set<String> = { emptySet() },
    ): Decision {
        val fallback = candidates.sortedBy { it.rank }
        if (
            candidates.isEmpty() ||
                candidates.size > MAX_CANDIDATES ||
                hasUnresolvedContrast(before, after)
        )
            return Decision(fallback)
        // A truncated candidate inventory could give a false winner. Refuse to rank above the
        // bound, and always preserve every input record in the returned ordering.
        val groups =
            candidates
                .groupBy {
                    pos(it.pos) + "|" + normalized(it.gloss).trim().replace(Regex("\\s+"), " ")
                }
                .values
                .toList()
        if (groups.size < 2) return Decision(fallback)

        val formCache = mutableMapOf<String, Set<String>>()
        fun forms(token: String): Set<String> =
            formCache.getOrPut(token) {
                (listOf(token) + lemmas(token).asSequence().take(8).map(::normalized).toList())
                    .filter { it.length >= 3 && it !in stops && word.matches(it) }
                    .toSet()
            }
        val excluded = tokens(query.take(160)).flatMap { forms(it) }.toSet() + stops
        val raw =
            nearbyBefore(before).asReversed().mapIndexed { index, text -> text to index + 1 } +
                nearbyAfter(after).mapIndexed { index, text -> text to index + 1 }
        val cues = mutableListOf<Cue>()
        for ((text, distance) in raw) {
            for (token in tokens(text.take(160))) {
                if (token in stops || token.length < 3) continue
                val family = forms(token) - excluded
                if (family.isEmpty()) continue
                var cue = Cue(family, token, distance)
                // Overlapping, not just identical, morphology sets are one evidence family.
                // Repeated words/inflections must not turn one cue into two independent votes.
                var index = 0
                while (index < cues.size) {
                    val previous = cues[index]
                    if (cue.forms.any { it in previous.forms }) {
                        cues.removeAt(index)
                        val nearest = if (previous.distance < cue.distance) previous else cue
                        cue = Cue(cue.forms + previous.forms, nearest.text, nearest.distance)
                        index = 0
                    } else index++
                }
                cues += cue
            }
        }
        if (cues.size < 2) return Decision(fallback)

        val signatures =
            groups.map { group ->
                tokens(group.first().gloss.take(4096))
                    .asSequence()
                    .filter { it.length >= 3 && it !in stops }
                    .take(MAX_SIGNATURE_WORDS)
                    .distinct()
                    .map { forms(it) - excluded }
                    .filter { it.isNotEmpty() }
                    .toList()
            }
        val matches =
            signatures.map { signature ->
                val flattened = signature.flatten().toSet()
                cues.indices.filter { index -> cues[index].forms.any { it in flattened } }.toSet()
            }
        val frequency = IntArray(cues.size) { cue -> matches.count { cue in it } }
        val evidence =
            groups.indices.map { index ->
                val discriminating = matches[index].filter { frequency[it] < groups.size }
                val weight =
                    discriminating.sumOf { cue ->
                        val information = ln((groups.size + 1.0) / (frequency[cue] + 1.0))
                        information / (1.0 + .12 * (cues[cue].distance - 1))
                    }
                // Mild damping stops long, broad definitions accumulating accidental matches.
                // It cannot by itself establish confidence: winner-only cue evidence is required.
                val damping = sqrt(1.0 + signatures[index].size / 48.0)
                Evidence(
                    groups[index].map { it.id }.toSet(),
                    weight / damping,
                    matches[index].map { cues[it].text }.toSet(),
                    discriminating.map { cues[it].text }.toSet(),
                )
            }
        val ranked =
            groups.indices.sortedWith(
                compareByDescending<Int> { evidence[it].score }
                    .thenBy { groups[it].minOf { candidate -> candidate.rank } }
            )
        val winner = ranked[0]
        val runner = ranked[1]
        val best = evidence[winner]
        val second = evidence[runner]
        val confident =
            !(pos(groups[winner].first().pos) == "v" && hasShortDeterminerPhrase(before)) &&
                best.distinctiveCues.size >= 2 &&
                groups.indices
                    .filter { it != winner }
                    .all { (matches[winner] - matches[it]).size >= 2 } &&
                best.score - second.score >= .12 &&
                best.score - second.score >= best.score * .2
        if (!confident) return Decision(fallback, evidence = ranked.map { evidence[it] })
        val scores = evidence.flatMap { item -> item.candidateIds.map { it to item.score } }.toMap()
        return Decision(
            candidates.sortedWith(
                compareByDescending<Candidate> { scores[it.id] ?: 0.0 }.thenBy { it.rank }
            ),
            best.candidateIds,
            ranked.map { evidence[it] },
        )
    }
}

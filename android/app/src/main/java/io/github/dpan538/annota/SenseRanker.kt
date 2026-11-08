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
    data class Candidate(
        val id: Int,
        val gloss: String,
        val pos: String = "",
        val rank: Int = 0,
        /** OEWN lexicographer file such as `noun.cognition`; a coarse domain is derived from it. */
        val domain: String = "",
    )

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

    /**
     * Domain prior thresholds. A prior needs two different context words from one coarse domain, a
     * strict lead over every other domain, and a candidate already close to the lexical best. These
     * are evidence weights, not probabilities; unrelated lexicographer files are ignored.
     */
    private const val DOMAIN_MIN_CUES = 2
    private const val DOMAIN_LEXICAL_TOLERANCE = .08
    private const val DOMAIN_WINNER_MARGIN = .06
    private const val DOMAIN_BASE_BONUS = .16
    private const val DOMAIN_EXTRA_BONUS = .08
    private const val DOMAIN_MAX_BONUS = .32

    /**
     * OEWN lexicographer files are broad and sometimes ambiguous. These nine coarse domains are a
     * deliberately small prior: they can break a near tie, never replace confident gloss overlap or
     * a curated rule. Broad files map to more than one domain (artifacts can be daily objects or
     * computing devices; communication can be arts or legal accusations; cognition can be science
     * or a legal dispute). Unknown files and empty domains stay neutral.
     */
    private val coarseDomains =
        setOf(
            "computing",
            "medicine",
            "legal",
            "finance",
            "science",
            "arts",
            "social",
            "nature",
            "daily",
        )

    private fun cues(value: String): Set<String> = value.split(' ').toSet()

    private val lexfileDomains =
        mapOf(
            "noun.artifact" to setOf("daily", "computing"),
            "adj.all" to setOf("daily"),
            "noun.plant" to setOf("nature"),
            "noun.animal" to setOf("nature"),
            "noun.state" to setOf("science"),
            "adj.pert" to setOf("science"),
            "noun.substance" to setOf("science"),
            "noun.person" to setOf("social"),
            "noun.act" to setOf("social"),
            "noun.communication" to setOf("arts", "legal"),
            "verb.social" to setOf("social", "computing"),
            "noun.quantity" to setOf("science"),
            "adv.all" to setOf("daily"),
            "verb.motion" to setOf("daily"),
            "verb.change" to setOf("science"),
            "noun.cognition" to setOf("science", "legal"),
            "noun.possession" to setOf("finance", "legal"),
            "verb.communication" to setOf("arts", "legal"),
            "noun.time" to setOf("daily"),
            "verb.stative" to setOf("daily"),
            "verb.possession" to setOf("finance"),
            "noun.process" to setOf("science"),
            "noun.group" to setOf("social"),
            "verb.contact" to setOf("daily", "computing"),
            "noun.attribute" to setOf("daily"),
            "noun.food" to setOf("daily"),
            "noun.body" to setOf("medicine"),
            "verb.perception" to setOf("science"),
            "noun.relation" to setOf("science"),
            "verb.cognition" to setOf("science"),
            "noun.shape" to setOf("science"),
            "noun.location" to setOf("nature"),
            "verb.body" to setOf("medicine"),
            "noun.event" to setOf("nature"),
            "noun.object" to setOf("nature"),
            "noun.phenomenon" to setOf("science"),
            "noun.feeling" to setOf("social"),
            "verb.creation" to setOf("arts"),
            "verb.emotion" to setOf("social"),
            "verb.competition" to setOf("social"),
            "verb.consumption" to setOf("daily"),
            "verb.weather" to setOf("nature"),
            "adj.ppl" to setOf("daily"),
            "noun.Tops" to setOf("nature", "medicine"),
            "noun.motive" to setOf("social"),
        )

    private val domainClues =
        mapOf(
            "computing" to
                cues(
                    "computer computers software hardware program programs programming code coding " +
                        "algorithm algorithms database databases server servers network networks data memory " +
                        "processor processors cpu cloud digital virtual internet application applications app " +
                        "apps file files byte bytes compile compiler debug runtime query queries script scripts " +
                        "module modules keyboard laptop desktop storage login username password browser website " +
                        "web"
                ),
            "medicine" to
                cues(
                    "patient patients clinical clinic hospital hospitals doctor doctors nurse nurses " +
                        "disease diseases treatment treatments diagnosis symptom symptoms blood cell cells " +
                        "tissue tissues organ organs brain skull neck anatomy medical medicine surgery " +
                        "infection infections virus viruses bacteria bacterial nucleus membrane protein " +
                        "proteins gene genes dna drug drugs dose doses fever pain health healthy immune " +
                        "microscope specimen syringe wound"
                ),
            "legal" to
                cues(
                    "court courts judge judges lawyer lawyers attorney attorneys legal law laws contract " +
                        "contracts plaintiff defendant trial trials statute statutes crime crimes criminal " +
                        "arrest police prison prisoner jail guilty verdict lawsuit sue evidence witness jury " +
                        "appeal appeals property patent patents copyright constitution constitutional " +
                        "regulation regulations illegal lawful tribunal custody sentence prosecutor offence " +
                        "offense"
                ),
            "finance" to
                cues(
                    "bank banks banking money cash deposit deposits loan loans mortgage mortgages interest " +
                        "percentage percent debt debts finance financial stock stocks investment investor " +
                        "investors income revenue capital market markets profit profits payment payments pay " +
                        "paid price prices cost costs account accounts salary wage wages tax taxes budget " +
                        "currency dollar dollars euro fund funds credit bill bills fee fees purchase sale sales " +
                        "borrow borrower lender trade trading economy economic cheque check birthday gift gifts wrapped"
                ),
            "science" to
                cues(
                    "science sciences scientific scientist scientists research experiment experiments " +
                        "laboratory laboratories lab labs physics chemistry chemical chemicals biology " +
                        "biological molecule molecules molecular atom atoms electron electrons proton protons " +
                        "neutron neutrons electric electrical electricity wire wires ampere amperes conductor " +
                        "circuit metal metals toxic graphite pencil matter liquid liquids solid solids gas " +
                        "gases voltage energy force forces magnetic magnet magnetism electromagnetic radiation " +
                        "equation equations math mathematics algebra geometry statistics statistical " +
                        "statistically hypothesis hypotheses theory theories quantum particle particles " +
                        "reaction reactions acid acids solution solutions concentration temperature " +
                        "temperatures pressure species organism organisms neural training trained parameters " +
                        "parameter learning gravity mass velocity acceleration wavelength frequency oxygen " +
                        "hydrogen carbon"
                ),
            "arts" to
                cues(
                    "art arts artist artists music musical song songs piano guitar painting paintings " +
                        "sculpture literature literary poem poems poetry novel novels story stories essay " +
                        "essays article articles book books magazine magazines newspaper newspapers publish " +
                        "published publisher publication publications writing writer writers author authors " +
                        "manuscript manuscripts grammar language languages culture cultural theatre theater " +
                        "film films movie movies drama dramas lyrics prose fiction narrative review reviews " +
                        "journal journals revise revision chapter poet composer concert gallery"
                ),
            "social" to
                cues(
                    "society social community communities people person persons group groups organization " +
                        "organizations government governments political politics party parties leader leaders " +
                        "leadership manager managers management team teams member members family families " +
                        "friend friends child children kids army armies military soldier soldiers war wars " +
                        "election elections vote votes voters public official officials company companies " +
                        "worker workers employee employees crowd crowds audience audiences class classes nation " +
                        "nations citizen citizens tribe tribes council committee recruit recruits volunteer " +
                        "volunteers student students"
                ),
            "nature" to
                cues(
                    "nature natural animal animals plant plants tree trees leaf leaves root roots soil " +
                        "earth land field fields farm farms farmer farmers river rivers stream streams water " +
                        "lake lakes ocean oceans sea seas mountain mountains forest forests wood woods rain " +
                        "rains wind winds weather storm storms snow ice sun moon sky cloud clouds fish fishes " +
                        "bird birds insect insects flower flowers grass grasses seed seeds crop crops barn " +
                        "shore shores coast coasts island islands valley valleys wild wildlife garden gardens " +
                        "stone stones sand mud swamp jungle desert hill hills canoe downstream salmon trout " +
                        "feather feathers fur nest nests"
                ),
            "daily" to
                cues(
                    "house houses home homes room rooms door doors key keys table tables chair chairs bed " +
                        "beds kitchen food eat eating drink drinking clothes shirt shirts shoe shoes car cars " +
                        "bus buses train trains ticket tickets work working job jobs school schools teacher " +
                        "teachers phone phones mobile battery batteries device devices machine machines tool " +
                        "tools furniture window windows street streets city cities town towns village villages " +
                        "shop shops store stores morning mornings evening evenings night nights day days time " +
                        "times hour hours minute minutes week weeks year years today yesterday tomorrow " +
                        "present presents holiday holidays dinner lunch breakfast coffee " +
                        "tea bottle bottles bag bags box boxes dress dresses factory factories industrial " +
                        "manufacturing"
                ),
        )

    private val clueDomains: Map<String, Set<String>> =
        domainClues
            .flatMap { (domain, words) -> words.map { it to domain } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }

    private data class DomainSignal(val domain: String, val cues: Set<String>)

    private fun candidateDomains(raw: String): Set<String> {
        val key = raw.trim()
        if (key.isEmpty()) return emptySet()
        val normalizedKey = normalized(key).trim()
        if (normalizedKey in coarseDomains) return setOf(normalizedKey)
        // Keep the original spelling first: OEWN uses `noun.Tops` with a capital T.
        return lexfileDomains[key] ?: lexfileDomains[normalizedKey].orEmpty()
    }

    /**
     * Count different context words per coarse domain. Repeated words and the selected word itself
     * do not count, and a domain must strictly lead every other domain before it can add anything.
     */
    private fun domainSignal(
        before: List<String>,
        after: List<String>,
        excluded: Set<String>,
    ): DomainSignal? {
        if (before.isEmpty() && after.isEmpty()) return null
        val context = (nearbyBefore(before) + nearbyAfter(after)).distinct()
        if (context.isEmpty()) return null
        val matches = LinkedHashMap<String, MutableSet<String>>()
        for (token in context) {
            if (token in stops || token in excluded) continue
            val domains = clueDomains[token] ?: continue
            for (domain in domains) matches.getOrPut(domain) { linkedSetOf() }.add(token)
        }
        if (matches.isEmpty()) return null
        val ranked =
            matches.entries.sortedWith(
                compareByDescending<Map.Entry<String, MutableSet<String>>> { it.value.size }
                    .thenBy { it.key }
            )
        val best = ranked.first()
        if (best.value.size < DOMAIN_MIN_CUES) return null
        val runnerUp = ranked.getOrNull(1)
        if (runnerUp != null && best.value.size <= runnerUp.value.size) return null
        return DomainSignal(best.key, best.value)
    }

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
        useDomainPrior: Boolean = true,
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
        val lexicalEvidence = ranked.map { evidence[it] }
        if (confident) {
            val scores =
                evidence.flatMap { item -> item.candidateIds.map { it to item.score } }.toMap()
            return Decision(
                candidates.sortedWith(
                    compareByDescending<Candidate> { scores[it.id] ?: 0.0 }.thenBy { it.rank }
                ),
                best.candidateIds,
                lexicalEvidence,
            )
        }
        if (!useDomainPrior) return Decision(fallback, evidence = lexicalEvidence)

        // A domain prior only breaks a near tie or fills a lexical gap. It never runs when the
        // gloss/rule evidence above is already confident, and a candidate that is too far behind
        // the lexical best is not rescued: explicit word-level evidence wins.
        val signal =
            domainSignal(before, after, excluded)
                ?: return Decision(fallback, evidence = lexicalEvidence)
        val groupDomains =
            groups.map { group -> group.flatMap { candidateDomains(it.domain) }.toSet() }
        val matching = groups.indices.filter { signal.domain in groupDomains[it] }
        if (matching.isEmpty()) return Decision(fallback, evidence = lexicalEvidence)
        val lexicalBest = evidence.maxOf { it.score }
        val domainBest = matching.maxOf { evidence[it].score }
        if (domainBest + DOMAIN_LEXICAL_TOLERANCE < lexicalBest)
            return Decision(fallback, evidence = lexicalEvidence)

        val bonus =
            minOf(
                DOMAIN_MAX_BONUS,
                DOMAIN_BASE_BONUS + DOMAIN_EXTRA_BONUS * (signal.cues.size - DOMAIN_MIN_CUES),
            )
        val combined =
            evidence.mapIndexed { index, item ->
                item.score + if (index in matching) bonus else 0.0
            }
        val domainRanked =
            groups.indices.sortedWith(
                compareByDescending<Int> { combined[it] }
                    .thenBy { groups[it].minOf { candidate -> candidate.rank } }
            )
        val domainWinner = domainRanked[0]
        if (domainWinner !in matching) return Decision(fallback, evidence = lexicalEvidence)
        val domainRunner = domainRanked[1]
        if (combined[domainWinner] - combined[domainRunner] < DOMAIN_WINNER_MARGIN)
            return Decision(fallback, evidence = lexicalEvidence)
        // The existing determiner veto is a grammatical constraint, not a lexical tie. Do not let
        // a domain prior promote a verb the caller has already made implausible.
        if (pos(groups[domainWinner].first().pos) == "v" && hasShortDeterminerPhrase(before))
            return Decision(fallback, evidence = lexicalEvidence)
        // If several senses share the winning domain and none has any gloss evidence, the prior
        // cannot tell them apart. Keep the source order instead of inventing a definite sense.
        if (evidence[domainWinner].score == 0.0 && matching.count { evidence[it].score == 0.0 } > 1)
            return Decision(fallback, evidence = lexicalEvidence)

        val combinedById =
            groups.indices
                .flatMap { index -> groups[index].map { it.id to combined[index] } }
                .toMap()
        return Decision(
            candidates.sortedWith(
                compareByDescending<Candidate> { combinedById[it.id] ?: 0.0 }.thenBy { it.rank }
            ),
            groups[domainWinner].map { it.id }.toSet(),
            lexicalEvidence,
        )
    }
}

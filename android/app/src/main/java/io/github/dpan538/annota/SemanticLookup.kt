package io.github.dpan538.annota

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt
import org.json.JSONObject

/** Bundled sense vectors and durable, content-addressed context vectors. No network access. */
class SemanticLookup internal constructor(private val context: Context) {
    private val prefs = Prefs(context)
    private val sequence = AtomicLong()
    private val foreground = AtomicInteger()
    val foregroundPending: Boolean
        get() = foreground.get() > 0

    private class Job<T>(val priority: Int, val order: Long, action: Callable<T>) :
        FutureTask<T>(action), Comparable<Job<*>> {
        override fun compareTo(other: Job<*>): Int =
            compareValuesBy(this, other, { it.priority }, { it.order })
    }

    private val worker =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            PriorityBlockingQueue(),
            { task ->
                Thread(task, "annota-semantic-lookup").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 1
                }
            },
        )
    private val idle =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "annota-semantic-idle").apply { isDaemon = true }
        }
    private var release: ScheduledFuture<*>? = null
    private val activityGeneration = AtomicLong()
    private var encoder: MiniLmEncoder? = null // accessed only by worker
    private var cache: SQLiteDatabase? = null
    private var index: SQLiteDatabase? = null
    private val manifest by lazy {
        JSONObject(
            context.assets.open("semantic/manifest.json").bufferedReader().use { it.readText() }
        )
    }
    private val revision by lazy {
        manifest.getJSONObject("files").getJSONObject("model.onnx").getString("sha256") +
            ":" +
            manifest.getJSONObject("files").getJSONObject("vocab.txt").getString("sha256") +
            ":context-v1"
    }
    private val encoded = AtomicLong()
    private val hits = AtomicLong()
    private val attempts = AtomicLong()
    private val accepted = AtomicLong()
    private val failures = AtomicLong()

    fun statistics(): Map<String, Long> =
        mapOf(
            "encoded" to encoded.get(),
            "cacheHits" to hits.get(),
            "rerankAttempts" to attempts.get(),
            "accepted" to accepted.get(),
            "fallbacks" to failures.get(),
        )

    private fun cacheDb(): SQLiteDatabase =
        cache
            ?: run {
                val dir = File(context.filesDir, "semantic").apply { mkdirs() }
                SQLiteDatabase.openOrCreateDatabase(File(dir, "contexts.sqlite"), null)
                    .apply {
                        execSQL(
                            "CREATE TABLE IF NOT EXISTS vectors (key TEXT PRIMARY KEY, vector BLOB NOT NULL)"
                        )
                    }
                    .also { cache = it }
            }

    private fun indexDb(): SQLiteDatabase =
        index
            ?: run {
                val dir = File(context.filesDir, "semantic").apply { mkdirs() }
                val file = File(dir, "glosses.sqlite")
                val hash =
                    manifest
                        .getJSONObject("files")
                        .getJSONObject("glosses.sqlite")
                        .getString("sha256")
                if (!file.exists() || sha256(file) != hash) {
                    atomicWrite(file) { output ->
                        context.assets.open("semantic/glosses.sqlite").use { it.copyTo(output) }
                    }
                    check(sha256(file) == hash) { "Bundled sense vectors checksum failed" }
                }
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).also {
                    index = it
                }
            }

    private fun bytes(vector: FloatArray): ByteArray =
        ByteBuffer.allocate(vector.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { vector.forEach { putFloat(it) } }
            .array()

    private fun floats(bytes: ByteArray): FloatArray? {
        if (bytes.size != 384 * 4) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(384) { buffer.float }
            .takeIf { v -> v.all { it.isFinite() } && v.sumOf { it.toDouble() * it } > .5 }
    }

    private fun vector(text: String, active: () -> Boolean): FloatArray {
        check(active()) { "Cancelled" }
        val key = digest(revision + "\u0000" + text)
        val db = cacheDb()
        db.rawQuery("SELECT vector FROM vectors WHERE key=?", arrayOf(key)).use { cursor ->
            if (cursor.moveToFirst())
                floats(cursor.getBlob(0))?.let {
                    hits.incrementAndGet()
                    return it
                }
        }
        val model = encoder ?: MiniLmEncoder(context).also { encoder = it }
        check(active()) { "Cancelled" }
        val result = model.encode(text)
        encoded.incrementAndGet()
        // A completed encoding is reusable even if the user has since selected another word.
        db.insertWithOnConflict(
            "vectors",
            null,
            ContentValues().apply {
                put("key", key)
                put("vector", bytes(result))
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        check(active()) { "Cancelled" }
        return result
    }

    private fun glossVector(gloss: String, active: () -> Boolean): FloatArray? {
        indexDb()
            .rawQuery("SELECT vector,scale FROM vectors WHERE key=?", arrayOf(digest(gloss)))
            .use { c ->
                if (c.moveToFirst()) {
                    val packed = c.getBlob(0)
                    val scale = c.getFloat(1)
                    if (packed.size != 384 || !scale.isFinite() || scale <= 0f) return null
                    val v = FloatArray(384) { packed[it].toInt() * scale }
                    val norm = sqrt(v.sumOf { it.toDouble() * it }).toFloat()
                    return v.takeIf { norm > 0f }?.also { for (i in it.indices) it[i] /= norm }
                }
            }
        // English fallback definitions are bounded and cached once, just like context.
        return vector(gloss, active)
    }

    private fun <T> execute(priority: Int, active: () -> Boolean, task: (() -> Boolean) -> T): T? {
        val deadline = SystemClock.uptimeMillis() + if (priority == 0) 8000 else 15000
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        val effectiveActive = {
            !cancelled.get() &&
                prefs.semanticDictionary &&
                active() &&
                SystemClock.uptimeMillis() < deadline
        }
        val job =
            Job(
                priority,
                sequence.incrementAndGet(),
                Callable {
                    check(effectiveActive()) { "Cancelled" }
                    val generation = activityGeneration.incrementAndGet()
                    release?.cancel(false)
                    try {
                        task(effectiveActive)
                    } finally {
                        release =
                            idle.schedule(
                                {
                                    worker.execute(
                                        Job(
                                            20,
                                            sequence.incrementAndGet(),
                                            Callable {
                                                if (activityGeneration.get() == generation) {
                                                    encoder?.close()
                                                    encoder = null
                                                }
                                            },
                                        )
                                    )
                                },
                                180,
                                TimeUnit.SECONDS,
                            )
                    }
                },
            )
        worker.execute(job)
        try {
            while (effectiveActive()) {
                try {
                    return job.get(50, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    /* Check cancellation between waits. */
                }
            }
        } catch (_: ExecutionException) {
            failures.incrementAndGet()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            cancelled.set(true)
            if (!job.isDone) {
                job.cancel(false)
                worker.remove(job)
            }
        }
        return null
    }

    fun prepare(active: () -> Boolean): Boolean {
        if (!prefs.semanticDictionary || !active() || foregroundPending) return false
        return execute(10, { active() && !foregroundPending }) { bounded ->
            check(bounded())
            (encoder ?: MiniLmEncoder(context).also { encoder = it }).prepare()
            check(bounded())
            indexDb()
            true
        } ?: false
    }

    fun warm(lookup: LookupContext, active: () -> Boolean): Boolean {
        if (!prefs.semanticDictionary || !active() || foregroundPending) return false
        val text = contextText(lookup)
        if (text.isBlank()) return false
        return execute(10, { active() && !foregroundPending }) { bounded ->
            vector(text, bounded)
            true
        } ?: false
    }

    fun rerank(
        query: String,
        lookup: LookupContext?,
        senses: List<WordNetDictionary.Sense>,
        active: () -> Boolean,
    ): List<WordNetDictionary.Sense> {
        if (
            !prefs.semanticDictionary ||
                lookup == null ||
                !lookup.matches(query) ||
                senses.size < 2 ||
                senses.size > 128 ||
                SenseRanker.hasUnresolvedContrast(lookup.before, lookup.after) ||
                (lookup.before + lookup.after).distinct().count {
                    it.length >= 3 && it !in GENERIC_CONTEXT
                } < 2
        )
            return senses
        attempts.incrementAndGet()
        foreground.incrementAndGet()
        try {
            return execute(0, active) { bounded ->
                val contextVector = vector(contextText(lookup), bounded)
                val groups = senses.indices.groupBy { senses[it].label + "|" + senses[it].gloss }
                if (groups.size < 2) return@execute senses
                val contextWords =
                    (lookup.before + LookupContext.tokens(query) + lookup.after).joinToString(" ")
                val scores =
                    groups.values
                        .filterNot { group ->
                            // A definition of an imitation also mentions the genuine object. That
                            // shared object name alone cannot establish the restrictive qualifier.
                            val gloss = senses[group.first()].gloss.lowercase(Locale.ROOT)
                            Regex("\\b(simulation|imitation|replica|counterfeit) of\\b")
                                .containsMatchIn(gloss) &&
                                !Regex(
                                        "\\b(simulat\\w*|imitat\\w*|replica\\w*|counterfeit\\w*|synthetic|artificial|electronic|digital)\\b"
                                    )
                                    .containsMatchIn(contextWords)
                        }
                        .map { group ->
                            check(bounded()) { "Cancelled" }
                            val candidate =
                                glossVector(senses[group.first()].gloss, bounded)
                                    ?: return@execute senses
                            group to
                                contextVector.indices.sumOf {
                                    contextVector[it].toDouble() * candidate[it]
                                }
                        }
                        .sortedByDescending { it.second }
                if (scores.size < 2) return@execute senses
                val winner = scores[0]
                // Definitions with shared complete translated equivalents are not competing
                // meanings (e.g. two sources describing the same pupil/eye sense).
                fun equivalents(id: Int) =
                    senses[id]
                        .text
                        .split(Regex("[；;，,/]"))
                        .map { it.trim() }
                        .filter { it.length >= 2 }
                        .toSet()
                val winnerTerms = winner.first.flatMap { equivalents(it) }.toSet()
                val runner =
                    scores.drop(1).firstOrNull { candidate ->
                        candidate.first.all { (equivalents(it) intersect winnerTerms).isEmpty() }
                    } ?: return@execute senses
                // These are conservative evidence thresholds, not calibrated probabilities.
                val verbConflict =
                    senses[winner.first.first()].label in setOf("v.", "vt.", "vi.") &&
                        SenseRanker.hasShortDeterminerPhrase(lookup.before)
                if (verbConflict || winner.second < .35 || winner.second - runner.second < .08)
                    return@execute senses
                accepted.incrementAndGet()
                scores.flatMap { (ids, _) ->
                    ids.map { id ->
                        senses[id].copy(
                            contextual = id in winner.first,
                            semantic = id in winner.first,
                        )
                    }
                }
            } ?: senses
        } finally {
            foreground.decrementAndGet()
        }
    }

    fun closeForTest() {
        val close =
            Job(
                0,
                sequence.incrementAndGet(),
                Callable {
                    release?.cancel(false)
                    encoder?.close()
                    encoder = null
                    cache?.close()
                    cache = null
                    index?.close()
                    index = null
                },
            )
        worker.execute(close)
        close.get(15, TimeUnit.SECONDS)
        idle.shutdownNow()
        worker.shutdownNow()
    }

    companion object {
        private val GENERIC_CONTEXT =
            ("the a an this that these those and are was were have has had with from into onto for but then them they you your his her our its she him their before after where when which word words heading headings appears appear appearing choose chosen list lists item items something thing things example examples called named said says found saw seen next page mentioned mention again yesterday today please explain discussing discuss me us can could should would will might maybe")
                .split(' ')
                .toSet()
        @Volatile private var instance: SemanticLookup? = null

        fun get(context: Context): SemanticLookup =
            instance
                ?: synchronized(this) {
                    instance ?: SemanticLookup(context.applicationContext).also { instance = it }
                }

        fun contextText(context: LookupContext): String =
            Normalizer.normalize(
                    context.semanticWindow
                        ?: (context.before + LookupContext.tokens(context.selected) + context.after)
                            .joinToString(" "),
                    Normalizer.Form.NFKC,
                )
                .lowercase(Locale.ROOT)
                .replace(Regex("\\s+"), " ")
                .trim()

        fun digest(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

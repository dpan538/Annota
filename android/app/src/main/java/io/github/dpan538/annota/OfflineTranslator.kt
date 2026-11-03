package io.github.dpan538.annota

import ai.onnxruntime.*
import android.content.Context
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Only bundled assets, CPU inference, no networking or remote model manager. */
class OfflineTranslator private constructor(private val context: Context) {
    private val worker =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "annota-offline-translation").apply { priority = Thread.NORM_PRIORITY - 1 }
        }
    private var runtime: Runtime? = null
    private var release: ScheduledFuture<*>? = null
    // Memory only: repeated selections must not reload or rerun a model; never persist reading
    // text.
    private val recent =
        object : LinkedHashMap<String, String>(8, .75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, String>?
            ): Boolean = size > 8
        }

    fun request(text: String, active: () -> Boolean, done: (Result<String>) -> Unit) {
        worker.execute {
            if (!active()) return@execute
            release?.cancel(false)
            val result = runCatching {
                require(eligible(text)) { "Select an English phrase or short passage" }
                recent[text]?.let {
                    return@runCatching it
                }
                val model = runtime ?: Runtime(context).also { runtime = it }
                model.translate(text, active).also { if (active()) recent[text] = it }
            }
            if (active()) done(result)
            release =
                worker.schedule(
                    {
                        runtime?.close()
                        runtime = null
                    },
                    45,
                    TimeUnit.SECONDS,
                )
        }
    }

    class Runtime(context: Context) : AutoCloseable {
        private val env = OrtEnvironment.getEnvironment().apply { setTelemetry(false) }
        private val options =
            OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
        private val tokenizer: OfflineTokenizer
        private val encoder: OrtSession
        private val decoder: OrtSession

        init {
            fun asset(name: String) =
                context.assets.open("translation/$name").bufferedReader().use { it.readText() }
            tokenizer = OfflineTokenizer(asset("source-unigram.json"), asset("vocab.json"))
            val manifest = JSONObject(asset("manifest.json")).getJSONObject("files")
            val dir = File(context.filesDir, "translation").apply { mkdirs() }
            fun model(name: String): File {
                val file = File(dir, name)
                val hash = manifest.getJSONObject(name).getString("sha256")
                if (!file.exists() || sha256(file) != hash) {
                    atomicWrite(file) { output ->
                        context.assets.open("translation/$name").use { it.copyTo(output) }
                    }
                    check(sha256(file) == hash) { "Bundled translation model checksum failed" }
                }
                return file
            }
            encoder =
                try {
                    env.createSession(model("encoder.onnx").path, options)
                } catch (failure: Throwable) {
                    options.close()
                    throw failure
                }
            try {
                decoder = env.createSession(model("decoder.onnx").path, options)
            } catch (failure: Throwable) {
                encoder.close()
                options.close()
                throw failure
            }
        }

        fun translate(text: String, active: () -> Boolean = { true }): String {
            check(active()) { "Cancelled" }
            val segments = TranslationInput.segments(text) { tokenizer.encode(it).size }
            val deadline = android.os.SystemClock.uptimeMillis() + 30000
            // Return only a complete result. A failed/cancelled later sentence cannot leave a
            // plausible-looking partial translation in the dictionary panel.
            return segments.joinToString("\n") { segment ->
                check(active()) { "Cancelled" }
                translateSegment(segment, active, deadline)
            }
        }

        private fun translateSegment(text: String, active: () -> Boolean, deadline: Long): String {
            val ids = tokenizer.encode(text)
            require(ids.size <= TranslationInput.MAX_SOURCE_TOKENS) { "The selection is too long" }
            fun longs(values: LongArray) =
                OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(values),
                    longArrayOf(1, values.size.toLong()),
                )
            val input = longs(ids)
            val mask = longs(LongArray(ids.size) { 1L })
            var encoded: OrtSession.Result? = null
            var first: OrtSession.Result? = null
            var previous: OrtSession.Result? = null
            val empty =
                OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, 8, 0, 64))
            try {
                check(active()) { "Cancelled" }
                encoded = encoder.run(mapOf("input_ids" to input, "attention_mask" to mask))
                val hidden = encoded[0] as OnnxTensor
                val output = mutableListOf<Int>()
                var next = 65000
                for (step in 0 until 160) {
                    check(active()) { "Cancelled" }
                    check(android.os.SystemClock.uptimeMillis() < deadline) {
                        "Translation took too long"
                    }
                    val token = longs(longArrayOf(next.toLong()))
                    val branch = OnnxTensor.createTensor(env, booleanArrayOf(step > 0))
                    val feed =
                        mutableMapOf<String, OnnxTensor>(
                            "input_ids" to token,
                            "encoder_attention_mask" to mask,
                            "encoder_hidden_states" to hidden,
                            "use_cache_branch" to branch,
                        )
                    for (layer in 0..5) for (side in listOf("encoder", "decoder")) for (kind in
                        listOf("key", "value")) {
                        val name = "$layer.$side.$kind"
                        val cache = if (side == "encoder") first else previous
                        feed["past_key_values.$name"] =
                            cache?.get("present.$name")?.orElse(null) as? OnnxTensor ?: empty
                    }
                    val result =
                        try {
                            decoder.run(feed)
                        } finally {
                            token.close()
                            branch.close()
                        }
                    if (previous !== first) previous?.close()
                    previous = result
                    if (step == 0) first = result
                    val scores = (result.get("logits").get() as OnnxTensor).floatBuffer
                    var best = Float.NEGATIVE_INFINITY
                    next = 0
                    // Pad is not a generated token. Do not copy foreign input into a fake
                    // translation.
                    for (i in 0 until 65000) {
                        val score = scores.get(i)
                        if (score > best) {
                            best = score
                            next = i
                        }
                    }
                    if (next == 0) {
                        val translation = tokenizer.decode(output)
                        check(!translation.contains("<unk>")) { "Unknown translation token" }
                        check(translation.any { it in '\u3400'..'\u9fff' }) {
                            "No Chinese translation"
                        }
                        return translation
                    }
                    output += next
                }
                error("Translation exceeded the short-sentence limit")
            } finally {
                if (previous !== first) previous?.close()
                first?.close()
                encoded?.close()
                empty.close()
                mask.close()
                input.close()
            }
        }

        override fun close() {
            decoder.close()
            encoder.close()
            options.close()
        }
    }

    companion object {
        @Volatile private var instance: OfflineTranslator? = null

        fun get(context: Context): OfflineTranslator =
            instance
                ?: synchronized(this) {
                    instance ?: OfflineTranslator(context.applicationContext).also { instance = it }
                }

        fun eligible(text: String): Boolean = TranslationInput.eligible(text)
    }
}

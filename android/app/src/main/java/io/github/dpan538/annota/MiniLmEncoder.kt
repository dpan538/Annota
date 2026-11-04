package io.github.dpan538.annota

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.InputStream
import java.nio.LongBuffer
import java.security.MessageDigest
import kotlin.math.sqrt
import org.json.JSONObject

/**
 * APK-bundled all-MiniLM-L6-v2 ARM64 int8 encoder. No network or model downloader. Callers schedule
 * work; this synchronous encoder uses bounded CPU threads and owns no executor. All public
 * operations share one monitor, so close cannot race inference or native resources. Pooling follows
 * the model card: masked mean (including CLS/SEP), then unit L2 normalization.
 * https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2
 */
class MiniLmEncoder(context: Context, private val threads: Int = 2) : AutoCloseable {
    private val context = context.applicationContext
    private var closed = false
    private var manifest: JSONObject? = null
    private var tokenizer: WordPieceTokenizer? = null
    private var runtime: Runtime? = null

    @Synchronized
    fun tokenCount(text: String): Int {
        check(!closed) { "Semantic encoder is closed" }
        return tokenizer().tokenCount(text)
    }

    @Synchronized
    fun encode(text: String): FloatArray {
        check(!closed) { "Semantic encoder is closed" }
        check(!Thread.currentThread().isInterrupted) { "Semantic encoding cancelled" }
        val ids = tokenizer().encode(text)
        // Validate token length before paying the cost of session creation.
        val current =
            runtime ?: Runtime(install("model.onnx"), threads.coerceIn(1, 4)).also { runtime = it }
        check(!Thread.currentThread().isInterrupted) { "Semantic encoding cancelled" }
        return current.encode(ids)
    }

    @Synchronized
    fun prepare() {
        check(!closed) { "Semantic encoder is closed" }
        tokenizer()
        if (runtime == null) runtime = Runtime(install("model.onnx"), threads.coerceIn(1, 4))
    }

    private fun manifest(): JSONObject =
        manifest
            ?: context.assets.open("semantic/manifest.json").bufferedReader().use {
                JSONObject(it.readText()).also { value ->
                    check(value.getInt("embedding_dimension") == DIMENSIONS)
                    check(value.getInt("max_tokens") == MAX_TOKENS)
                    check(value.getString("normalization") == WordPieceTokenizer.VERSION)
                    manifest = value
                }
            }

    private fun tokenizer(): WordPieceTokenizer =
        tokenizer
            ?: WordPieceTokenizer(install("vocab.txt").readLines(Charsets.UTF_8)).also {
                tokenizer = it
            }

    /**
     * A hash-named subdirectory avoids mixing APK model revisions. Verify both reused files and
     * newly extracted bytes; an interrupted/corrupt write never becomes the active model.
     */
    private fun install(name: String): File =
        synchronized(installLock) {
            require(name == "model.onnx" || name == "vocab.txt")
            val expected = manifest().getJSONObject("files").getJSONObject(name).getString("sha256")
            require(expected.matches(Regex("[a-f0-9]{64}"))) { "Invalid semantic asset checksum" }
            val dir = File(context.noBackupFilesDir, "semantic/$expected")
            check(dir.isDirectory || dir.mkdirs()) { "Cannot create semantic model directory" }
            val file = File(dir, name)
            val atomic = AtomicFile(file)
            if (runCatching { atomic.openRead().use(::digest) }.getOrNull() == expected) {
                return@synchronized file
            }
            val output = atomic.startWrite()
            try {
                val hash = MessageDigest.getInstance("SHA-256")
                context.assets.open("semantic/$name").use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        hash.update(buffer, 0, count)
                    }
                }
                check(hex(hash.digest()) == expected) {
                    "Bundled semantic asset checksum failed: $name"
                }
                atomic.finishWrite(output)
            } catch (failure: Throwable) {
                atomic.failWrite(output)
                throw failure
            }
            file
        }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            runtime?.close()
        } finally {
            runtime = null
            tokenizer = null
            manifest = null
        }
    }

    private class Runtime(model: File, threads: Int) : AutoCloseable {
        private val env = OrtEnvironment.getEnvironment().apply { setTelemetry(false) }
        private val options =
            OrtSession.SessionOptions().also { option ->
                try {
                    option.setIntraOpNumThreads(threads)
                    option.setInterOpNumThreads(1)
                    option.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                    option.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    option.setDeterministicCompute(true)
                    option.disableProfiling()
                } catch (failure: Throwable) {
                    option.close()
                    throw failure
                }
            }
        private val session: OrtSession

        init {
            session =
                try {
                    env.createSession(model.path, options).also { opened ->
                        try {
                            check(
                                opened.inputNames ==
                                    setOf("input_ids", "attention_mask", "token_type_ids")
                            ) {
                                "Unsupported semantic model inputs"
                            }
                            check("last_hidden_state" in opened.outputNames) {
                                "Unsupported semantic model output"
                            }
                        } catch (failure: Throwable) {
                            opened.close()
                            throw failure
                        }
                    }
                } catch (failure: Throwable) {
                    options.close()
                    throw failure
                }
        }

        fun encode(ids: LongArray): FloatArray {
            fun tensor(values: LongArray) =
                OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(values),
                    longArrayOf(1, values.size.toLong()),
                )
            // A single unpadded sequence: attention is 1 even for literal special tokens.
            tensor(ids).use { input ->
                tensor(LongArray(ids.size) { 1L }).use { mask ->
                    tensor(LongArray(ids.size)).use { types ->
                        session
                            .run(
                                mapOf(
                                    "input_ids" to input,
                                    "attention_mask" to mask,
                                    "token_type_ids" to types,
                                )
                            )
                            .use { result ->
                                val hidden = result.get("last_hidden_state").get() as OnnxTensor
                                check(
                                    hidden.info.shape.contentEquals(
                                        longArrayOf(1, ids.size.toLong(), DIMENSIONS.toLong())
                                    )
                                ) {
                                    "Unexpected semantic embedding shape"
                                }
                                val values = hidden.floatBuffer
                                val mean = DoubleArray(DIMENSIONS)
                                for (token in ids.indices) for (dimension in 0 until DIMENSIONS) {
                                    val value = values.get(token * DIMENSIONS + dimension)
                                    check(value.isFinite()) { "Non-finite semantic embedding" }
                                    mean[dimension] += value.toDouble() / ids.size
                                }
                                val norm = sqrt(mean.sumOf { it * it })
                                check(norm.isFinite() && norm > 1e-12) {
                                    "Empty semantic embedding"
                                }
                                check(!Thread.currentThread().isInterrupted) {
                                    "Semantic encoding cancelled"
                                }
                                return FloatArray(DIMENSIONS) { (mean[it] / norm).toFloat() }
                            }
                    }
                }
            }
        }

        override fun close() {
            try {
                session.close()
            } finally {
                options.close()
            }
            // OrtEnvironment is process-global and shared with OfflineTranslator; do not close it.
        }
    }

    companion object {
        const val DIMENSIONS = 384
        const val MAX_TOKENS = WordPieceTokenizer.MAX_TOKENS
        private val installLock = Any()

        private fun digest(input: InputStream): String {
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
            return hex(hash.digest())
        }

        private fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

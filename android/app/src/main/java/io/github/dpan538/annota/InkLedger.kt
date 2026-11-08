package io.github.dpan538.annota

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A per-book, append-only tail of ink operations for strokes that are newer than the last
 * whole-book snapshot.
 *
 * The ledger is deliberately not a general WAL: an operation is either one completed [Mark] on a
 * page, or a wholesale page replacement / one-step undo. Every frame carries its own magic,
 * format version, a 32-bit SHA-256 book-id fingerprint, a monotonic sequence, the payload length,
 * and two CRC32 values. A torn final frame is therefore recoverable by dropping that frame only;
 * damaged middle bytes are skipped while later frames are still located by magic.
 *
 * The point codec uses a lossless hybrid: coordinates are quantised to 1/1024 page units when that
 * round-trips bit-exactly, otherwise the exact IEEE-754 bit pattern is mapped to a sortable integer
 * and delta-coded. Both paths use zigzag varints, with a second-order predictor chosen whenever it
 * is shorter. Pressure and tilt use a 16-bit quantised base with an optional exact-float residual,
 * so even those fields round-trip bit-exactly; time and partial-erase width are always exact.
 */
class InkLedger(
    val directory: File,
    private val syncEachAppend: Boolean = true,
    private val maxBytes: Long = 256L * 1024,
    private val maxOps: Int = 512,
) {
    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(maxOps > 0) { "maxOps must be positive" }
        if (!directory.exists() && !directory.mkdirs())
            throw IOException("cannot create ink ledger directory: $directory")
    }

    /** Physical length and valid-record count of one book's log. */
    data class Stats(
        val bytes: Long,
        val ops: Int,
        val intact: Boolean,
        val nextSequence: Long,
    )

    private class LogState(
        var nextSequence: Long,
        var bytes: Long,
        var ops: Int,
        var complete: Boolean = true,
    )

    private data class SeqOp(val sequence: Long, val op: Op)

    private sealed class Op {
        data class Append(val page: Int, val mark: Mark) : Op()

        data class Replace(val page: Int, val marks: List<Mark>, val pop: Boolean = false) : Op()
    }

    private data class Scan(
        val ops: List<SeqOp>,
        val complete: Boolean,
        val maxSequence: Long,
    )

    private class AxisState {
        var q = 0L
        var delta = 0L
        var ordered = 0L
    }

    private data class AxisPlan(val tag: Int, val delta: Long, val rawBits: Int)

    private class CorruptFrame(message: String) : IOException(message)

    private val locks = ConcurrentHashMap<String, Any>()
    private val states = ConcurrentHashMap<String, LogState>()
    private val digests = ConcurrentHashMap<String, Int>()

    /** File used for a book, e.g. `files/ink-ledger/<bookId>.log`. */
    fun logFile(bookId: String): File = File(directory, "${safeId(bookId)}.log")

    /**
     * Append one completed stroke. Returns the sequence number assigned to the frame.
     * The write itself is synchronous; `fd.sync()` is enabled by [syncEachAppend].
     */
    fun append(bookId: String, pageIndex: Int, mark: Mark): Long =
        synchronized(lock(bookId)) {
            val id = safeId(bookId)
            require(pageIndex >= 0) { "page index must be non-negative" }
            val state = prepare(id)
            val sequence = state.nextSequence
            writeAppend(id, sequence, Op.Append(pageIndex, mark))
            state.nextSequence = sequence + 1
            state.ops++
            state.bytes = logFile(id).length()
            maybeCompact(id, state)
            sequence
        }

    /** Replace a page with an explicit list of marks (including an empty list). */
    fun replacePage(bookId: String, pageIndex: Int, marks: List<Mark>): Long =
        synchronized(lock(bookId)) {
            val id = safeId(bookId)
            require(pageIndex >= 0) { "page index must be non-negative" }
            val state = prepare(id)
            val sequence = state.nextSequence
            writeAppend(id, sequence, Op.Replace(pageIndex, marks.toList()))
            state.nextSequence = sequence + 1
            state.ops++
            state.bytes = logFile(id).length()
            maybeCompact(id, state)
            sequence
        }

    /** Record a one-step undo marker for a page, preserving the page's older strokes. */
    fun undo(bookId: String, pageIndex: Int): Long =
        synchronized(lock(bookId)) {
            val id = safeId(bookId)
            require(pageIndex >= 0) { "page index must be non-negative" }
            val state = prepare(id)
            val sequence = state.nextSequence
            writeAppend(id, sequence, Op.Replace(pageIndex, emptyList(), pop = true))
            state.nextSequence = sequence + 1
            state.ops++
            state.bytes = logFile(id).length()
            maybeCompact(id, state)
            sequence
        }

    /**
     * Rebuild the tail represented by the log. Returns `null` when there is no log, or when the
     * whole file is too damaged to yield a single valid frame. A complete but empty log returns an
     * empty map.
     */
    fun replay(bookId: String): Map<Int, List<Mark>>? =
        synchronized(lock(bookId)) {
            val id = safeId(bookId)
            if (!logFile(id).exists()) return@synchronized null
            val scan = scan(id)
            remember(id, scan)
            if (scan.ops.isEmpty() && !scan.complete) return@synchronized null
            applyOps(scan.ops, emptyMap())
        }

    /**
     * Apply the log on top of a known snapshot. Only pages touched by the log are returned; for
     * those pages the returned marks already include [base]. This is what makes a missing newest
     * snapshot recoverable without duplicating strokes that are already persisted.
     */
    fun replayInto(bookId: String, base: Map<Int, List<Mark>>): Map<Int, List<Mark>>? =
        synchronized(lock(bookId)) {
            val id = safeId(bookId)
            if (!logFile(id).exists()) return@synchronized null
            val scan = scan(id)
            remember(id, scan)
            if (scan.ops.isEmpty() && !scan.complete) return@synchronized null
            applyOps(scan.ops, base)
        }

    /** True when every byte of the book's log is covered by a valid frame. */
    fun intact(bookId: String): Boolean =
        synchronized(lock(bookId)) {
            val id = safeId(bookId)
            val file = logFile(id)
            if (!file.exists()) return@synchronized true
            val scan = runCatching { scan(id) }
            scan.getOrNull()?.let { remember(id, it) }
            scan.getOrNull()?.complete ?: false
        }

    /** True when every `*.log` file in the ledger directory is structurally intact. */
    fun intact(): Boolean {
        val logs =
            directory.listFiles()?.filter { it.isFile && it.name.endsWith(".log") } ?: return true
        return logs.all { runCatching { intact(it.name.removeSuffix(".log")) }.getOrDefault(false) }
    }

    /** Sequence that will be assigned to the next frame. May scan/repair the file. */
    fun position(bookId: String): Long =
        synchronized(lock(bookId)) { prepare(safeId(bookId)).nextSequence }

    /**
     * Sequence already known from an append, replay, or compaction in this process. This is an
     * O(1) cache lookup with no filesystem access, for UI-thread transaction watermarking.
     * Returns 0 when the log has not been touched yet; callers then cannot trim anything.
     */
    fun cachedPosition(bookId: String): Long {
        val id = safeId(bookId)
        return states[id]?.nextSequence ?: 0L
    }

    /**
     * Drop every frame whose sequence is at or below [through]. Frames above the watermark are
     * rewritten unchanged, so a snapshot can commit while newer strokes are still arriving.
     * If no frames remain the log is deleted.
     */
    fun trimThrough(bookId: String, through: Long): Unit =
        synchronized(lock(bookId)) {
            val id = safeId(bookId)
            val file = logFile(id)
            if (!file.exists()) {
                states[id] = LogState(0, 0, 0)
                return@synchronized
            }
            val scan = scan(id)
            val kept = scan.ops.filter { it.sequence > through }
            if (kept.isEmpty()) {
                file.delete()
                File(directory, "$id.log.tmp").delete()
                states[id] = LogState(0, 0, 0)
                return@synchronized
            }
            writeFrames(id, kept)
        }

    /** Remove a book's tail after a snapshot has fully absorbed it. */
    fun clear(bookId: String) =
        synchronized(lock(bookId)) {
            val id = safeId(bookId)
            logFile(id).delete()
            File(directory, "$id.log.tmp").delete()
            states[id] = LogState(0, 0, 0)
        }

    /** Rewrite the current state as the smallest equivalent log: one replace per touched page. */
    fun compact(bookId: String): Unit =
        synchronized(lock(bookId)) {
            val id = safeId(bookId)
            rewriteCompacted(id, scan(id).ops)
        }

    fun stats(bookId: String): Stats =
        synchronized(lock(bookId)) {
            val id = safeId(bookId)
            val file = logFile(id)
            if (!file.exists()) return@synchronized Stats(0, 0, true, 0)
            val scan = runCatching { scan(id) }.getOrNull()
            if (scan == null) Stats(file.length(), 0, false, 0)
            else {
                remember(id, scan)
                Stats(
                    bytes = file.length(),
                    ops = scan.ops.size,
                    intact = scan.complete,
                    nextSequence = (scan.maxSequence + 1).coerceAtLeast(0),
                )
            }
        }

    private fun lock(bookId: String): Any = locks.computeIfAbsent(bookId) { Any() }

    private fun safeId(bookId: String): String {
        require(
            bookId.isNotEmpty() &&
                bookId.length <= 128 &&
                bookId.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' } &&
                bookId != "." &&
                bookId != ".."
        ) { "invalid book id for ink ledger" }
        return bookId
    }

    private fun prepare(bookId: String): LogState {
        val file = logFile(bookId)
        val cached = states[bookId]
        if (cached != null && cached.complete && file.exists() && file.length() == cached.bytes)
            return cached
        if (!file.exists()) {
            val fresh = LogState(0, 0, 0)
            states[bookId] = fresh
            return fresh
        }
        val scan = scan(bookId)
        if (!scan.complete) {
            // A torn or damaged tail is the normal crash case. Keep every valid frame, drop the
            // invalid bytes, and continue from a clean file.
            if (scan.ops.isNotEmpty()) {
                val rewritten = rewriteCompacted(bookId, scan.ops)
                states[bookId] = rewritten
                return rewritten
            }
            RandomAccessFile(file, "rw").use { it.setLength(0) }
            val fresh = LogState(0, 0, 0)
            states[bookId] = fresh
            return fresh
        }
        val state = LogState(scan.maxSequence + 1, file.length(), scan.ops.size)
        states[bookId] = state
        return state
    }

    private fun remember(bookId: String, scan: Scan): LogState {
        val file = logFile(bookId)
        val state =
            LogState(
                nextSequence = (scan.maxSequence + 1).coerceAtLeast(0),
                bytes = if (file.exists()) file.length() else 0,
                ops = scan.ops.size,
                complete = scan.complete,
            )
        states[bookId] = state
        return state
    }

    private fun maybeCompact(bookId: String, state: LogState) {
        if (state.ops >= maxOps || state.bytes >= maxBytes) {
            rewriteCompacted(bookId, scan(bookId).ops)
        }
    }

    private fun rewriteCompacted(bookId: String, ops: List<SeqOp>): LogState {
        val final = applyOps(ops)
        var sequence = 0L
        val minimal =
            final.map { (page, marks) -> SeqOp(sequence++, Op.Replace(page, marks.toList())) }
        val state = writeFrames(bookId, minimal)
        states[bookId] = state
        return state
    }

    private fun writeAppend(bookId: String, sequence: Long, op: Op) {
        val payload = encodePayload(op)
        val frame = frame(bookId, opCode(op), sequence, payload)
        FileOutputStream(logFile(bookId), true).use { out ->
            out.write(frame)
            out.flush()
            if (syncEachAppend) out.fd.sync()
        }
    }

    private fun writeFrames(bookId: String, ops: List<SeqOp>): LogState {
        val file = logFile(bookId)
        val temp = File(directory, "$bookId.log.tmp")
        if (temp.exists()) temp.delete()
        try {
            FileOutputStream(temp).use { out ->
                ops.forEach { seqOp ->
                    out.write(
                        frame(bookId, opCode(seqOp.op), seqOp.sequence, encodePayload(seqOp.op))
                    )
                }
                out.flush()
                if (syncEachAppend) out.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
        val state =
            LogState(
                nextSequence = (ops.maxOfOrNull { it.sequence } ?: -1L) + 1,
                bytes = file.length(),
                ops = ops.size,
            )
        states[bookId] = state
        return state
    }

    private fun frame(bookId: String, op: Int, sequence: Long, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "ink ledger frame too large" }
        val header = ByteArray(HEADER_SIZE)
        putInt(header, 0, MAGIC)
        header[4] = VERSION.toByte()
        header[5] = op.toByte()
        putInt(header, 6, bookDigest(bookId))
        putLong(header, 10, sequence)
        putInt(header, 18, payload.size)
        putInt(header, 22, crc32(payload, 0, payload.size))
        putInt(header, 26, crc32(header, 0, 26))
        return header + payload
    }

    private fun scan(bookId: String): Scan {
        val file = logFile(bookId)
        if (!file.exists()) return Scan(emptyList(), true, -1)
        if (file.length() > MAX_LOG_BYTES) throw IOException("ink ledger is unreasonably large")
        val data = file.readBytes()
        val digest = bookDigest(bookId)
        val ops = ArrayList<SeqOp>()
        var offset = 0
        var complete = true
        var maxSequence = -1L
        while (offset < data.size) {
            if (data.size - offset < HEADER_SIZE) {
                complete = false
                break
            }
            if (intAt(data, offset) != MAGIC) {
                complete = false
                val next = findMagic(data, offset + 1)
                if (next < 0) break
                offset = next
                continue
            }
            val version = data[offset + 4].toInt() and 0xff
            val op = data[offset + 5].toInt() and 0xff
            val frameDigest = intAt(data, offset + 6)
            val sequence = longAt(data, offset + 10)
            val length = intAt(data, offset + 18)
            val payloadCrc = intAt(data, offset + 22)
            val headerCrc = intAt(data, offset + 26)
            if (
                version != VERSION ||
                    frameDigest != digest ||
                    length < 0 ||
                    length > MAX_PAYLOAD ||
                    headerCrc != crc32(data, offset, 26)
            ) {
                complete = false
                val next = findMagic(data, offset + 1)
                if (next < 0) break
                offset = next
                continue
            }
            val start = offset + HEADER_SIZE
            if (length > data.size - start) {
                complete = false // torn final frame
                break
            }
            val end = start + length
            if (payloadCrc != crc32(data, start, length)) {
                complete = false
                offset = end
                continue
            }
            val decoded =
                runCatching { decodePayload(op, data.copyOfRange(start, end)) }.getOrNull()
            if (decoded == null) {
                complete = false
                offset = end
                continue
            }
            ops.add(SeqOp(sequence, decoded))
            if (sequence > maxSequence) maxSequence = sequence
            offset = end
        }
        if (offset != data.size) complete = false
        return Scan(ops, complete, maxSequence)
    }

    private fun findMagic(data: ByteArray, from: Int): Int {
        var i = from
        val last = data.size - 4
        while (i <= last) {
            if (intAt(data, i) == MAGIC) return i
            i++
        }
        return -1
    }

    private fun applyOps(
        ops: List<SeqOp>,
        base: Map<Int, List<Mark>> = emptyMap(),
    ): Map<Int, List<Mark>> {
        val pages = TreeMap<Int, MutableList<Mark>>()
        fun page(index: Int): MutableList<Mark> =
            pages.getOrPut(index) { base[index]?.toMutableList() ?: mutableListOf() }
        ops.forEach { seqOp ->
            when (val op = seqOp.op) {
                is Op.Append -> page(op.page).add(op.mark)
                is Op.Replace ->
                    if (op.pop) {
                        val existing = pages[op.page] ?: base[op.page]?.toMutableList()
                        if (existing != null) {
                            if (existing.isNotEmpty()) existing.removeAt(existing.lastIndex)
                            pages[op.page] = existing
                        }
                    } else {
                        pages[op.page] = op.marks.toMutableList()
                    }
            }
        }
        return pages.mapValues { it.value.toList() }
    }

    private fun encodePayload(op: Op): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        when (op) {
            is Op.Append -> {
                writeVarLong(out, op.page.toLong())
                encodeMark(out, op.mark)
            }
            is Op.Replace -> {
                writeVarLong(out, op.page.toLong())
                out.writeByte(if (op.pop) 1 else 0)
                if (!op.pop) {
                    require(op.marks.size <= MAX_MARKS) { "too many marks in one page" }
                    writeVarLong(out, op.marks.size.toLong())
                    op.marks.forEach { encodeMark(out, it) }
                }
            }
        }
        out.flush()
        return bytes.toByteArray()
    }

    private fun decodePayload(op: Int, payload: ByteArray): Op {
        val input = DataInputStream(ByteArrayInputStream(payload))
        val page = readCount(input, MAX_POINTS, "page index").also { /* range checked */ }
        return when (op) {
            OP_APPEND -> Op.Append(page, decodeMark(input))
            OP_REPLACE -> {
                val mode = input.readUnsignedByte()
                when (mode) {
                    0 -> {
                        val count = readCount(input, MAX_MARKS, "mark count")
                        val marks = ArrayList<Mark>(count)
                        repeat(count) { marks.add(decodeMark(input)) }
                        if (input.available() != 0) throw CorruptFrame("trailing replace bytes")
                        Op.Replace(page, marks)
                    }
                    1 -> {
                        if (input.available() != 0) throw CorruptFrame("trailing undo bytes")
                        Op.Replace(page, emptyList(), pop = true)
                    }
                    else -> throw CorruptFrame("unknown replace mode")
                }
            }
            else -> throw CorruptFrame("unknown op")
        }.also {
            if (op == OP_APPEND && input.available() != 0)
                throw CorruptFrame("trailing append bytes")
        }
    }

    private fun opCode(op: Op): Int =
        when (op) {
            is Op.Append -> OP_APPEND
            is Op.Replace -> OP_REPLACE
        }

    private fun encodeMark(out: DataOutputStream, mark: Mark) {
        writeTool(out, mark.tool)
        writeZigzag(out, mark.color.toLong())
        out.writeInt(mark.width.toRawBits())
        writeVarLong(out, mark.inkVersion.toLong())
        var flags = 0
        if (mark.filled) flags = flags or 1
        if (mark.refined) flags = flags or 2
        if (mark.image.isNotEmpty()) flags = flags or 4
        if (mark.rotation.toRawBits() != 0) flags = flags or 8
        if (mark.text.isNotEmpty()) flags = flags or 16
        out.writeByte(flags)
        if (mark.image.isNotEmpty()) writeString(out, mark.image)
        if (mark.rotation.toRawBits() != 0) out.writeInt(mark.rotation.toRawBits())
        if (mark.text.isNotEmpty()) writeString(out, mark.text)
        require(mark.points.size <= MAX_POINTS) { "too many points in one stroke" }
        writeVarLong(out, mark.points.size.toLong())
        val xState = AxisState()
        val yState = AxisState()
        var lastTime = 0L
        mark.points.forEach { point ->
            var pointFlags = 0
            if (point.p.toRawBits() != 1f.toRawBits()) pointFlags = pointFlags or 1
            if (point.t != 0L) pointFlags = pointFlags or 2
            if (point.w.toRawBits() != 0) pointFlags = pointFlags or 4
            if (point.tilt.toRawBits() != 0) pointFlags = pointFlags or 8
            out.writeByte(pointFlags)
            val xPlan = planAxis(point.x, xState)
            val yPlan = planAxis(point.y, yState)
            val pressureCode = pressureCode(point.p)
            val tiltCode = tiltCode(point.tilt)
            val pressureExact =
                pointFlags and 1 != 0 &&
                    (pressureCode / 65535f).toRawBits() != point.p.toRawBits()
            val tiltExact =
                pointFlags and 8 != 0 &&
                    (tiltCode * TILT_STEP).toRawBits() != point.tilt.toRawBits()
            var tags = xPlan.tag or (yPlan.tag shl 2)
            if (pressureExact) tags = tags or TAG_PRESSURE_EXACT
            if (tiltExact) tags = tags or TAG_TILT_EXACT
            out.writeByte(tags)
            if (pointFlags and 1 != 0) {
                out.writeShort(pressureCode)
                if (pressureExact) out.writeInt(point.p.toRawBits())
            }
            if (pointFlags and 2 != 0) {
                writeZigzag(out, point.t - lastTime)
                lastTime = point.t
            } else {
                lastTime = 0L
            }
            if (pointFlags and 4 != 0) out.writeInt(point.w.toRawBits())
            if (pointFlags and 8 != 0) {
                out.writeShort(tiltCode)
                if (tiltExact) out.writeInt(point.tilt.toRawBits())
            }
            writeAxis(out, xPlan)
            writeAxis(out, yPlan)
        }
    }

    private fun decodeMark(input: DataInputStream): Mark {
        val tool = readTool(input)
        val color = readZigzag(input).toInt()
        val width = Float.fromBits(input.readInt())
        val inkVersion = readVarLong(input).toInt()
        val flags = input.readUnsignedByte()
        val image = if (flags and 4 != 0) readString(input) else ""
        val rotation = if (flags and 8 != 0) Float.fromBits(input.readInt()) else 0f
        val text = if (flags and 16 != 0) readString(input) else ""
        val count = readCount(input, MAX_POINTS, "point count")
        val points = ArrayList<Point>(count)
        val xState = AxisState()
        val yState = AxisState()
        var lastTime = 0L
        repeat(count) {
            val pointFlags = input.readUnsignedByte()
            val tags = input.readUnsignedByte()
            val pressure =
                if (pointFlags and 1 != 0) {
                    val code = input.readUnsignedShort()
                    if (tags and TAG_PRESSURE_EXACT != 0) Float.fromBits(input.readInt())
                    else code / 65535f
                } else 1f
            val time =
                if (pointFlags and 2 != 0) {
                    val delta = readZigzag(input)
                    lastTime = addExact(lastTime, delta, "time delta")
                    lastTime
                } else {
                    lastTime = 0L
                    0L
                }
            val partial = if (pointFlags and 4 != 0) Float.fromBits(input.readInt()) else 0f
            val tilt =
                if (pointFlags and 8 != 0) {
                    val code = input.readUnsignedShort()
                    if (tags and TAG_TILT_EXACT != 0) Float.fromBits(input.readInt())
                    else code * TILT_STEP
                } else 0f
            val x = readAxis(tags and 3, input, xState)
            val y = readAxis((tags ushr 2) and 3, input, yState)
            points.add(Point(x, y, pressure, time, partial, tilt))
        }
        return Mark(
            tool,
            color,
            width,
            points,
            image,
            flags and 1 != 0,
            inkVersion,
            flags and 2 != 0,
            rotation,
            text,
        )
    }

    private fun planAxis(value: Float, state: AxisState): AxisPlan {
        val bits = value.toRawBits()
        val quantised = quantise(value)
        if (quantised != null) {
            val delta = quantised - state.q
            val second = delta - state.delta
            val tag = if (varintSize(zigzag(second)) < varintSize(zigzag(delta))) 1 else 0
            state.q = quantised
            state.delta = delta
            state.ordered = order(bits)
            return AxisPlan(tag, if (tag == 1) second else delta, 0)
        }
        val ordered = order(bits)
        val delta = ordered - state.ordered
        state.q = approximateQuantised(value)
        state.delta = 0
        state.ordered = ordered
        return if (varintSize(zigzag(delta)) > 4) AxisPlan(3, 0, bits)
        else AxisPlan(2, delta, 0)
    }

    private fun writeAxis(out: DataOutputStream, plan: AxisPlan) {
        when (plan.tag) {
            0,
            1,
            2 -> writeZigzag(out, plan.delta)
            3 -> out.writeInt(plan.rawBits)
            else -> throw IllegalStateException("bad axis plan")
        }
    }

    private fun readAxis(tag: Int, input: DataInputStream, state: AxisState): Float =
        when (tag) {
            0 -> {
                val delta = readZigzag(input)
                state.delta = delta
                state.q = addExact(state.q, delta, "x delta")
                val value = quantisedToFloat(state.q)
                state.ordered = order(value.toRawBits())
                value
            }
            1 -> {
                val second = readZigzag(input)
                state.delta = addExact(state.delta, second, "second delta")
                state.q = addExact(state.q, state.delta, "x delta")
                val value = quantisedToFloat(state.q)
                state.ordered = order(value.toRawBits())
                value
            }
            2 -> {
                val delta = readZigzag(input)
                val ordered = addExact(state.ordered, delta, "ordered float delta")
                if (ordered < 0 || ordered > 0xffffffffL)
                    throw CorruptFrame("ordered float out of range")
                state.ordered = ordered
                val value = Float.fromBits(unorder(ordered))
                state.q = approximateQuantised(value)
                state.delta = 0
                value
            }
            3 -> {
                val bits = input.readInt()
                val value = Float.fromBits(bits)
                state.ordered = order(bits)
                state.q = approximateQuantised(value)
                state.delta = 0
                value
            }
            else -> throw CorruptFrame("unknown axis tag")
        }

    private fun quantise(value: Float): Long? {
        if (!value.isFinite()) return null
        val scaled = value.toDouble() * SCALE
        if (abs(scaled) > 9.0e15) return null
        val q = Math.round(scaled)
        return if (quantisedToFloat(q).toRawBits() == value.toRawBits()) q else null
    }

    private fun approximateQuantised(value: Float): Long {
        if (!value.isFinite()) return 0
        val scaled = value.toDouble() * SCALE
        if (abs(scaled) > 9.0e15) return 0
        return Math.round(scaled)
    }

    private fun quantisedToFloat(q: Long): Float = q.toFloat() * INVERSE_SCALE

    private fun pressureCode(value: Float): Int =
        if (!value.isFinite()) 0
        else (value.coerceIn(0f, 1f) * 65535f).roundToInt().coerceIn(0, 65535)

    private fun tiltCode(value: Float): Int =
        if (!value.isFinite()) 0
        else ((value.coerceIn(0f, 1.4f) / 1.4f) * 65535f).roundToInt().coerceIn(0, 65535)

    private fun writeTool(out: DataOutputStream, tool: String) {
        val code = TOOLS.indexOf(tool)
        if (code >= 0) writeVarLong(out, (code + 1).toLong())
        else {
            writeVarLong(out, 0)
            writeString(out, tool)
        }
    }

    private fun readTool(input: DataInputStream): String {
        val code = readVarLong(input)
        if (code == 0L) return readString(input)
        if (code < 1 || code > TOOLS.size) throw CorruptFrame("unknown tool code")
        return TOOLS[code.toInt() - 1]
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_STRING) throw IOException("ink ledger string too large")
        writeVarLong(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = readVarLong(input)
        if (length < 0 || length > MAX_STRING) throw CorruptFrame("bad string length")
        val bytes = ByteArray(length.toInt())
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun writeZigzag(out: DataOutputStream, value: Long) =
        writeVarLong(out, zigzag(value))

    private fun readZigzag(input: DataInputStream): Long = unzigzag(readVarLong(input))

    private fun writeVarLong(out: DataOutputStream, value: Long) {
        var v = value
        while ((v and 0x7fL.inv()) != 0L) {
            out.writeByte(((v and 0x7fL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.writeByte(v.toInt())
    }

    private fun readVarLong(input: DataInputStream): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (shift > 63) throw CorruptFrame("varint overflow")
            val b = input.readUnsignedByte()
            result = result or ((b.toLong() and 0x7fL) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    private fun readCount(input: DataInputStream, max: Int, what: String): Int {
        val value = readVarLong(input)
        if (value < 0 || value > max) throw CorruptFrame("bad $what")
        return value.toInt()
    }

    private fun zigzag(value: Long): Long = (value shl 1) xor (value shr 63)

    private fun unzigzag(value: Long): Long = (value ushr 1) xor -(value and 1L)

    private fun varintSize(value: Long): Int {
        var v = value
        var size = 1
        while ((v and 0x7fL.inv()) != 0L) {
            v = v ushr 7
            size++
        }
        return size
    }

    private fun addExact(a: Long, b: Long, what: String): Long =
        try {
            Math.addExact(a, b)
        } catch (_: ArithmeticException) {
            throw CorruptFrame("$what overflow")
        }

    /** Exact lossless mapping from IEEE bits to a sortable unsigned 32-bit range. */
    private fun order(bits: Int): Long {
        val unsigned = bits.toLong() and 0xffffffffL
        return if (bits < 0) unsigned xor 0xffffffffL else unsigned xor 0x80000000L
    }

    private fun unorder(ordered: Long): Int {
        val value = ordered and 0xffffffffL
        val bits =
            if (value and 0x80000000L != 0L) value xor 0x80000000L
            else value xor 0xffffffffL
        return bits.toInt()
    }

    private fun bookDigest(bookId: String): Int =
        digests.computeIfAbsent(bookId) {
            val digest = MessageDigest.getInstance("SHA-256").digest(bookId.toByteArray())
            intAt(digest, 0)
        }

    private fun crc32(data: ByteArray, offset: Int, length: Int): Int {
        val crc = CRC32()
        crc.update(data, offset, length)
        return crc.value.toInt()
    }

    private fun intAt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)

    private fun longAt(data: ByteArray, offset: Int): Long =
        (intAt(data, offset).toLong() shl 32) or (intAt(data, offset + 4).toLong() and 0xffffffffL)

    private fun putInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 24).toByte()
        data[offset + 1] = (value ushr 16).toByte()
        data[offset + 2] = (value ushr 8).toByte()
        data[offset + 3] = value.toByte()
    }

    private fun putLong(data: ByteArray, offset: Int, value: Long) {
        putInt(data, offset, (value ushr 32).toInt())
        putInt(data, offset + 4, value.toInt())
    }

    private companion object {
        const val MAGIC = 0x494e4b4c // "INKL"
        const val VERSION = 1
        const val OP_APPEND = 1
        const val OP_REPLACE = 2
        const val HEADER_SIZE = 30
        const val MAX_PAYLOAD = 64 * 1024 * 1024
        const val MAX_LOG_BYTES = 512L * 1024 * 1024
        const val MAX_POINTS = 1_000_000
        const val MAX_MARKS = 1_000_000
        const val MAX_STRING = 1_048_576
        const val SCALE = 1024f
        const val INVERSE_SCALE = 1f / SCALE
        const val TILT_STEP = 1.4f / 65535f
        const val TAG_PRESSURE_EXACT = 0x10
        const val TAG_TILT_EXACT = 0x20
        val TOOLS =
            arrayOf(
                "pen",
                "fountain",
                "pencil",
                "highlight",
                "eraser",
                "line",
                "arrow",
                "rectangle",
                "ellipse",
                "triangle",
                "text",
                "image",
            )
    }
}

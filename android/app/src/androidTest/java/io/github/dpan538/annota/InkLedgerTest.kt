package io.github.dpan538.annota

import android.graphics.Color
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.util.Collections
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ink ledger and asynchronous whole-book snapshot acceptance tests.
 *
 * These run against the emulator's real filesystem and SQLite; the snapshot timing is therefore a
 * conservative upper bound compared with the target tablet.
 */
@RunWith(AndroidJUnit4::class)
class InkLedgerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: Store
    private lateinit var commitExecutor: ExecutorService
    private val roots = mutableListOf<File>()

    @Before
    fun setup() {
        Assume.assumeTrue(
            "Ink ledger tests run on the emulator",
            Build.HARDWARE in setOf("ranchu", "goldfish"),
        )
        context.deleteDatabase("library.sqlite")
        store = Store(context)
        commitExecutor = Executors.newSingleThreadExecutor()
        synchronized(EVIDENCE) {
            EVIDENCE.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            EVIDENCE.put("hardware", Build.HARDWARE)
            EVIDENCE.put("sdk", Build.VERSION.SDK_INT)
            EVIDENCE.put("timestamp", System.currentTimeMillis())
        }
    }

    @After
    fun cleanup() {
        if (::commitExecutor.isInitialized) {
            runCatching { store.flush() }
            commitExecutor.shutdownNow()
            commitExecutor.awaitTermination(5, TimeUnit.SECONDS)
        }
        if (::store.isInitialized) store.close()
        context.deleteDatabase("library.sqlite")
        roots.forEach { it.deleteRecursively() }
        roots.clear()
    }

    @Test
    fun appendRoundTripsCoordinatesBitExactAndQuantisesPressure() {
        val ledger = InkLedger(newRoot("round-trip"), syncEachAppend = false)
        val mark = randomMark(seed = 7, count = 257, tool = "fountain")
        val sequence = ledger.append("book-round", 3, mark)
        assertEquals(0L, sequence)

        val recovered = ledger.replay("book-round")
        assertNotNull(recovered)
        val got = recovered!!.getValue(3).single()
        assertEquals(mark.tool, got.tool)
        assertEquals(mark.color, got.color)
        assertEquals(mark.width.toRawBits(), got.width.toRawBits())
        assertEquals(mark.inkVersion, got.inkVersion)
        assertEquals(mark.image, got.image)
        assertEquals(mark.filled, got.filled)
        assertEquals(mark.refined, got.refined)
        assertEquals(mark.rotation.toRawBits(), got.rotation.toRawBits())
        assertPointsEquivalent(mark, got)
        assertEquals("whole Mark must round-trip", mark, got)
        assertTrue(ledger.intact("book-round"))

        val unknown =
            Mark(
                tool = "custom-tool",
                color = -123456,
                width = .25f,
                points =
                    mutableListOf(
                        Point(x = -0.0f, y = 1e-7f, p = 0f, t = 0L, w = 0f, tilt = 0f),
                        Point(x = 1e9f, y = -1e-9f, p = 1f, t = 5L, w = .0f, tilt = 1.4f),
                    ),
                image = "sig.png",
                filled = true,
                inkVersion = 0,
                refined = false,
                rotation = 0f,
            )
        ledger.append("book-round", 0, unknown)
        val unknownGot = ledger.replay("book-round")!!.getValue(0).single()
        assertEquals("custom-tool", unknownGot.tool)
        assertEquals(unknown.image, unknownGot.image)
        assertEquals((-0.0f).toRawBits(), unknownGot.points[0].x.toRawBits())
        assertEquals(1e-7f.toRawBits(), unknownGot.points[0].y.toRawBits())
        assertEquals(1e9f.toRawBits(), unknownGot.points[1].x.toRawBits())
        assertEquals((-1e-9f).toRawBits(), unknownGot.points[1].y.toRawBits())
        assertEquals(0f.toRawBits(), unknownGot.points[0].p.toRawBits())
        assertEquals(1f.toRawBits(), unknownGot.points[1].p.toRawBits())
        assertEquals(1.4f.toRawBits(), unknownGot.points[1].tilt.toRawBits())
        assertTrue(ledger.intact("book-round"))

        record(
            "roundTrip",
            JSONObject()
                .put("points", mark.points.size)
                .put("bytes", ledger.stats("book-round").bytes)
                .put("coordinateRoundTrip", "bit-exact")
                .put("pressure", "16-bit quantised + exact residual")
                .put("tilt", "16-bit quantised + exact residual")
                .put("unknownTool", "generic-string"),
        )
    }

    @Test
    fun tenThousandPointCodecMeasuresEncodeDecodeAndStaysBitExact() {
        val ledger = InkLedger(newRoot("ten-thousand"), syncEachAppend = false)
        val mark = randomMark(seed = 11, count = 10_000, tool = "pen")
        val encodeStart = System.nanoTime()
        ledger.append("book-10k", 0, mark)
        val encodeMs = (System.nanoTime() - encodeStart) / 1_000_000.0
        val decodeStart = System.nanoTime()
        val got = ledger.replay("book-10k")!!.getValue(0).single()
        val decodeMs = (System.nanoTime() - decodeStart) / 1_000_000.0
        assertPointsEquivalent(mark, got)
        assertEquals("10k Mark must round-trip", mark, got)
        val stats = ledger.stats("book-10k")
        assertTrue("encoded 10k points in ${encodeMs}ms", encodeMs < 3_000)
        assertTrue("decoded 10k points in ${decodeMs}ms", decodeMs < 3_000)
        assertTrue(stats.bytes > 0)
        assertTrue(stats.intact)
        record(
            "tenThousandPoints",
            JSONObject()
                .put("points", 10_000)
                .put("encodeMs", round(encodeMs))
                .put("decodeMs", round(decodeMs))
                .put("bytes", stats.bytes)
                .put("intact", stats.intact),
        )
    }

    @Test
    fun tornTailDropsOnlyLastOperationAndCanContinue() {
        val ledger = InkLedger(newRoot("torn"), syncEachAppend = false)
        repeat(3) { ledger.append("book-torn", 0, randomMark(seed = 20 + it, count = 16)) }
        val file = ledger.logFile("book-torn")
        assertTrue(ledger.intact("book-torn"))
        assertEquals(3, ledger.replay("book-torn")!!.getValue(0).size)

        RandomAccessFile(file, "rw").use { it.setLength(file.length() - 7) }
        assertFalse(ledger.intact("book-torn"))
        val torn = ledger.replay("book-torn")
        assertNotNull(torn)
        assertEquals(2, torn!!.getValue(0).size)
        val lengthAfterTear = file.length()

        // The next append must repair the torn tail rather than refuse to write.
        ledger.append("book-torn", 0, randomMark(seed = 30, count = 16))
        assertTrue(ledger.intact("book-torn"))
        assertEquals(3, ledger.replay("book-torn")!!.getValue(0).size)

        val corruptRoot = newRoot("corrupt")
        File(corruptRoot, "book-corrupt.log").writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7))
        val corrupt = InkLedger(corruptRoot, syncEachAppend = false)
        assertFalse(corrupt.intact("book-corrupt"))
        assertNull(corrupt.replay("book-corrupt"))

        record(
            "tornTail",
            JSONObject()
                .put("framesBefore", 3)
                .put("recoveredAfterTruncation", 2)
                .put("bytesAfterTruncation", lengthAfterTear)
                .put("intactAfterRepair", ledger.intact("book-torn"))
                .put("allCorruptReturnsNull", corrupt.replay("book-corrupt") == null),
        )
    }

    @Test
    fun compactionCollapsesToMinimalLogAndPreservesReplay() {
        val ledger =
            InkLedger(
                newRoot("compact"),
                syncEachAppend = false,
                maxBytes = Long.MAX_VALUE,
                maxOps = Int.MAX_VALUE,
            )
        repeat(60) { ledger.append("book-compact", it % 5, randomMark(seed = 100 + it, count = 8)) }
        ledger.replacePage(
            "book-compact",
            1,
            listOf(randomMark(seed = 200, count = 20), randomMark(seed = 201, count = 20)),
        )
        ledger.undo("book-compact", 2)
        val before = ledger.replay("book-compact")!!
        val beforeStats = ledger.stats("book-compact")
        assertTrue(beforeStats.ops >= 60)

        ledger.compact("book-compact")
        val after = ledger.replay("book-compact")!!
        val afterStats = ledger.stats("book-compact")
        assertEquals(before.keys, after.keys)
        before.keys.forEach { page ->
            val a = before.getValue(page)
            val b = after.getValue(page)
            assertEquals(a.size, b.size)
            a.indices.forEach { assertMarkEquivalent(a[it], b[it]) }
        }
        assertTrue("compacted ops ${afterStats.ops}", afterStats.ops <= 5)
        assertTrue("compacted bytes ${afterStats.bytes} < ${beforeStats.bytes}", afterStats.bytes < beforeStats.bytes)
        assertTrue(ledger.intact("book-compact"))

        val auto =
            InkLedger(
                newRoot("auto-compact"),
                syncEachAppend = false,
                maxBytes = 4096,
                maxOps = 8,
            )
        repeat(40) { auto.append("book-auto", 0, randomMark(seed = 300 + it, count = 6)) }
        val autoStats = auto.stats("book-auto")
        assertEquals(40, auto.replay("book-auto")!!.getValue(0).size)
        assertTrue("auto compacted ops ${autoStats.ops}", autoStats.ops <= 8)
        assertTrue(auto.intact("book-auto"))

        record(
            "compaction",
            JSONObject()
                .put("beforeOps", beforeStats.ops)
                .put("beforeBytes", beforeStats.bytes)
                .put("afterOps", afterStats.ops)
                .put("afterBytes", afterStats.bytes)
                .put("autoCompactedOps", autoStats.ops)
                .put("autoRecoveredMarks", 40),
        )
    }

    @Test
    fun ledgerTailRecoversWhenLatestSnapshotMissesIt() {
        val book = store.newNote("Ledger recovery", "blank", "white")
        val persisted = randomMark(seed = 400, count = 9)
        book.pages[0].marks.add(persisted)
        store.commit(book)

        // This is the newest snapshot; it cannot know about the stroke appended only to the log.
        val base = store.book(book.id)
        val snapshotMark = base.pages[0].marks[0]
        val tail = randomMark(seed = 401, count = 11)
        val sequence = store.inkLedger.append(book.id, 0, tail)
        assertEquals(1, store.inkLedger.stats(book.id).ops)
        val ledgerBytes = store.inkLedger.stats(book.id).bytes

        val serializationsBeforeRecovery = store.stateSerializations
        assertTrue(store.recoverInk(base))
        assertEquals(serializationsBeforeRecovery, store.stateSerializations)
        assertEquals(2, base.pages[0].marks.size)
        assertSame(snapshotMark, base.pages[0].marks[0])

        val error = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val serializationsBeforeCommit = store.stateSerializations
        store.commitAsync(base, commitExecutor) {
            error.set(it)
            done.countDown()
        }
        assertTrue(done.await(60, TimeUnit.SECONDS))
        store.flush()
        assertNull(error.get())
        assertEquals(1, store.stateSerializations - serializationsBeforeCommit)
        val saved = store.book(book.id)
        assertEquals(2, saved.pages[0].marks.size)
        assertMarkEquivalent(persisted, saved.pages[0].marks[0])

        // The snapshot now owns the tail; transactionally prune only frames through that sequence.
        store.inkLedger.trimThrough(book.id, sequence)
        assertNull(store.inkLedger.replay(book.id))
        record(
            "recovery",
            JSONObject()
                .put("snapshotMarks", 1)
                .put("tailMarks", 1)
                .put("recoveredMarks", base.pages[0].marks.size)
                .put("ledgerBytes", ledgerBytes)
                .put("commitSerializations", 1)
                .put("tailTrimmed", store.inkLedger.replay(book.id) == null),
        )
    }

    @Test
    fun asyncCommitOfTwoThousandMarksSnapshotsOnceAndUndoAfterFlush() {
        val book = store.newNote("Async ledger", "blank", "white")
        val page = book.pages[0]
        repeat(2_000) { index ->
            val points = ArrayList<Point>(10)
            repeat(10) { i ->
                points.add(
                    Point(
                        x = index * .5f + i * .125f,
                        y = index * .25f + i,
                        p = .35f + (i % 5) * .1f,
                        t = index * 10L + i,
                        w = 0f,
                        tilt = (i % 3) * .1f,
                    )
                )
            }
            page.marks.add(Mark("pen", Color.rgb(30, 40, 50), 2f, points, inkVersion = 5))
        }
        store.commit(book) // whole-book baseline: 2000 marks / 20k points

        // Warm up JIT/allocation paths, then measure the UI-side shallow copy.
        repeat(4) { book.snapshot() }
        val snapshotSamples = LongArray(30) {
            val start = System.nanoTime()
            book.snapshot()
            System.nanoTime() - start
        }
        val sorted = snapshotSamples.sorted()
        val snapshotMedianMs = sorted[sorted.size / 2] / 1_000_000.0
        val snapshotMaxMs = sorted.last() / 1_000_000.0
        val snapshotP95Ms = sorted[(sorted.size * 95) / 100] / 1_000_000.0
        assertTrue("snapshot median ${snapshotMedianMs}ms", snapshotMedianMs < 5.0)

        // Synchronous compatibility path still serializes exactly once.
        page.marks.add(singleMark(9000))
        val beforeSync = store.stateSerializations
        store.commit(book)
        assertEquals(1, store.stateSerializations - beforeSync)

        // Warm FutureTask/executor/JIT once so the measured UI submissions are steady-state.
        page.marks.add(singleMark(9050))
        val warmDone = CountDownLatch(1)
        val warmStart = System.nanoTime()
        store.commitAsync(book, commitExecutor) { warmDone.countDown() }
        assertTrue(warmDone.await(60, TimeUnit.SECONDS))
        store.flush()
        val warmupMs = (System.nanoTime() - warmStart) / 1_000_000.0

        val baseMarks = page.marks.size
        val baseCursor = book.cursor
        val commits = 4
        val done = CountDownLatch(commits)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val submitNanos = LongArray(commits)
        val beforeAsync = store.stateSerializations
        val asyncStart = System.nanoTime()
        repeat(commits) { index ->
            page.marks.add(singleMark(9100 + index))
            val submitStart = System.nanoTime()
            store.commitAsync(book, commitExecutor) { error ->
                if (error != null) errors.add(error)
                done.countDown()
            }
            submitNanos[index] = System.nanoTime() - submitStart
        }
        assertTrue(done.await(120, TimeUnit.SECONDS))
        store.flush()
        val asyncMs = (System.nanoTime() - asyncStart) / 1_000_000.0
        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals(commits, (store.stateSerializations - beforeAsync).toInt())

        val saved = store.book(book.id)
        assertEquals(baseMarks + commits, saved.pages[0].marks.size)
        assertEquals(baseCursor + commits, saved.cursor)
        assertEquals(saved.cursor, saved.revision)

        val beforeUndo = store.stateSerializations
        assertTrue(store.undo(book, false))
        assertEquals("undo must reuse the history reference", beforeUndo, store.stateSerializations)
        assertEquals(baseCursor + commits - 1, book.cursor)
        assertEquals(baseMarks + commits - 1, store.book(book.id).pages[0].marks.size)

        val submitSorted = submitNanos.sorted()
        record(
            "snapshotAndCommit",
            JSONObject()
                .put("marks", 2_000)
                .put("points", 20_000)
                .put("snapshotSamples", 30)
                .put("snapshotMedianMs", round(snapshotMedianMs))
                .put("snapshotP95Ms", round(snapshotP95Ms))
                .put("snapshotMaxMs", round(snapshotMaxMs))
                .put("snapshotBudgetMs", 5)
                .put("asyncCommits", commits)
                .put("commitWarmupMs", round(warmupMs))
                .put("asyncTotalMs", round(asyncMs))
                .put("uiSubmitMedianMs", round(submitSorted[submitSorted.size / 2] / 1_000_000.0))
                .put("uiSubmitMaxMs", round(submitSorted.last() / 1_000_000.0))
                .put(
                    "stateSerializationsDelta",
                    (store.stateSerializations - beforeAsync).toInt(),
                )
                .put("marksAfterCommit", saved.pages[0].marks.size)
                .put("cursorAfterCommit", saved.cursor)
                .put("undoCursor", book.cursor)
                .put("marksAfterUndo", store.book(book.id).pages[0].marks.size)
                .put("undoSerializations", store.stateSerializations - beforeUndo),
        )
    }

    private fun newRoot(name: String): File =
        File(context.cacheDir, "ink-ledger-$name-${System.nanoTime()}").apply {
            mkdirs()
            roots.add(this)
        }

    private fun randomMark(seed: Int, count: Int, tool: String = "fountain"): Mark {
        val random = Random(seed.toLong())
        val points = ArrayList<Point>(count)
        var x = 80f + random.nextFloat() * 100f
        var y = 120f + random.nextFloat() * 100f
        var time = 1_000L + seed
        repeat(count) { index ->
            x += (random.nextFloat() - .5f) * 4f
            y += (random.nextFloat() - .5f) * 4f
            if (index % 37 == 0) {
                x = random.nextFloat() * 612f
                y = random.nextFloat() * 792f
            }
            time += 1 + random.nextInt(9)
            val pressure = random.nextInt(0, 65_536) / 65_535f
            val tilt = random.nextInt(0, 65_536) / 65_535f * 1.4f
            points.add(
                Point(
                    x = x,
                    y = y,
                    p = pressure,
                    t = time,
                    w = if (index % 53 == 0) 1.75f else 0f,
                    tilt = tilt,
                )
            )
        }
        return Mark(
            tool = tool,
            color = 0xff224466.toInt(),
            width = 2.75f,
            points = points,
            inkVersion = 5,
            refined = true,
        )
    }

    private fun singleMark(seed: Int): Mark {
        val points =
            MutableList(4) { i ->
                Point(
                    x = seed + i * .25f,
                    y = seed * .5f + i,
                    p = .4f + i * .01f,
                    t = seed * 10L + i,
                    w = 0f,
                    tilt = 0f,
                )
            }
        return Mark("pen", 0xff335577.toInt(), 2.25f, points, inkVersion = 5)
    }

    private fun assertPointsEquivalent(expected: Mark, actual: Mark) {
        assertEquals(expected.points.size, actual.points.size)
        expected.points.indices.forEach { index ->
            val a = expected.points[index]
            val b = actual.points[index]
            assertEquals("x[$index]", a.x.toRawBits(), b.x.toRawBits())
            assertEquals("y[$index]", a.y.toRawBits(), b.y.toRawBits())
            assertEquals("t[$index]", a.t, b.t)
            assertEquals("w[$index]", a.w.toRawBits(), b.w.toRawBits())
            assertEquals("p[$index]", a.p.toRawBits(), b.p.toRawBits())
            assertEquals("tilt[$index]", a.tilt.toRawBits(), b.tilt.toRawBits())
        }
    }

    private fun assertMarkEquivalent(expected: Mark, actual: Mark) {
        assertEquals(expected.tool, actual.tool)
        assertEquals(expected.color, actual.color)
        assertEquals(expected.width.toRawBits(), actual.width.toRawBits())
        assertEquals(expected.image, actual.image)
        assertEquals(expected.filled, actual.filled)
        assertEquals(expected.inkVersion, actual.inkVersion)
        assertEquals(expected.refined, actual.refined)
        assertEquals(expected.rotation.toRawBits(), actual.rotation.toRawBits())
        assertPointsEquivalent(expected, actual)
    }

    private fun round(value: Double): Double = Math.round(value * 1000.0) / 1000.0

    private fun record(test: String, data: JSONObject) {
        synchronized(EVIDENCE) { EVIDENCE.put(test, data) }
    }

    private companion object {
        val EVIDENCE = JSONObject()

        @JvmStatic
        @AfterClass
        fun writeEvidence() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.filesDir.mkdirs()
            File(context.filesDir, "ink-ledger-evidence.json").writeText(EVIDENCE.toString(2))
        }
    }
}

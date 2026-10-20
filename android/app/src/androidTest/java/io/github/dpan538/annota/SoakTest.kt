package io.github.dpan538.annota

import android.content.Intent
import android.graphics.Color
import android.os.*
import android.view.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Explicit emulator soak; use -e soakSeconds 600 for the recorded ten-minute run. */
@RunWith(AndroidJUnit4::class)
class SoakTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private var activity: MainActivity? = null

    private fun all(v: View): List<View> =
        listOf(v) +
            if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) }
            else emptyList()

    private fun <T> ui(f: () -> T): T {
        var result: T? = null
        inst.runOnMainSync { result = f() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitFor(f: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (!f()) {
            check(SystemClock.uptimeMillis() < deadline) { "Soak UI/render timed out" }
            SystemClock.sleep(50)
        }
    }

    private fun start(): Pair<MainActivity, ReaderView> {
        val a =
            inst.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
        activity = a
        waitFor {
            ui {
                all(a.window.decorView).any {
                    it.isClickable && it.contentDescription == "Attention Is All You Need"
                }
            }
        }
        ui {
            all(a.window.decorView)
                .first { it.isClickable && it.contentDescription == "Attention Is All You Need" }
                .performClick()
        }
        waitFor { ui { all(a.window.decorView).any { it is ReaderView } } }
        val view = ui { all(a.window.decorView).filterIsInstance<ReaderView>().single() }
        return a to view
    }

    @After
    fun close() {
        activity?.let { ui { it.finish() } }
        inst.waitForIdleSync()
    }

    @Test
    fun repeatedRenderWriteLookupTrimAndActivityRecreationKeepSavedInkAndBoundedCaches() {
        Assume.assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        context.deleteDatabase("library.sqlite")
        context.getSharedPreferences("settings", 0).edit().putBoolean("english", true).commit()
        val seconds =
            InstrumentationRegistry.getArguments()
                .getString("soakSeconds", "60")!!
                .toInt()
                .coerceIn(60, 3600)
        val started = SystemClock.elapsedRealtime()
        var (a, view) = start()
        val initialMarks = a.store.book("attention").pages.sumOf { it.marks.size }
        val samples = JSONArray()
        var cycles = 0
        var expectedState = a.store.book("attention").state()
        while (SystemClock.elapsedRealtime() - started < seconds * 1000L) {
            ui { view.next(1) }
            waitFor {
                ui {
                    view.memoryStats().pageBytes > 0 &&
                        view.memoryStats().wordBytes > 0 &&
                        view.memoryStats().queued == 0
                }
            }
            ui {
                val b = view.book
                b.pages[b.current]
                    .marks
                    .add(
                        Mark(
                            "fountain",
                            Color.BLUE,
                            2f,
                            MutableList(40) { i ->
                                Point(40f + i * 3, 80f + cycles % 100, .05f + i / 40f)
                            },
                        )
                    )
                a.store.commit(b)
                view.invalidate()
            }
            expectedState = a.store.book("attention").state()
            assertEquals(
                initialMarks + cycles + 1,
                a.store.book("attention").pages.sumOf { it.marks.size },
            )
            assertTrue(a.dictionary.query("attention").entries.isNotEmpty())
            assertTrue(
                a.store.libraryBooks().first { it.id == "attention" }.pages[0].marks.isEmpty()
            )
            val stats = ui { view.memoryStats() }
            assertTrue(
                stats.pageBytes <= stats.pageLimit &&
                    stats.imageBytes <= stats.imageLimit &&
                    stats.wordBytes <= stats.wordLimit &&
                    stats.queued <= 4
            )
            if (cycles % 5 == 0) {
                ui { a.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) }
                SystemClock.sleep(100)
                assertEquals(0, ui { view.memoryStats().pageBytes })
                ui { view.resumeMemory() }
                waitFor { ui { view.memoryStats().pageBytes > 0 } }
                assertEquals(expectedState, a.store.book("attention").state())
            }
            if (cycles % 3 == 0) {
                samples.put(
                    JSONObject()
                        .put("seconds", (SystemClock.elapsedRealtime() - started) / 1000)
                        .put("cycles", cycles + 1)
                        .put("pssKiB", Debug.getPss())
                        .put(
                            "javaUsedBytes",
                            Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
                        )
                        .put("pageCacheBytes", stats.pageBytes)
                        .put("pageCacheLimit", stats.pageLimit)
                        .put("wordCacheBytes", stats.wordBytes)
                )
            }
            if (cycles % 10 == 9) {
                ui { a.finish() }
                inst.waitForIdleSync()
                val fresh = start()
                a = fresh.first
                view = fresh.second
                assertEquals(expectedState, a.store.book("attention").state())
                inst.sendStatus(
                    0,
                    Bundle().apply {
                        putString(
                            "stream",
                            "\nSoak: ${cycles + 1} cycles, ${(SystemClock.elapsedRealtime() - started) / 1000}s, PSS ${Debug.getPss()} KiB\n",
                        )
                    },
                )
            }
            cycles++
            SystemClock.sleep(1500)
        }
        assertEquals(expectedState, a.store.book("attention").state())
        val result =
            JSONObject()
                .put("device", "API36 ARM64 emulator, debug/instrumented build")
                .put("elapsedSeconds", (SystemClock.elapsedRealtime() - started) / 1000)
                .put("cycles", cycles)
                .put("activityRecreations", cycles / 10)
                .put("addedStrokes", cycles)
                .put("samples", samples)
                .put("passed", true)
                .put("hardwareAcceptance", false)
        File(context.cacheDir, "english-soak.json").writeText(result.toString(2))
        inst.sendStatus(
            0,
            Bundle().apply {
                putString(
                    "stream",
                    "\nSoak passed: $cycles cycles. Report: cache/english-soak.json\n",
                )
            },
        )
    }
}

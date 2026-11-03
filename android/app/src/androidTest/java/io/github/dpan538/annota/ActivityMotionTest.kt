package io.github.dpan538.annota

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

/** Actual library-card / reader-back callbacks, with only the bundled public paper in captures. */
class ActivityMotionTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext

    private fun <T> ui(block: () -> T): T {
        var result: T? = null
        inst.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun all(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) }
            else emptyList()

    private fun await(block: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 25000
        while (!block()) {
            check(SystemClock.uptimeMillis() < deadline) { "Actual reader flow did not settle" }
            SystemClock.sleep(20)
        }
    }

    private data class Frame(
        val ms: Long,
        val bitmap: Bitmap,
        val offset: Float,
        val opaque: Boolean,
        val scale: Float = 1f,
        val horizontal: Float = 0f,
    )

    private fun frame(root: ViewGroup): Bitmap {
        val scale = minOf(1f, 640f / maxOf(root.width, root.height))
        return Bitmap.createBitmap(
                (root.width * scale).roundToInt(),
                (root.height * scale).roundToInt(),
                Bitmap.Config.ARGB_8888,
            )
            .also {
                val canvas = Canvas(it)
                canvas.scale(scale, scale)
                // Observe only the toolbar region used below. Repainting the entire PDF into a
                // software bitmap every vsync can itself consume the 180 ms transition budget.
                // Keep full-size coordinates so the measured actual view pixel stays unchanged.
                canvas.clipRect(0f, 0f, root.width.toFloat(), root.height * .16f)
                root.draw(canvas)
            }
    }

    private fun record(root: ViewGroup, action: () -> Unit): List<Frame> {
        val frames = mutableListOf<Frame>()
        val done = CountDownLatch(1)
        ui {
            frames += Frame(0, frame(root), 0f, true)
            action()
            val start = SystemClock.uptimeMillis()
            val choreographer = Choreographer.getInstance()
            choreographer.postFrameCallback(
                object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        val elapsed = SystemClock.uptimeMillis() - start
                        frames +=
                            Frame(
                                elapsed,
                                frame(root),
                                (root.getChildAt(0)?.translationY ?: 0f) /
                                    (20f * root.resources.displayMetrics.density),
                                (0 until root.childCount).all { root.getChildAt(it).alpha == 1f },
                                root.getChildAt(0)?.scaleX ?: 1f,
                                root.getChildAt(0)?.translationX ?: 0f,
                            )
                        // Entry may wait up to 800 ms for its first PDF frame, then animate.
                        // Observe the complete legal interval even under full-suite raster load.
                        if (elapsed >= 800 + UiMotion.DOCUMENT + 240) done.countDown()
                        else choreographer.postFrameCallback(this)
                    }
                }
            )
        }
        assertTrue("Actual view frames should complete", done.await(8, TimeUnit.SECONDS))
        return frames
    }

    private fun shell(command: String): String =
        inst.uiAutomation.executeShellCommand(command).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).bufferedReader().use { it.readText() }
        }

    private fun analyze(
        name: String,
        frames: List<Frame>,
        point: Pair<Float, Float>,
        animated: Boolean,
        output: File,
    ): JSONObject {
        try {
            val x = (point.first * frames.first().bitmap.width).toInt()
            val y = (point.second * frames.first().bitmap.height).toInt()
            val colors = frames.map { it.bitmap.getPixel(x, y) }
            val first = colors.first()
            val last = colors.last()
            fun components(color: Int) =
                intArrayOf(Color.red(color), Color.green(color), Color.blue(color))
            val from = components(first)
            val delta = components(last).mapIndexed { i, value -> value - from[i] }
            val length = delta.sumOf { it * it }.toFloat()
            assertTrue("Toolbar and library must visibly differ: $name", length > 5000f)
            val progress =
                colors.map { color ->
                    components(color)
                        .mapIndexed { i, value -> (value - from[i]) * delta[i] }
                        .sum() / length
                }
            val positions = frames.drop(1).map { abs(it.offset) }
            assertTrue("Screens remain fully opaque", frames.all { it.opaque })
            assertTrue("Never sweep the screen horizontally", frames.all { it.horizontal == 0f })
            assertTrue("Depth change stays subtle", frames.all { it.scale in .98f..1.02f })
            if (animated) {
                assertTrue(
                    "Mix depth with the short lift",
                    frames.any { abs(it.scale - 1f) > .001f },
                )
                assertTrue(
                    "$name needs intermediate positions: $positions",
                    positions.any { it in 0.03f..0.97f },
                )
                assertTrue(
                    "$name must move continuously to its destination: $positions",
                    positions.zipWithNext().all { (a, b) -> b <= a + .01f },
                )
            } else assertTrue(positions.all { it < .01f })
            assertTrue(positions.takeLast(3).all { it < .01f })
            val middle = frames.indices.minBy { abs(abs(frames[it].offset) - 0.5f) }
            for ((label, index) in
                listOf("before" to 0, "middle" to middle, "after" to frames.lastIndex)) {
                File(output, "$name-$label.png").outputStream().use {
                    frames[index].bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            return JSONObject()
                .put("animated", animated)
                .put("point", JSONArray(listOf(point.first, point.second)))
                .put("frameMs", JSONArray(frames.map { it.ms }))
                .put("normalizedVerticalLift", JSONArray(frames.map { it.offset }))
                .put("depthScales", JSONArray(frames.map { it.scale }))
                .put("intermediateFrames", positions.count { it in 0.03f..0.97f })
        } finally {
            frames.forEach { it.bitmap.recycle() }
        }
    }

    @Test
    fun libraryCardOpenAndReaderBackHaveRealFramesAndRespectDisabledAnimations() {
        Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        Assume.assumeTrue(UiMotion.enabled())
        val prefs = context.getSharedPreferences("settings", 0)
        val previousTheme = prefs.all["theme"]
        val previousEnglish = prefs.all["english"]
        val previousScale = shell("settings get global animator_duration_scale").trim()
        prefs.edit().putInt("theme", 2).putBoolean("english", true).commit()
        val activity =
            inst.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
        val root = ui {
            (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as ViewGroup
        }
        var fixture: Book? = null
        val output =
            File(context.getExternalFilesDir(null), "interaction-real-flow").apply { mkdirs() }
        val evidence = JSONObject().put("document", "Bundled Attention Is All You Need")
        try {
            await { ui { all(root).any { it is LibraryScreen && it.width > 0 } } }
            val source = activity.store.file("${uid()}.pdf")
            context.assets.open("attention-is-all-you-need.pdf").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val title = "Attention Is All You Need - Motion ${SystemClock.uptimeMillis()}"
            fixture = activity.pdf.import(source, title)
            fun button(label: String): View =
                all(root).first { it.isClickable && it.contentDescription == label }
            ui { button("Home").performClick() }
            await { ui { all(root).any { it.isClickable && it.contentDescription == "Search" } } }
            ui { button("Search").performClick() }
            await { ui { all(root).filterIsInstance<EditText>().size == 1 } }
            ui {
                all(root).filterIsInstance<EditText>().single().setText(title)
                root.clearFocus()
            }
            await {
                ui {
                    all(root).any { it.isShown && it.contentDescription == title && it.width > 0 }
                }
            }
            SystemClock.sleep(400)
            inst.waitForIdleSync()
            val opening = record(root) { assertTrue(button(title).performClick()) }
            val point = ui {
                val toolbar = all(root).first { it.tag == "reader-toolbar" }
                val trailing = all(root).first { it.tag == "reader-trailing-actions" }
                assertTrue(
                    "Trailing tools need an inset",
                    toolbar.width - trailing.right >= toolbar.width * .014f,
                )
                assertTrue(all(root).any { it is ReaderView })
                val at = IntArray(2).also(toolbar::getLocationOnScreen)
                val rootAt = IntArray(2).also(root::getLocationOnScreen)
                Pair(
                    (at[0] - rootAt[0] + toolbar.width * .74f) / root.width,
                    (at[1] - rootAt[1] + toolbar.height * .5f) / root.height,
                )
            }
            evidence.put("open", analyze("open", opening, point, true, output))
            await {
                ui { all(root).filterIsInstance<ReaderView>().single().detailStats().resident > 0 }
            }
            val r = ui { all(root).filterIsInstance<ReaderView>().single() }
            fun toggleSidebar() {
                activity.javaClass
                    .getDeclaredMethod("pages")
                    .apply { isAccessible = true }
                    .invoke(activity)
            }
            fun sidebar(open: Boolean): JSONArray {
                val sizes = mutableListOf<Int>()
                val done = CountDownLatch(1)
                ui {
                    toggleSidebar()
                    val start = SystemClock.uptimeMillis()
                    val choreographer = Choreographer.getInstance()
                    choreographer.postFrameCallback(
                        object : Choreographer.FrameCallback {
                            override fun doFrame(time: Long) {
                                sizes += r.width
                                assertEquals(1f, r.alpha, 0f)
                                assertEquals(0f, r.translationX, 0f)
                                assertSame(r, all(root).filterIsInstance<ReaderView>().single())
                                if (SystemClock.uptimeMillis() - start > 650) done.countDown()
                                else choreographer.postFrameCallback(this)
                            }
                        }
                    )
                }
                assertTrue(done.await(5, TimeUnit.SECONDS))
                assertTrue("Actual width must animate: $sizes", sizes.distinct().size >= 3)
                assertTrue(
                    "Width must be monotonic: $sizes",
                    sizes.zipWithNext().all { (a, b) -> if (open) b <= a else b >= a },
                )
                return JSONArray(sizes)
            }
            evidence.put("sidebarOpenWidths", sidebar(true))
            evidence.put("sidebarCloseWidths", sidebar(false))
            ui { repeat(4) { toggleSidebar() } }
            SystemClock.sleep(350)
            assertEquals(ui { (r.parent as View).width }, ui { r.width })
            val closing = record(root) { assertTrue(button("Library").performClick()) }
            assertTrue(
                ui { all(root).any { it is LibraryScreen } && all(root).none { it is ReaderView } }
            )
            evidence.put("close", analyze("close", closing, point, true, output))

            // Start and reverse the real screen replacement before the first animation frame.
            repeat(3) {
                ui {
                    assertTrue(button(title).performClick())
                    assertTrue(button("Library").performClick())
                }
            }
            SystemClock.sleep(400)
            assertTrue(
                ui { all(root).any { it is LibraryScreen } && all(root).none { it is ReaderView } }
            )
            evidence.put("rapidOpenReturnCycles", 3)

            shell("settings put global animator_duration_scale 0")
            await { !UiMotion.enabled() }
            evidence.put(
                "openDisabled",
                analyze(
                    "open-disabled",
                    record(root) { button(title).performClick() },
                    point,
                    false,
                    output,
                ),
            )
            evidence.put(
                "closeDisabled",
                analyze(
                    "close-disabled",
                    record(root) { button("Library").performClick() },
                    point,
                    false,
                    output,
                ),
            )
            evidence.put("passed", true).put("physicalXiaomiEvidence", false)
            File(output, "activity-motion.json").writeText(evidence.toString(2))
        } finally {
            shell(
                if (previousScale == "null") "settings delete global animator_duration_scale"
                else "settings put global animator_duration_scale $previousScale"
            )
            fixture?.let {
                it.trash = true
                activity.store.metadata(it)
            }
            ui { activity.finish() }
            inst.waitForIdleSync()
            prefs
                .edit()
                .also {
                    if (previousTheme is Int) it.putInt("theme", previousTheme)
                    else it.remove("theme")
                    if (previousEnglish is Boolean) it.putBoolean("english", previousEnglish)
                    else it.remove("english")
                }
                .commit()
        }
    }
}

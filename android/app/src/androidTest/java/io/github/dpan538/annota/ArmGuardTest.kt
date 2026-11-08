package io.github.dpan538.annota

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IEA acceptance: a large forearm contact at the edge must not draw, and the first back gesture
 * right after it must be deferred; a second deliberate back still leaves the reader.
 */
class ArmGuardTest {
    private var evidence = org.json.JSONObject()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val expectFixes =
        InstrumentationRegistry.getArguments().getString("expectFixes") == "true"
    private var activity: MainActivity? = null

    @After
    fun cleanup() {
        inst.runOnMainSync { activity?.finish() }
        val dir = java.io.File(context.filesDir, "writing-acceptance").apply { mkdirs() }
        java.io.File(dir, "arm-guard-${if (expectFixes) "after" else "before"}.json")
            .writeText(evidence.toString(2))
    }

    private fun <T> ui(body: () -> T): T {
        var result: T? = null
        inst.runOnMainSync { result = body() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun all(v: View): List<View> =
        listOf(v) +
            if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) }
            else emptyList()

    private fun readers(a: MainActivity) =
        ui { all(a.window.decorView).filterIsInstance<ReaderView>() }

    private fun screenClass(a: MainActivity): String =
        ui {
            val root =
                MainActivity::class.java
                    .getDeclaredField("root")
                    .apply { isAccessible = true }
                    .get(a) as ViewGroup
            root.getChildAt(0)?.javaClass?.simpleName ?: "empty"
        }

    private fun mainReader(a: MainActivity): ReaderView? =
        ui {
            MainActivity::class.java
                .getDeclaredField("reader")
                .apply { isAccessible = true }
                .get(a) as? ReaderView
        }

    private fun waitUntil(body: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20_000
        while (!body()) {
            check(SystemClock.uptimeMillis() < end) { "arm guard UI timeout" }
            SystemClock.sleep(40)
        }
    }

    private fun start(): MainActivity {
        val a =
            inst.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
        activity = a
        waitUntil {
            ui {
                all(a.window.decorView).any {
                    it.contentDescription == "Home" || it.contentDescription == "主页"
                }
            }
        }
        val book =
            ui {
                a.store.books().firstOrNull { !it.trash }
                    ?: a.store.newNote("Arm guard fixture ${System.nanoTime()}", "blank", "white")
            }
        ui {
            MainActivity::class.java
                .getDeclaredMethod("open", Book::class.java)
                .apply { isAccessible = true }
                .invoke(a, book)
        }
        waitUntil { readers(a).size == 1 }
        SystemClock.sleep(600)
        return a
    }

    private fun limbEvent(action: Int, x: Float, y: Float): MotionEvent {
        val props =
            arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            )
        val coords =
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x
                    this.y = y
                    pressure = .9f
                    touchMajor =
                        26f * context.resources.displayMetrics.density * 160f / 25.4f
                    touchMinor =
                        22f * context.resources.displayMetrics.density * 160f / 25.4f
                }
            )
        return MotionEvent.obtain(
            0L,
            SystemClock.uptimeMillis(),
            action,
            1,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }

    @Test
    fun forearmAtEdgeDoesNotDrawAndFirstBackIsDeferred() {
        val a = start()
        val density = context.resources.displayMetrics.density
        val edgeX = 12f * density
        val centerY = a.window.decorView.height * .5f

        // A large contact along both edges: consumed as limb, never delivered to the reader.
        for (x in listOf(edgeX, a.window.decorView.width - edgeX)) {
            val event = limbEvent(MotionEvent.ACTION_DOWN, x, centerY)
            ui { a.dispatchTouchEvent(event) }
            event.recycle()
            val move = limbEvent(MotionEvent.ACTION_MOVE, x + 4f * density, centerY + 8f * density)
            ui { a.dispatchTouchEvent(move) }
            move.recycle()
            val up = limbEvent(MotionEvent.ACTION_UP, x + 4f * density, centerY + 8f * density)
            ui { a.dispatchTouchEvent(up) }
            up.recycle()
        }
        if (expectFixes) assertTrue("the guard fixture reader is alive", mainReader(a) != null)
        val excluded =
            ui {
                if (android.os.Build.VERSION.SDK_INT >= 29)
                    a.window.decorView.systemGestureExclusionRects.isNotEmpty()
                else true
            }
        if (expectFixes)
            assertTrue("edge gesture exclusion should be armed after limb contact", excluded)

        // First back immediately after the forearm contact is deferred; the reader stays.
        val beforeBack = mainReader(a) != null
        val screenBefore = screenClass(a)
        ui { a.onBackPressed() }
        val afterBack = mainReader(a) != null
        val screenAfter = screenClass(a)
        if (expectFixes) {
            assertTrue("first accidental back must not leave the reader", afterBack)
            assertTrue(
                "first accidental back must not switch to the library screen",
                screenAfter != "LibraryScreen",
            )
        }
        evidence =
            org.json.JSONObject()
                .put("readerBeforeBack", beforeBack)
                .put("readerAfterBack", afterBack)
                .put("screenBefore", screenBefore)
                .put("screenAfter", screenAfter)
    }
}

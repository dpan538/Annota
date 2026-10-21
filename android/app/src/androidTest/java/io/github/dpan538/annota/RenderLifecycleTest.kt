package io.github.dpan538.annota

import android.content.Intent
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RenderLifecycleTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private var activity: MainActivity? = null
    private var originalEnglish = false

    @Before
    fun language() {
        val settings = context.getSharedPreferences("settings", 0)
        originalEnglish = settings.getBoolean("english", false)
        settings.edit().putBoolean("english", false).commit()
    }

    private fun all(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) }
            else emptyList()

    private fun <T> ui(body: () -> T): T {
        var value: T? = null
        inst.runOnMainSync { value = body() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun waitUntil(condition: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + 15000
        while (!condition()) {
            check(android.os.SystemClock.uptimeMillis() < end) {
                "Timed out waiting for real UI/render state"
            }
            android.os.SystemClock.sleep(40)
        }
    }

    @After
    fun close() {
        activity?.let { ui { it.finish() } }
        inst.waitForIdleSync()
        context
            .getSharedPreferences("settings", 0)
            .edit()
            .putBoolean("english", originalEnglish)
            .commit()
    }

    @Test
    fun attachedReaderEvictsAndRebuildsWithoutLosingSavedDocumentOrAddingBottomChrome() {
        Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        context.deleteDatabase("library.sqlite")
        val a =
            inst.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
        activity = a
        waitUntil {
            ui { all(a.window.decorView).any { it.contentDescription == "主页" && it.isClickable } }
        }
        val book = a.store.book("attention")
        book.pages[0]
            .marks
            .add(
                Mark(
                    "pen",
                    Color.RED,
                    2f,
                    mutableListOf(Point(50f, 80f, .3f), Point(80f, 90f, .8f)),
                )
            )
        a.store.commit(book)
        ui {
            all(a.window.decorView)
                .first { it.contentDescription == book.title && it.isClickable }
                .performClick()
        }
        var reader: ReaderView? = null
        waitUntil {
            ui {
                reader = all(a.window.decorView).filterIsInstance<ReaderView>().firstOrNull()
                reader != null
            }
        }
        val view = reader!!
        waitUntil { ui { view.memoryStats().pageBytes > 0 && view.memoryStats().wordBytes > 0 } }
        ui { view.next(1) }
        waitUntil { ui { view.memoryStats().queued == 0 && view.memoryStats().pageBytes > 0 } }
        val saved = a.store.book(book.id).state()
        val current = a.store.book(book.id).current
        val before = ui { view.memoryStats() }
        assertTrue(before.pageBytes > 0 && before.pageBytes <= before.pageLimit)
        assertTrue(before.wordBytes <= before.wordLimit && before.queued <= 4)
        ui { a.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) }
        android.os.SystemClock.sleep(250)
        val empty = ui { view.memoryStats() }
        assertEquals(0, empty.pageBytes)
        assertEquals(0, empty.imageBytes)
        assertEquals(0, empty.wordBytes)
        assertEquals(0, empty.queued)
        inst.sendStatus(
            0,
            android.os.Bundle().apply {
                putString("stream", "\nCache before trim: $before\nCache after trim: $empty\n")
            },
        )
        assertEquals(saved, a.store.book(book.id).state())
        assertEquals(current, a.store.book(book.id).current)
        ui { view.resumeMemory() }
        waitUntil { ui { view.memoryStats().pageBytes > 0 } }
        waitUntil {
            ui {
                ViewCompat.getRootWindowInsets(view)
                    ?.isVisible(WindowInsetsCompat.Type.navigationBars()) == false
            }
        }
        assertEquals(saved, a.store.book(book.id).state())
    }
}

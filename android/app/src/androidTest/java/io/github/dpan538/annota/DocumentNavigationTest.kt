package io.github.dpan538.annota

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.*
import android.os.SystemClock
import android.view.*
import android.widget.*
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.action.*
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.*
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InterruptedIOException
import org.junit.*
import org.junit.Assert.*

/** Dedicated emulator only: actual searchable PDF, links/outlines, native handles and search UI. */
class DocumentNavigationTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private lateinit var store: Store
    private lateinit var pdf: PdfEngine
    private var activity: MainActivity? = null
    private lateinit var prefsBefore: Map<String, *>
    private val prefs
        get() = context.getSharedPreferences("settings", 0)

    private val out
        get() = File(context.filesDir, "document-navigation-capture").apply { mkdirs() }

    private fun <T> ui(body: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return body()
        var value: T? = null
        inst.runOnMainSync { value = body() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun all(v: View): List<View> =
        listOf(v) +
            if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) }
            else emptyList()

    private fun waitFor(body: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 25000
        while (!body()) {
            check(SystemClock.uptimeMillis() < end) { "Navigation did not settle" }
            SystemClock.sleep(50)
        }
        inst.waitForIdleSync()
    }

    @Before
    fun setup() {
        Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        prefsBefore = prefs.all.toMap()
        prefs
            .edit()
            .putBoolean("english", false)
            .putInt("theme", 2)
            .putInt("buttonSize", 68)
            .commit()
        context.deleteDatabase("library.sqlite")
        store = Store(context)
        pdf = PdfEngine(context, store)
    }

    @After
    fun cleanup() {
        ui { activity?.finish() }
        inst.waitForIdleSync()
        store.close()
        prefs
            .edit()
            .also { e ->
                for (key in listOf("english", "theme", "buttonSize")) when (
                    val value = prefsBefore[key]
                ) {
                    is Int -> e.putInt(key, value)
                    is Boolean -> e.putBoolean(key, value)
                    else -> e.remove(key)
                }
            }
            .commit()
    }

    private fun fixture(): Book {
        val source = store.file("${uid()}.pdf")
        PDDocument().use { d ->
            repeat(3) { i ->
                val p = PDPage(PDRectangle(612f, 792f))
                d.addPage(p)
                PDPageContentStream(d, p).use { cs ->
                    fun text(x: Float, y: Float, value: String, size: Float = 14f) {
                        cs.beginText()
                        cs.setFont(PDType1Font.TIMES_ROMAN, size)
                        cs.newLineAtOffset(x, y)
                        cs.showText(value)
                        cs.endText()
                    }
                    text(48f, 710f, "Attention page ${i+1}", 20f)
                    repeat(5) { n ->
                        text(48f, 650f - n * 25, "Left attention studies sequence models.")
                        text(335f, 650f - n * 25, "Right column describes a separate topic.")
                    }
                    text(48f, 300f, "Jump to results on page three.")
                }
            }
            val root = PDDocumentOutline()
            d.documentCatalog.documentOutline = root
            val intro =
                PDOutlineItem().apply {
                    title = "Introduction"
                    destination =
                        PDPageXYZDestination().apply {
                            page = d.getPage(0)
                            top = 710
                        }
                }
            val result =
                PDOutlineItem().apply {
                    title = "Results"
                    destination =
                        PDPageXYZDestination().apply {
                            page = d.getPage(2)
                            top = 650
                        }
                }
            root.addLast(intro)
            intro.addLast(result)
            val dest =
                PDPageXYZDestination().apply {
                    page = d.getPage(2)
                    top = 650
                }
            d.getPage(0)
                .annotations
                .add(
                    PDAnnotationLink().apply {
                        rectangle = PDRectangle(48f, 295f, 220f, 20f)
                        action = PDActionGoTo().apply { destination = dest }
                    }
                )
            d.getPage(0)
                .annotations
                .add(
                    PDAnnotationLink().apply {
                        rectangle = PDRectangle(400f, 295f, 80f, 20f)
                        action = PDActionURI().apply { uri = "https://example.invalid/" }
                    }
                )
            d.save(source)
        }
        return pdf.import(source, "Navigation acceptance PDF")
    }

    private fun start(b: Book): ReaderView {
        val a =
            inst.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
        activity = a
        waitFor { ui { all(a.window.decorView).any { it is LibraryScreen } } }
        ui { a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        waitFor { ui { a.window.decorView.width > a.window.decorView.height } }
        open(b)
        return reader()
    }

    private fun open(b: Book) {
        ui {
            MainActivity::class
                .java
                .getDeclaredMethod("open", Book::class.java)
                .apply { isAccessible = true }
                .invoke(activity, activity!!.store.book(b.id))
        }
        waitFor { ui { all(activity!!.window.decorView).any { it is ReaderView && it.width > 0 } } }
        SystemClock.sleep(300)
    }

    private fun reader() = ui {
        all(activity!!.window.decorView).filterIsInstance<ReaderView>().single()
    }

    private fun popup(): PopupWindow? =
        MainActivity::class
            .java
            .getDeclaredField("readerPopup")
            .apply { isAccessible = true }
            .get(activity) as? PopupWindow

    private fun views() = ui {
        all(activity!!.window.decorView) +
            popup()?.takeIf { it.isShowing }?.let { all(it.contentView) }.orEmpty()
    }

    private fun control(label: String) =
        views().firstOrNull { it.isClickable && it.contentDescription == label }
            ?: error(
                "Missing $label: " + views().filter { it.isClickable }.map { it.contentDescription }
            )

    private fun tapAt(x: Float, y: Float, hold: Long = 35) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e =
                MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
            assertTrue(inst.uiAutomation.injectInputEvent(e, true))
            e.recycle()
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(hold)
        }
        inst.waitForIdleSync()
    }

    private fun tap(label: String) {
        val p = ui {
            val v = control(label)
            IntArray(2).also {
                v.getLocationOnScreen(it)
                it[0] += v.width / 2
                it[1] += v.height / 2
            }
        }
        tapAt(p[0].toFloat(), p[1].toFloat())
    }

    private fun capture(name: String) {
        inst.waitForIdleSync()
        SystemClock.sleep(160)
        val b = inst.uiAutomation.takeScreenshot()!!
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
    }

    private fun sourcePoint(r: ReaderView, page: Int, x: Float, y: Float): PointF = ui {
        val p = r.book.pages[page]
        val bounds = r.pageBounds(page)!!
        val screen = IntArray(2).also(r::getLocationOnScreen)
        PointF(
            screen[0] + bounds.left + x / p.w * bounds.width(),
            screen[1] + bounds.top + y / p.h * bounds.height(),
        )
    }

    private fun w(text: String, x: Float, y: Float) =
        Word(text, RectF(x, y, x + text.length * 5, y + 10))

    @Test
    fun columnSelectionNeverIncludesTheNeighborColumn() {
        val ws =
            (0..3).flatMap { n ->
                listOf(
                    w("left$n", 20f, n * 20f),
                    w("text$n", 65f, n * 20f),
                    w("right$n", 220f, n * 20f),
                    w("other$n", 270f, n * 20f),
                )
            }
        val layout = PageText(ws)
        val last = layout.nearest(ws[0], 300f, 65f)!!
        val chosen = layout.range(ws[0], last)
        assertTrue(chosen.size >= 6)
        assertTrue(chosen.all { it.box.right < 200 })
        assertTrue(layout.flow(ws[2]).all { it.box.left > 200 })
    }

    @Test
    fun searchMatchesPhrasesAcrossLinesCaseUnicodeAndCjkWithoutCrossingColumns() {
        val words =
            listOf(
                w("Ａttention", 10f, 0f),
                w("models", 65f, 0f),
                w("work", 10f, 20f),
                w("well", 65f, 20f),
                w("wrong", 230f, 0f),
                w("topic", 230f, 20f),
            )
        val layout = PageText(words)
        assertEquals(1, layout.find("attention models work", "p").size)
        assertTrue(layout.find("models wrong", "p").isEmpty())
        val cjk = PageText(listOf(w("注", 0f, 0f), w("意", 5f, 0f), w("力", 10f, 0f)))
        assertEquals(1, cjk.find("注意力", "p").size)
    }

    @Test
    fun realPdfSearchUsesCurrentPageOrderStreamsResultsAndCancels() {
        val b = fixture()
        b.pages.add(0, Sheet())
        b.pages.reverse()
        store.commit(b)
        val originalSource = store.file(b.source).readBytes()
        val events = mutableListOf<SearchProgress>()
        pdf.search(b, "ATTENTION") { events += it }
        assertEquals(4, events.size)
        assertTrue(events.last().complete)
        assertEquals(18, events.last().hits.size)
        assertEquals(b.pages.first().id, events.last().hits.first().pageId)
        var count = 0
        try {
            pdf.search(b, "attention", { count > 0 }) { count++ }
            fail("Cancel between pages")
        } catch (_: InterruptedIOException) {}
        assertEquals(1, count)
        assertArrayEquals(originalSource, store.file(b.source).readBytes())
    }

    @Test
    fun outlinesLinksAndRotatedDestinationCoordinatesResolveWithoutExternalActions() {
        val b = fixture()
        val entries = pdf.outline(b)
        assertEquals(listOf("Introduction", "Results"), entries.map { it.title })
        assertEquals(listOf(0, 1), entries.map { it.depth })
        assertEquals(2, entries.last().target!!.source)
        val content = pdf.pageContent(b, b.pages[0])
        assertEquals(1, content.links.size)
        assertEquals(2, content.links[0].target.source)
        assertTrue(content.words.any { it.text == "Attention" })
        assertTrue(content.links[0].box.contains(100f, 490f))
        PDDocument().use { d ->
            val p = PDPage(PDRectangle(10f, 20f, 600f, 800f))
            p.rotation = 90
            d.addPage(p)
            val point = displayedPoint(p, 50f, 700f)
            assertEquals(680f, point.x, 0f)
            assertEquals(40f, point.y, 0f)
        }
    }

    @Test
    fun bookmarksSurviveReorderUndoCopyAndBackupWithoutTouchingInkHistory() {
        val b = store.newNote("Bookmark history", "grid", "black")
        b.pages.add(Sheet())
        store.commit(b)
        val marked = b.pages.last().id
        val cursor = b.cursor
        val state = b.state()
        store.toggleBookmark(b, marked)
        assertEquals(cursor, b.cursor)
        assertEquals(state, b.state())
        assertEquals(setOf(marked), store.bookmarks(store.book(b.id)))
        b.pages.reverse()
        store.commit(b)
        assertEquals(marked, store.bookmarks(b).single())
        store.undo(b, false)
        assertEquals(setOf(marked), store.bookmarks(b))
        store.copy(b)
        val copy = store.books().first { it.id != b.id }
        assertEquals(1, store.bookmarks(copy).size)
        assertTrue(store.bookmarks(copy).single() != marked)
        val zip = ByteArrayOutputStream()
        store.backup(zip) { false }
        val before = store.books().map { it.id }.toSet()
        store.restore(zip.toByteArray().inputStream()) { false }
        assertEquals(
            2,
            store.books().filter { it.id !in before }.count { store.bookmarks(it).size == 1 },
        )
    }

    @Test
    fun navigationReturnsExactViewportAndIgnoresRemovedTargets() {
        val b = fixture()
        val r = start(b)
        ui {
            r.zoom = 1.6f
            r.panY = -320f
            r.panX = 40f
            r.invalidate()
        }
        SystemClock.sleep(200)
        val before = ui { r.viewport()!! }
        ui {
            assertTrue(r.navigate(PdfTarget(2, PointF(80f, 200f))))
            assertTrue(r.returnFromNavigation())
        }
        val after = ui { r.viewport()!! }
        assertEquals(before.pageId, after.pageId)
        assertEquals(before.fraction, after.fraction, .001f)
        assertEquals(before.zoom, after.zoom, 0f)
        assertEquals(before.panRatio, after.panRatio, .001f)
        ui {
            r.book.pages.removeAll { it.source == 2 }
            r.refresh()
            assertFalse(r.navigate(PdfTarget(2)))
        }
    }

    @Test
    fun nativeSearchNextCloseRestoresPositionToolAndDropsStaleQueries() {
        val b = fixture()
        val r = start(b)
        ui {
            r.tool = "pencil"
            r.zoom = 1.4f
            r.panY = -180f
            r.invalidate()
        }
        SystemClock.sleep(200)
        val origin = ui { r.viewport()!! }
        val state = r.book.state()
        tap("更多")
        tap("文内搜索")
        val query = views().filterIsInstance<EditText>().first { it.tag == "document-search-query" }
        ui {
            query.setText("Right")
            query.setText("ATTENTION")
        }
        waitFor {
            ui {
                views().filterIsInstance<TextView>().any { it.text.toString().startsWith("1 / 18") }
            }
        }
        assertTrue(r.searching)
        assertEquals("pencil", r.tool)
        tap("下一个结果")
        capture("navigation-search")
        tap("关闭导航")
        waitFor { !r.searching }
        SystemClock.sleep(250)
        val after = ui { r.viewport()!! }
        assertSame(r, reader())
        assertEquals(origin.pageId, after.pageId)
        assertEquals(origin.fraction, after.fraction, .002f)
        assertEquals(origin.panRatio, after.panRatio, .002f)
        assertEquals(origin.zoom, after.zoom, 0f)
        assertEquals(state, r.book.state())
        assertEquals("pencil", r.tool)
    }

    @Test
    fun nativeOutlineBookmarkAndInternalLinkOfferReturn() {
        val b = fixture()
        val r = start(b)
        ui { r.reading = true }
        tap("缩略图与页面")
        tap("目录")
        waitFor { views().any { it.contentDescription == "Results" } }
        capture("navigation-outline")
        tapAtControlText("Results")
        waitFor { r.book.current == 2 }
        tap("添加书签")
        tap("书签")
        capture("navigation-bookmarks")
        assertTrue(store.bookmarks(store.book(b.id)).isNotEmpty())
        tap("关闭导航")
        tap("返回原处")
        SystemClock.sleep(250)
        ui {
            r.goToPage(0, false)
            r.fit()
        }
        SystemClock.sleep(1000)
        val point = sourcePoint(r, 0, 100f, 490f)
        tapAt(point.x, point.y)
        waitFor { r.book.current == 2 }
        capture("navigation-link-return")
        tap("返回原处")
        assertEquals(0, r.book.current)
    }

    private fun tapAtControlText(label: String) {
        val v = views().first { it.contentDescription == label }
        val p = ui {
            IntArray(2).also {
                v.getLocationOnScreen(it)
                it[0] += v.width / 2
                it[1] += v.height / 2
            }
        }
        tapAt(p[0].toFloat(), p[1].toFloat())
    }

    @Test
    fun nativeSelectionHandlesExtendAcrossLinesWithoutTakingRightColumn() {
        val b = fixture()
        val r = start(b)
        ui {
            r.reading = true
            r.fit()
        }
        SystemClock.sleep(1000)
        val words = pdf.words(b, b.pages[0])
        val word = words.first { it.text == "Left" }
        val point = sourcePoint(r, 0, word.box.centerX(), word.box.centerY())
        tapAt(point.x, point.y, ViewConfiguration.getLongPressTimeout() + 200L)
        waitFor { ui { r.selectionHandles().size == 2 } }
        ui {
            val menu = all(popup()!!.contentView)
            assertEquals(
                listOf("复制", "查词"),
                menu.filter { it.isClickable }.map { it.contentDescription.toString() },
            )
            assertTrue(menu.none { it is ReaderGlyph })
        }
        val end = ui {
            val p = r.selectionHandles()[1]
            val loc = IntArray(2).also(r::getLocationOnScreen)
            PointF(p.x + loc[0], p.y + loc[1])
        }
        val last = words.filter { it.text == "models." }.sortedBy { it.box.top }[2]
        val to = sourcePoint(r, 0, last.box.centerX(), last.box.centerY())
        val down = SystemClock.uptimeMillis()
        for ((action, p) in
            listOf(
                MotionEvent.ACTION_DOWN to end,
                MotionEvent.ACTION_MOVE to to,
                MotionEvent.ACTION_UP to to,
            )) {
            val e =
                MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, p.x, p.y, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
            assertTrue(inst.uiAutomation.injectInputEvent(e, true))
            e.recycle()
            SystemClock.sleep(100)
        }
        assertTrue(ui { r.selectedText().contains("Left attention studies sequence models.") })
        assertFalse(ui { r.selectedText().contains("Right") })
        capture("navigation-selection-handles")
        assertTrue(r.book.pages.all { it.marks.isEmpty() })
    }

    @Test
    fun nativeNavigationFitsAllSizesOrientationsAndActualKeyboard() {
        val b = fixture()
        val r = start(b)
        for (portrait in listOf(false, true)) for (size in UiSizing.choices) {
            prefs.edit().putInt("buttonSize", size).commit()
            ui {
                activity!!.requestedOrientation =
                    if (portrait) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            waitFor {
                ui {
                    (activity!!.window.decorView.width < activity!!.window.decorView.height) ==
                        portrait
                }
            }
            open(b)
            tap("缩略图与页面")
            tap("书签")
            if (views().any { it.contentDescription == "移除书签" }) tap("移除书签")
            tap("添加书签")
            val panel = views().first { it.tag == "document-navigation" }
            waitFor { ui { all(panel).all { it.scaleX == 1f && it.scaleY == 1f } } }
            ui {
                all(panel)
                    .filter { it.isClickable && it.isShown }
                    .forEach { v ->
                        val clipped = Rect()
                        assertTrue(v.getGlobalVisibleRect(clipped))
                        assertEquals(v.width, clipped.width())
                        assertEquals(v.height, clipped.height())
                    }
            }
            capture("navigation-$size-${if(portrait)"portrait" else "landscape"}")
            tap("文内搜索")
            waitFor {
                ui {
                    androidx.core.view.ViewCompat.getRootWindowInsets(panel)
                        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                }
            }
            val q = views().filterIsInstance<EditText>().first { it.tag == "document-search-query" }
            ui { q.setText("attention") }
            waitFor {
                views().filterIsInstance<TextView>().any { it.text.toString().startsWith("1 / 18") }
            }
            if (size == 76)
                capture("navigation-keyboard-${if(portrait)"portrait" else "landscape"}")
            tap("关闭导航")
        }
        prefs.edit().putBoolean("english", true).commit()
        open(b)
        tap("Pages")
        tap("Bookmarks")
        capture("navigation-english")
        tap("Close navigation")
    }

    @Test
    fun rotatedNativeSelectionUsesTheCorrectPageCoordinates() {
        val b = fixture()
        b.pages[0].rotation = 90
        store.commit(b)
        val r = start(b)
        ui {
            r.reading = true
            r.fit()
        }
        SystemClock.sleep(1200)
        val word = pdf.words(b, b.pages[0]).first { it.text == "Left" }
        val point = ui {
            val bounds = r.pageBounds(0)!!
            val loc = IntArray(2).also(r::getLocationOnScreen)
            PointF(
                loc[0] + bounds.right - word.box.centerY() / b.pages[0].h * bounds.width(),
                loc[1] + bounds.top + word.box.centerX() / b.pages[0].w * bounds.height(),
            )
        }
        tapAt(point.x, point.y, ViewConfiguration.getLongPressTimeout() + 180L)
        waitFor { ui { r.selectedText() == "Left" } }
        assertEquals(2, ui { r.selectionHandles().size })
        capture("navigation-rotated-selection")
        val handles = ui { r.selectionHandles() }
        assertTrue(handles.all { it.x >= 0 && it.x <= r.width && it.y >= 0 && it.y <= r.height })
    }

    @Test
    fun searchRotationPreservesQueryResultIndexAndTemporaryReadOnlyState() {
        val b = fixture()
        val r = start(b)
        ui { r.tool = "fountain" }
        tap("更多")
        tap("文内搜索")
        ui {
            views()
                .filterIsInstance<EditText>()
                .first { it.tag == "document-search-query" }
                .setText("attention")
        }
        waitFor {
            views().filterIsInstance<TextView>().any { it.text.toString().startsWith("1 / 18") }
        }
        tap("下一个结果")
        ui {
            r.tool = "eraser"
            assertEquals("fountain", r.tool)
            MainActivity::class
                .java
                .getDeclaredMethod("addPage")
                .apply { isAccessible = true }
                .invoke(activity)
            assertEquals(3, r.book.pages.size)
            activity!!.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        waitFor { ui { activity!!.window.decorView.width < activity!!.window.decorView.height } }
        waitFor {
            views().filterIsInstance<TextView>().any { it.text.toString().startsWith("2 / 18") }
        }
        assertEquals(
            "attention",
            ui {
                views()
                    .filterIsInstance<EditText>()
                    .first { it.tag == "document-search-query" }
                    .text
                    .toString()
            },
        )
        assertTrue(reader().searching)
        capture("navigation-search-rotated")
        tap("关闭导航")
        assertFalse(reader().searching)
        assertEquals("fountain", reader().tool)
    }

    @Test
    fun attentionPaperDemonstratesSearchSelectionLookupAndBookmarks() {
        val source = store.file("${uid()}.pdf")
        context.assets.open("attention-is-all-you-need.pdf").use { input ->
            source.outputStream().use(input::copyTo)
        }
        val b = pdf.import(source, "Attention Is All You Need")
        val original = source.readBytes()
        val r = start(b)
        ui {
            r.reading = true
            r.readingStyle = ReadingStyle.SOFT
            r.fit()
        }
        SystemClock.sleep(1500)
        capture("navigation-paper-reading")
        tap("更多")
        tap("文内搜索")
        ui {
            views()
                .filterIsInstance<EditText>()
                .first { it.tag == "document-search-query" }
                .setText("attention")
        }
        waitFor {
            views().filterIsInstance<TextView>().any {
                it.text.toString().matches(Regex("1 / [0-9]+"))
            }
        }
        tap("下一个结果")
        capture("navigation-paper-search")
        tap("关闭导航")
        assertTrue(r.reading)
        ui { r.fit() }
        SystemClock.sleep(1200)
        val word = pdf.words(b, b.pages[0]).first { it.text.equals("Attention", true) }
        val point = sourcePoint(r, 0, word.box.centerX(), word.box.centerY())
        tapAt(point.x, point.y, ViewConfiguration.getLongPressTimeout() + 200L)
        waitFor { ui { r.selectedText().equals("attention", true) && popup()?.isShowing == true } }
        capture("navigation-paper-selection")
        tap("复制")
        ui {
            val clipboard =
                context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            assertEquals(word.text, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        tapAt(point.x, point.y, ViewConfiguration.getLongPressTimeout() + 200L)
        waitFor { ui { popup()?.isShowing == true } }
        tap("查词")
        waitFor { views().filterIsInstance<TextView>().any { it.text.toString().contains("注意力") } }
        capture("navigation-paper-dictionary")
        ui { popup()?.dismiss() }
        ui {
            r.goToPage(2, false)
            r.fit()
        }
        SystemClock.sleep(1200)
        tap("缩略图与页面")
        tap("书签")
        tap("添加书签")
        capture("navigation-paper-bookmark")
        tap("页面")
        capture("navigation-paper-pages")
        tap("关闭导航")
        assertTrue(store.bookmarks(b).isNotEmpty())
        assertTrue(b.pages.all { it.marks.isEmpty() })
        assertArrayEquals(original, source.readBytes())
    }

    @Test
    fun bundledChinesePdfSearchAndSelectionJoinRealGlyphs() {
        val source = store.file("${uid()}.pdf")
        context.assets.open("guide.pdf").use { input -> source.outputStream().use(input::copyTo) }
        val b = pdf.import(source, "Chinese glyph fixture")
        val words = pdf.words(b, b.pages[0])
        val run =
            words.windowed(3).first { group ->
                group.all { it.text.all { ch -> ch in '\u3400'..'\u9fff' } } &&
                    group.maxOf { it.box.top } - group.minOf { it.box.top } <
                        group.first().box.height()
            }
        val query = run.joinToString("") { it.text }
        assertEquals(query, PageText.join(run))
        var result: SearchProgress? = null
        pdf.search(b, query) { result = it }
        assertTrue(result!!.hits.isNotEmpty())
        assertEquals(query, LookupContext.fromWords(words, run)!!.selected)
    }

    @Test
    fun broadSearchStopsAtBoundedResultCountAndHandlesLineHyphenation() {
        val source = store.file("${uid()}.pdf")
        PDDocument().use { doc ->
            repeat(2) {
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    repeat(100) { n ->
                        cs.beginText()
                        cs.setFont(PDType1Font.HELVETICA, 2f)
                        cs.newLineAtOffset(20f, 750f - n * 5)
                        cs.showText(List(12) { "needle" }.joinToString(" "))
                        cs.endText()
                    }
                }
            }
            doc.save(source)
        }
        val b = pdf.import(source, "Bounded matches")
        val events = mutableListOf<SearchProgress>()
        pdf.search(b, "needle") { events += it }
        assertEquals(1, events.size)
        assertTrue(events.last().limited)
        assertTrue(events.last().complete)
        assertEquals(1000, events.last().hits.size)
        val layout = PageText(listOf(w("atten-", 0f, 0f), w("tion", 0f, 20f)))
        assertEquals(1, layout.find("attention", "p").size)
    }
}

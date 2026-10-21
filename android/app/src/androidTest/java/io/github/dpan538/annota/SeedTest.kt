package io.github.dpan538.annota

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Destructive fixtures: emulator-only; never run against a user's library. */
@RunWith(AndroidJUnit4::class)
class SeedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: Store

    @Before
    fun setup() {
        Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        finishReadersBeforeDatabaseReset()
        context.deleteDatabase("library.sqlite")
        store = Store(context)
    }

    @After
    fun cleanup() {
        store.close()
    }

    @Test
    fun firstLaunchIncludesTwoRealPdfsAndNeverResurrectsDeletedContent() {
        store.seed()
        assertEquals(setOf("guide", "attention"), store.books().map { it.id }.toSet())
        assertEquals(5, store.book("guide").pages.size)
        assertEquals(11, store.book("attention").pages.size)
        val text =
            PdfEngine(context, store)
                .words(store.book("attention"), store.book("attention").pages[0])
        assertTrue(text.any { it.text.contains("Attention") })
        store.writableDatabase.delete("books", "id=?", arrayOf("guide"))
        val paper = store.book("attention").apply { trash = true }
        store.metadata(paper)
        store.close()
        store = Store(context)
        store.seed()
        assertEquals(listOf("attention"), store.books().map { it.id })
        assertTrue(store.book("attention").trash)
    }

    @Test
    fun previousInitializedInstallNeverGetsSampleDocumentsAddedByUpgrade() {
        store.writableDatabase.execSQL("INSERT INTO flags(key,value) VALUES('seed','1')")
        val existing = store.newNote("My notes", "lines", "white")
        existing.pages[0]
            .marks
            .add(Mark("pen", android.graphics.Color.BLACK, 2f, mutableListOf(Point(10f, 20f, .5f))))
        store.commit(existing)
        val oldState = store.book(existing.id).state()
        store.seed()
        assertEquals(setOf(existing.id), store.books().map { it.id }.toSet())
        assertEquals(oldState, store.book(existing.id).state())
        store.seed()
        assertEquals(listOf(existing.id), store.books().map { it.id })
    }

    @Test
    fun copyFailurePublishesNeitherDocumentAndRetryCreatesExactlyTwo() {
        val blocked = store.file("00000000-0000-0000-0000-000000000002.pdf")
        blocked.delete()
        check(blocked.mkdir())
        try {
            try {
                store.seed()
                fail("Blocked paper copy accepted")
            } catch (_: java.io.IOException) {}
            assertTrue(store.books().isEmpty())
            store.readableDatabase.rawQuery("SELECT COUNT(*) FROM flags", null).use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
        } finally {
            blocked.deleteRecursively()
        }
        store.seed()
        store.seed()
        assertEquals(2, store.books().size)
    }
}

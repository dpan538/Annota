package io.github.dpan538.annota

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.util.AtomicFile
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

fun uid(): String = UUID.randomUUID().toString()

fun sha256(file: File): String =
    file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val b = ByteArray(65536)
        while (true) {
            val n = input.read(b)
            if (n < 0) break
            digest.update(b, 0, n)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

fun atomicWrite(file: File, block: (FileOutputStream) -> Unit) {
    file.parentFile!!.mkdirs()
    val af = AtomicFile(file)
    val out = af.startWrite()
    try {
        block(out)
        af.finishWrite(out)
    } catch (t: Throwable) {
        af.failWrite(out)
        throw t
    }
}

data class Point(
    val x: Float,
    val y: Float,
    val p: Float = 1f,
    val t: Long = 0,
    val w: Float = 0f,
    val tilt: Float = 0f,
) {
    fun json() =
        JSONArray().put(x.toDouble()).put(y.toDouble()).put(p.toDouble()).also {
            if (t > 0 || w > 0f || tilt > 0f) it.put(t)
            if (w > 0f || tilt > 0f) it.put(w.toDouble())
            if (tilt > 0f) it.put(tilt.toDouble())
        }
}

data class Mark(
    val tool: String,
    val color: Int,
    val width: Float,
    val points: MutableList<Point> = mutableListOf(),
    val image: String = "",
    val filled: Boolean = false,
    val inkVersion: Int = 0,
    val refined: Boolean = true,
    val rotation: Float = 0f,
) {
    fun json() =
        JSONObject()
            .put("tool", tool)
            .put("color", color)
            .put("width", width.toDouble())
            .put("points", JSONArray().also { a -> points.forEach { a.put(it.json()) } })
            .put("image", image)
            .put("filled", filled)
            .also { if (rotation != 0f) it.put("rotation", rotation.toDouble()) }
            .also { if (inkVersion > 0) it.put("inkVersion", inkVersion).put("refined", refined) }

    companion object {
        fun from(j: JSONObject): Mark {
            val a = j.getJSONArray("points")
            return Mark(
                j.getString("tool"),
                j.getInt("color"),
                j.getDouble("width").toFloat(),
                MutableList(a.length()) { i ->
                    a.getJSONArray(i).let {
                        Point(
                            it.getDouble(0).toFloat(),
                            it.getDouble(1).toFloat(),
                            it.optDouble(2, 1.0).toFloat(),
                            it.optLong(3, 0),
                            it.optDouble(4, 0.0).toFloat(),
                            it.optDouble(5, 0.0).toFloat().let { v ->
                                if (v.isFinite()) v.coerceIn(0f, 1.4f) else 0f
                            },
                        )
                    }
                },
                j.optString("image"),
                j.optBoolean("filled"),
                j.optInt("inkVersion", 0),
                j.optBoolean("refined", true),
                j.optDouble("rotation", 0.0).toFloat().let { if (it.isFinite()) it % 360f else 0f },
            )
        }
    }
}

data class Sheet(
    val id: String = uid(),
    var source: Int = -1,
    var rotation: Int = 0,
    val w: Float = 612f,
    val h: Float = 792f,
    var style: String = "blank",
    var paper: String = "white",
    val guide: String = "",
    val marks: MutableList<Mark> = mutableListOf(),
) {
    fun json() =
        JSONObject()
            .put("id", id)
            .put("source", source)
            .put("rotation", rotation)
            .put("w", w.toDouble())
            .put("h", h.toDouble())
            .put("style", style)
            .put("paper", paper)
            .put("guide", guide)
            .put("marks", JSONArray().also { a -> marks.forEach { a.put(it.json()) } })

    companion object {
        fun from(j: JSONObject): Sheet =
            Sheet(
                j.getString("id"),
                j.optInt("source", -1),
                j.optInt("rotation"),
                j.getDouble("w").toFloat(),
                j.getDouble("h").toFloat(),
                j.optString("style", "blank"),
                j.optString("paper", "white"),
                j.optString("guide"),
                j.getJSONArray("marks").let { a ->
                    MutableList(a.length()) { Mark.from(a.getJSONObject(it)) }
                },
            )
    }
}

data class Book(
    val id: String,
    var title: String,
    val source: String,
    var folder: String = "",
    var favorite: Boolean = false,
    var trash: Boolean = false,
    var opened: Long = 0,
    var current: Int = 0,
    var revision: Long = 0,
    var cursor: Long = 0,
    val pages: MutableList<Sheet>,
    var pdfInfo: PdfInfo = PdfInfo(),
) {
    fun state() = JSONArray().also { a -> pages.forEach { a.put(it.json()) } }.toString()

    /**
     * UI-side shallow snapshot for an asynchronous commit.
     *
     * The pages list and every sheet's marks list are copied, but completed [Mark] objects are
     * shared: a finished stroke is never mutated again, and the in-progress stroke is not in
     * [Sheet.marks]. The caller can therefore keep drawing while a background thread serializes
     * this snapshot.
     */
    fun snapshot(): Book {
        val copied = ArrayList<Sheet>(pages.size)
        for (sheet in pages) copied.add(sheet.copy(marks = ArrayList(sheet.marks)))
        return copy(pages = copied)
    }
}

class Store(val context: Context) : SQLiteOpenHelper(context, "library.sqlite", null, 2) {
    val assetsDir = File(context.filesDir, "documents").apply { mkdirs() }
    private val statesDir = File(context.filesDir, "states").apply { mkdirs() }

    /**
     * Crash tail for completed strokes that are not yet covered by a whole-book snapshot. It is
     * intentionally independent of the JSON state files: the next [commitAsync] makes the tail
     * durable as a snapshot, after which a caller may [InkLedger.trimThrough] it.
     */
    val inkLedger = InkLedger(File(context.filesDir, "ink-ledger"))

    /**
     * Number of full-book `Book.state()` serializations made for persistence. A commit must add
     * exactly one; the counter is exposed internally so acceptance tests can prove that
     * bookkeeping columns do not trigger a second serialization.
     */
    internal var stateSerializations: Long = 0L
        private set

    private var pendingCommits = 0
    private var commitChain: Future<*>? = null

    private fun writeState(json: String): String {
        val hash =
            MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
        val name = "$hash.json.gz"
        val file = File(statesDir, name)
        if (!file.exists())
            atomicWrite(file) { out ->
                val gz = GZIPOutputStream(out)
                gz.write(json.toByteArray())
                gz.finish()
                gz.flush()
            }
        return name
    }

    private fun readState(reference: String): String {
        if (reference.startsWith("["))
            return reference // migrate early development builds on their next save
        require(reference.matches(Regex("[a-f0-9]{64}\\.json\\.gz"))) { "无效的笔迹快照" }
        return GZIPInputStream(File(statesDir, reference).inputStream()).bufferedReader().use {
            it.readText()
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.execSQL("PRAGMA synchronous=FULL")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT NOT NULL,source TEXT NOT NULL,folder TEXT NOT NULL,favorite INTEGER NOT NULL,trash INTEGER NOT NULL,opened INTEGER NOT NULL,current INTEGER NOT NULL,revision INTEGER NOT NULL,cursor INTEGER NOT NULL,state TEXT NOT NULL,pdfInfo TEXT NOT NULL DEFAULT '{}')"
        )
        db.execSQL("CREATE TABLE folders(id TEXT PRIMARY KEY,title TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE history(book TEXT NOT NULL,version INTEGER NOT NULL,state TEXT NOT NULL,PRIMARY KEY(book,version),FOREIGN KEY(book) REFERENCES books(id) ON DELETE CASCADE)"
        )
        db.execSQL("CREATE TABLE flags(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) db.execSQL("ALTER TABLE books ADD COLUMN pdfInfo TEXT NOT NULL DEFAULT '{}'")
    }

    /** Serializes the whole book once and counts it; callers must reuse the returned reference. */
    private fun persistableState(b: Book): String {
        stateSerializations++
        return writeState(b.state())
    }

    private fun values(b: Book, state: String) =
        ContentValues().apply {
            put("id", b.id)
            put("title", b.title)
            put("source", b.source)
            put("folder", b.folder)
            put("favorite", if (b.favorite) 1 else 0)
            put("trash", if (b.trash) 1 else 0)
            put("opened", b.opened)
            put("current", b.current)
            put("revision", b.revision)
            put("cursor", b.cursor)
            put("state", state)
            put("pdfInfo", b.pdfInfo.json().toString())
        }

    private fun decode(c: android.database.Cursor, preview: Boolean = false): Book {
        fun s(k: String): String {
            return c.getString(c.getColumnIndexOrThrow(k))
        }
        fun n(k: String): Long {
            return c.getLong(c.getColumnIndexOrThrow(k))
        }
        val sheets =
            if (preview) mutableListOf(firstSheet(s("state")))
            else {
                val a = JSONArray(readState(s("state")))
                MutableList(a.length()) { Sheet.from(a.getJSONObject(it)) }
            }
        return Book(
            s("id"),
            s("title"),
            s("source"),
            s("folder"),
            n("favorite") == 1L,
            n("trash") == 1L,
            n("opened"),
            n("current").toInt(),
            n("revision"),
            n("cursor"),
            sheets,
            PdfInfo.from(JSONObject(s("pdfInfo"))),
        )
    }

    /** Library summaries stream only first-page metadata, never all documents' ink. */
    private fun firstSheet(reference: String): Sheet {
        val input =
            if (reference.startsWith("[")) java.io.StringReader(reference)
            else {
                require(reference.matches(Regex("[a-f0-9]{64}\\.json\\.gz")))
                GZIPInputStream(File(statesDir, reference).inputStream()).reader()
            }
        return android.util.JsonReader(input).use { reader ->
            reader.beginArray()
            reader.beginObject()
            val data = Sheet().json()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name == "marks")
                    break // Last field: do not inflate even a large first-page stroke list.
                when (reader.peek()) {
                    android.util.JsonToken.STRING -> data.put(name, reader.nextString())
                    android.util.JsonToken.NUMBER -> data.put(name, reader.nextDouble())
                    else -> reader.skipValue()
                }
            }
            Sheet.from(data)
        }
    }

    @Synchronized
    fun widgetMetadata(id: String): Pair<String, Int>? =
        readableDatabase
            .rawQuery("SELECT title,current FROM books WHERE id=? AND trash=0", arrayOf(id))
            .use { if (it.moveToFirst()) it.getString(0) to it.getInt(1) else null }

    @Synchronized
    fun available(id: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM books WHERE id=? AND trash=0", arrayOf(id)).use {
            it.moveToFirst()
        }

    @Synchronized
    fun libraryBooks(): List<Book> =
        readableDatabase.rawQuery("SELECT * FROM books ORDER BY opened DESC,title", null).use { c ->
            buildList { while (c.moveToNext()) add(decode(c, preview = true)) }
        }

    @Synchronized
    fun books(): List<Book> =
        readableDatabase.rawQuery("SELECT * FROM books ORDER BY opened DESC,title", null).use { c ->
            buildList { while (c.moveToNext()) add(decode(c)) }
        }

    @Synchronized
    /**
     * Read a book with every acknowledged snapshot visible. Commits are asynchronous now, so a
     * read immediately after pen-up must wait for the queued snapshot; the old full-serialization
     * path made this implicit by blocking the UI thread.
     */
    fun book(id: String): Book {
        awaitPending()
        return readBook(id)
    }

    private fun readBook(id: String): Book =
        readableDatabase.rawQuery("SELECT * FROM books WHERE id=?", arrayOf(id)).use {
            check(it.moveToFirst())
            decode(it)
        }

    @Synchronized
    fun folders(): List<Pair<String, String>> =
        readableDatabase.rawQuery("SELECT id,title FROM folders ORDER BY title", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }

    @Synchronized
    fun folder(title: String) {
        writableDatabase.insertOrThrow(
            "folders",
            null,
            ContentValues().apply {
                put("id", uid())
                put("title", title.take(200))
            },
        )
    }

    @Synchronized
    fun removeFolder(id: String) {
        transaction {
            execSQL("UPDATE books SET folder='' WHERE folder=?", arrayOf(id))
            delete("folders", "id=?", arrayOf(id))
        }
    }

    private fun transaction(block: SQLiteDatabase.() -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun insert(b: Book) {
        val state = persistableState(b)
        transaction {
            insertOrThrow("books", null, values(b, state))
            insertOrThrow(
                "history",
                null,
                ContentValues().apply {
                    put("book", b.id)
                    put("version", 0)
                    put("state", state)
                },
            )
        }
    }

    /**
     * Update book metadata. Pass the already-written history reference from undo so undoing does
     * not serialize the whole book a second time.
     */
    @Synchronized
    fun metadata(b: Book, stateReference: String? = null) {
        val state = stateReference ?: persistableState(b)
        writableDatabase.update("books", values(b, state), "id=?", arrayOf(b.id))
    }

    /** Reading progress must not serialize all ink on every crossed page. */
    @Synchronized
    fun position(b: Book) {
        writableDatabase.update(
            "books",
            ContentValues().apply { put("current", b.current) },
            "id=?",
            arrayOf(b.id),
        )
    }

    /**
     * Synchronous compatibility commit. It shares the exact persistence path of [commitAsync] and
     * serializes the book only once.
     *
     * The snapshot is the transaction boundary: every ledger frame present when the watermark was
     * taken must already be reflected in [b] (normally via [recoverInk]). Those frames are trimmed
     * after the SQLite transaction succeeds; frames appended later survive for the next recovery.
     */
    @Synchronized
    fun commit(b: Book) {
        val ledgerThrough = ledgerWatermark(b)
        val previousCursor = b.cursor
        val previousRevision = b.revision
        val next = previousCursor + 1
        val state = persistableState(b)
        b.cursor = next
        b.revision = next
        try {
            persistCommit(b, state, next, previousCursor)
        } catch (t: Throwable) {
            b.cursor = previousCursor
            b.revision = previousRevision
            throw t
        }
        trimLedger(b.id, ledgerThrough)
        if (next % 20 == 0L) runCatching { cleanupStates() }
    }

    /**
     * UI side does only [Book.snapshot] plus cursor/revision reservation. The supplied executor
     * serializes the snapshot once, writes one content-addressed state file, and commits the
     * history/books rows in a SQLite transaction.
     *
     * Commits are chained in submission order even on a multi-threaded executor. [onDone] runs on
     * the executor thread after persistence and before [flush] can return, so a caller cannot close
     * SQLite underneath a live callback. Do not call [flush] from inside [onDone].
     *
     * The ledger watermark is captured before [Book.snapshot]. Therefore a frame appended while
     * the background commit is running is not trimmed and remains recoverable by the next
     * [recoverInk] call.
     */
    fun commitAsync(b: Book, executor: Executor, onDone: (Throwable?) -> Unit = {}) {
        val rejected =
            synchronized(this) {
                // Capture the tail boundary before the snapshot: frames appended while the
                // background commit runs are newer than the snapshot and must survive the trim.
                val ledgerThrough = ledgerWatermark(b)
                val snapshot = b.snapshot()
                val previousCursor = b.cursor
                val previousRevision = b.revision
                val next = previousCursor + 1
                b.cursor = next
                b.revision = next
                snapshot.cursor = next
                snapshot.revision = next
                val previous = commitChain
                val task =
                    FutureTask<Unit>(
                        Callable<Unit> {
                            runCatching { previous?.get() }
                            var failure: Throwable? = null
                            try {
                                persistSnapshot(snapshot, next, previousCursor, ledgerThrough)
                            } catch (t: Throwable) {
                                failure = t
                            }
                            try {
                                onDone(failure)
                            } catch (_: Throwable) {
                                // A broken callback must not poison the commit chain.
                            }
                            synchronized(this@Store) {
                                pendingCommits--
                                (this@Store as Object).notifyAll()
                            }
                            Unit
                        }
                    )
                commitChain = task
                pendingCommits++
                try {
                    executor.execute(task)
                    null
                } catch (t: Throwable) {
                    commitChain = previous
                    pendingCommits--
                    (this as Object).notifyAll()
                    b.cursor = previousCursor
                    b.revision = previousRevision
                    t
                }
            }
        if (rejected != null) runCatching { onDone(rejected) }
    }

    @Synchronized
    private fun persistSnapshot(
        snapshot: Book,
        next: Long,
        previousCursor: Long,
        ledgerThrough: Long,
    ) {
        val state = persistableState(snapshot)
        persistCommit(snapshot, state, next, previousCursor)
        trimLedger(snapshot.id, ledgerThrough)
        if (next % 20 == 0L) runCatching { cleanupStates() }
    }

    /** O(1) ledger lookup: the UI thread must not scan the log while reserving a commit. */
    private fun ledgerWatermark(b: Book): Long =
        runCatching { inkLedger.cachedPosition(b.id) - 1 }.getOrDefault(-1L)

    /**
     * The snapshot now owns every ledger frame through [through]. Frames written after the
     * snapshot boundary were captured by [ledgerWatermark] and remain for the next recovery.
     * Trimming is best effort: a snapshot is already durable even if the local rewrite fails.
     */
    private fun trimLedger(bookId: String, through: Long) {
        if (through >= 0) runCatching { inkLedger.trimThrough(bookId, through) }
    }

    @Synchronized
    private fun persistCommit(b: Book, state: String, next: Long, previousCursor: Long) {
        transaction {
            delete("history", "book=? AND version>?", arrayOf(b.id, previousCursor.toString()))
            insertOrThrow(
                "history",
                null,
                ContentValues().apply {
                    put("book", b.id)
                    put("version", next)
                    put("state", state)
                },
            )
            update("books", values(b, state), "id=?", arrayOf(b.id))
            // Keep a bounded durable undo history. The latest state remains in books.
            delete("history", "book=? AND version<?", arrayOf(b.id, (next - 50).toString()))
        }
    }

    /** Wait for every commit submitted before this call; `timeoutMs == 0` waits indefinitely. */
    @Synchronized
    fun awaitPending(timeoutMs: Long = 0L): Boolean {
        require(timeoutMs >= 0) { "timeout must not be negative" }
        if (pendingCommits == 0) return true
        if (timeoutMs == 0L) {
            while (pendingCommits > 0) (this as Object).wait()
            return true
        }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (pendingCommits > 0) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return false
            (this as Object).wait(remaining / 1_000_000, (remaining % 1_000_000).toInt())
        }
        return true
    }

    /** Required before undo/redo, export/backup, and close. */
    fun flush() {
        awaitPending()
    }

    /** Closing the database must not race a background snapshot. */
    override fun close() {
        flush()
        super.close()
    }

    @Synchronized
    fun undo(b: Book, redo: Boolean): Boolean {
        awaitPending() // never read history while an earlier snapshot is still in flight
        val target = b.cursor + if (redo) 1 else -1
        val state =
            readableDatabase
                .rawQuery(
                    "SELECT state FROM history WHERE book=? AND version=?",
                    arrayOf(b.id, target.toString()),
                )
                .use { if (it.moveToFirst()) it.getString(0) else null } ?: return false
        val a = JSONArray(readState(state))
        b.pages.clear()
        repeat(a.length()) { b.pages.add(Sheet.from(a.getJSONObject(it))) }
        b.cursor = target
        b.current = b.current.coerceIn(0, b.pages.lastIndex)
        metadata(b, state)
        // Undo intentionally discards the uncommitted tail; otherwise book() would replay it.
        runCatching { inkLedger.clear(b.id) }
        return true
    }

    /**
     * Merge the ledger tail onto [b]. Only pages touched by the log are replaced; pages already
     * present in the last snapshot keep their objects and marks.
     *
     * Call this once per freshly loaded [b], then [commit] or [commitAsync]. A successful commit
     * trims exactly the applied frames, so the same tail cannot be replayed onto the saved book.
     * Do not call it twice on the same in-memory book without reloading or committing in between.
     */
    @Synchronized
    fun recoverInk(b: Book): Boolean {
        val base = HashMap<Int, List<Mark>>(b.pages.size)
        b.pages.forEachIndexed { index, sheet -> base[index] = sheet.marks.toList() }
        val merged = inkLedger.replayInto(b.id, base) ?: return false
        if (merged.isEmpty()) return false
        var changed = false
        merged.forEach { (index, marks) ->
            val sheet = b.pages.getOrNull(index) ?: return@forEach
            val same =
                sheet.marks.size == marks.size &&
                    sheet.marks.indices.all { sheet.marks[it] === marks[it] }
            if (!same) {
                sheet.marks.clear()
                sheet.marks.addAll(marks)
                changed = true
            }
        }
        return changed
    }

    /** Resolve duplicate external shares without retaining the sender's URI. */
    @Synchronized
    fun externalBook(hash: String): Book? {
        val id =
            readableDatabase
                .rawQuery("SELECT value FROM flags WHERE key=?", arrayOf("external-pdf:$hash"))
                .use { if (it.moveToFirst()) it.getString(0) else null } ?: return null
        return runCatching { book(id) }.getOrNull()?.takeUnless { it.trash }
    }

    @Synchronized
    fun rememberExternal(hash: String, book: Book) {
        writableDatabase.insertWithOnConflict(
            "flags",
            null,
            ContentValues().apply {
                put("key", "external-pdf:$hash")
                put("value", book.id)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** Page IDs, not page numbers: reorder and undo cannot silently move a bookmark. */
    @Synchronized
    fun bookmarks(b: Book): Set<String> {
        val raw =
            readableDatabase
                .rawQuery("SELECT value FROM flags WHERE key=?", arrayOf("bookmarks:${b.id}"))
                .use { if (it.moveToFirst()) it.getString(0) else "[]" }
        val a = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        val ids = b.pages.map { it.id }.toSet()
        return (0 until a.length()).map { a.optString(it) }.filter { it in ids }.toSet()
    }

    @Synchronized
    fun setBookmarks(b: Book, ids: Set<String>) {
        val valid = b.pages.map { it.id }.filter { it in ids }
        writableDatabase.insertWithOnConflict(
            "flags",
            null,
            ContentValues().apply {
                put("key", "bookmarks:${b.id}")
                put("value", JSONArray(valid).toString())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun toggleBookmark(b: Book, pageId: String) {
        val saved = bookmarks(b).toMutableSet()
        if (!saved.remove(pageId)) saved.add(pageId)
        setBookmarks(b, saved)
    }

    @Synchronized
    fun copy(b: Book) {
        val pages =
            JSONArray(b.state()).let { a ->
                MutableList(a.length()) { Sheet.from(a.getJSONObject(it).put("id", uid())) }
            }
        val copy =
            Book(
                uid(),
                b.title +
                    if (context.getSharedPreferences("settings", 0).getBoolean("english", false))
                        " copy"
                    else " 副本",
                b.source,
                b.folder,
                b.favorite,
                false,
                System.currentTimeMillis(),
                b.current,
                pages = pages,
                pdfInfo = b.pdfInfo,
            )
        insert(copy)
        val saved = bookmarks(b)
        setBookmarks(
            copy,
            b.pages.indices.filter { b.pages[it].id in saved }.map { pages[it].id }.toSet(),
        )
    }

    fun newNote(title: String, style: String, paper: String, folder: String = ""): Book =
        Book(
                uid(),
                title,
                "",
                folder,
                opened = System.currentTimeMillis(),
                pages = mutableListOf(Sheet(style = style, paper = paper)),
            )
            .also { insert(it) }

    fun file(name: String): File {
        require(name.matches(Regex("[a-fA-F0-9-]+\\.(pdf|png|jpg|webp)")))
        return File(assetsDir, name)
    }

    fun copyInput(
        uri: Uri,
        suffix: String,
        cancelled: () -> Boolean = { false },
        progress: (Long) -> Unit = {},
    ): File {
        val target = file("${uid()}.$suffix")
        try {
            (context.contentResolver.openInputStream(uri) ?: throw IOException("无法读取文件，请重新分享 PDF"))
                .use { input ->
                    atomicWrite(target) { out ->
                        copyLimited(input, out, 512L * 1024 * 1024, cancelled, progress)
                    }
                }
            return target
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
    }

    fun copyLimited(
        input: InputStream,
        out: OutputStream,
        max: Long,
        cancelled: () -> Boolean = { false },
        progress: (Long) -> Unit = {},
    ) {
        var count = 0L
        val buf = ByteArray(65536)
        while (true) {
            if (cancelled()) throw InterruptedIOException("已取消")
            val n = input.read(buf)
            if (n < 0) break
            count += n
            require(count <= max) { "文件超过允许大小" }
            if (assetsDir.usableSpace < 16L * 1024 * 1024) throw IOException("本地空间不足，至少保留 16 MiB")
            out.write(buf, 0, n)
            progress(count)
        }
    }

    @Synchronized
    fun cleanupStates() {
        val references =
            readableDatabase
                .rawQuery("SELECT state FROM books UNION SELECT state FROM history", null)
                .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        statesDir.listFiles()?.filter { it.name !in references }?.forEach { it.delete() }
    }

    @Synchronized
    fun seed() {
        cleanupStates()
        fun seeded(key: String) =
            readableDatabase.rawQuery("SELECT value FROM flags WHERE key=?", arrayOf(key)).use {
                it.moveToFirst()
            }
        // Any earlier initialization marker is final, including guide-only builds.
        // Deleted seed documents must never be reintroduced by a later upgrade.
        if (seeded("seed") || seeded("seed-attention-v1") || seeded("library-initialized-v1"))
            return
        val manifest =
            JSONObject(context.assets.open("seed.json").bufferedReader().use { it.readText() })
        fun prepare(
            id: String,
            title: String,
            asset: String,
            filename: String,
            entry: String,
        ): Book {
            val metadata = manifest.getJSONObject(entry)
            val target = file(filename)
            val expected = metadata.getString("sha256")
            if (!target.exists() || sha256(target) != expected)
                atomicWrite(target) { out -> context.assets.open(asset).use { it.copyTo(out) } }
            check(sha256(target) == expected) { "初始文档校验失败: $title" }
            val pages =
                android.os.ParcelFileDescriptor.open(
                        target,
                        android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                    )
                    .use { fd ->
                        android.graphics.pdf.PdfRenderer(fd).use { renderer ->
                            check(renderer.pageCount == metadata.getInt("pages")) {
                                "初始文档页数不符: $title"
                            }
                            MutableList(renderer.pageCount) { i ->
                                renderer.openPage(i).use { page ->
                                    Sheet(
                                        source = i,
                                        w = page.width.toFloat(),
                                        h = page.height.toFloat(),
                                    )
                                }
                            }
                        }
                    }
            return Book(id, title, target.name, opened = System.currentTimeMillis(), pages = pages)
        }
        // Prepare and validate every resource before publishing any library rows or flags.
        // If copying fails, a retry reuses valid files; it cannot leave a half-seeded library.
        val pending = mutableListOf<Pair<String, Book>>()
        pending +=
            "seed" to
                prepare(
                    "guide",
                    "Annota 使用指南",
                    "guide.pdf",
                    "00000000-0000-0000-0000-000000000001.pdf",
                    "guide",
                )
        pending +=
            "seed-attention-v1" to
                prepare(
                    "attention",
                    "Attention Is All You Need",
                    "attention-is-all-you-need.pdf",
                    "00000000-0000-0000-0000-000000000002.pdf",
                    "attention",
                )
        transaction {
            insertOrThrow(
                "flags",
                null,
                ContentValues().apply {
                    put("key", "library-initialized-v1")
                    put("value", "1")
                },
            )
            pending.forEach { (marker, book) ->
                val state = persistableState(book)
                insertOrThrow("books", null, values(book, state))
                insertOrThrow(
                    "history",
                    null,
                    ContentValues().apply {
                        put("book", book.id)
                        put("version", 0)
                        put("state", state)
                    },
                )
                insertOrThrow(
                    "flags",
                    null,
                    ContentValues().apply {
                        put("key", marker)
                        put("value", "1")
                    },
                )
            }
        }
    }

    @Synchronized
    fun backup(out: OutputStream, cancelled: () -> Boolean) {
        awaitPending() // export must include every acknowledged stroke
        val bs = books()
        // A crash can leave strokes in the ledger that are newer than the newest snapshot.
        bs.forEach { runCatching { recoverInk(it) } }
        val manifest =
            JSONObject()
                .put("format", "annota-backup-v1")
                .put(
                    "books",
                    JSONArray().also { a ->
                        bs.forEach { b ->
                            a.put(
                                JSONObject()
                                    .put("id", b.id)
                                    .put("title", b.title)
                                    .put("source", b.source)
                                    .put("pdfInfo", b.pdfInfo.json())
                                    .put("folder", b.folder)
                                    .put("favorite", b.favorite)
                                    .put("trash", b.trash)
                                    .put("opened", b.opened)
                                    .put("current", b.current)
                                    .put("pages", JSONArray(b.state()))
                                    .put("bookmarks", JSONArray(bookmarks(b).toList()))
                            )
                        }
                    },
                )
                .put(
                    "folders",
                    JSONArray().also { a ->
                        folders().forEach {
                            a.put(JSONObject().put("id", it.first).put("title", it.second))
                        }
                    },
                )
        val names =
            bs.flatMap { b ->
                    listOf(b.source, b.pdfInfo.viewingSource) +
                        b.pages.flatMap { p -> p.marks.map { it.image } }
                }
                .filter { it.isNotBlank() }
                .toSet()
        manifest.put(
            "assets",
            JSONObject().also { j -> names.forEach { j.put(it, sha256(file(it))) } },
        )
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write(manifest.toString().toByteArray())
            z.closeEntry()
            names.forEach { name ->
                if (cancelled()) throw InterruptedIOException("已取消")
                z.putNextEntry(ZipEntry("assets/$name"))
                file(name).inputStream().use { copyLimited(it, z, 512L * 1024 * 1024, cancelled) }
                z.closeEntry()
            }
        }
    }

    @Synchronized
    fun restore(input: InputStream, cancelled: () -> Boolean): Int {
        val staged = File(context.cacheDir, "restore-${uid()}").apply { mkdirs() }
        try {
            var total = 0L
            ZipInputStream(input).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    require(!e.isDirectory)
                    require(
                        e.name == "manifest.json" ||
                            e.name.matches(Regex("assets/[a-fA-F0-9-]+\\.(pdf|png|jpg|webp)"))
                    ) {
                        "备份路径无效"
                    }
                    val f = File(staged, e.name)
                    f.parentFile!!.mkdirs()
                    require(!f.exists())
                    f.outputStream().use { out ->
                        val buf = ByteArray(65536)
                        while (true) {
                            if (cancelled()) throw InterruptedIOException("已取消")
                            val n = z.read(buf)
                            if (n < 0) break
                            total += n
                            require(total <= 1024L * 1024 * 1024)
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
            val m = JSONObject(File(staged, "manifest.json").readText())
            require(m.getString("format") == "annota-backup-v1")
            val assets = m.getJSONObject("assets")
            val names = assets.keys().asSequence().toList()
            val remap =
                names.associateWith { old ->
                    require(old.matches(Regex("[a-fA-F0-9-]+\\.(pdf|png|jpg|webp)")))
                    val f = File(staged, "assets/$old")
                    require(sha256(f) == assets.getString(old)) { "备份校验失败" }
                    val n = "${uid()}.${old.substringAfterLast('.')}"
                    atomicWrite(file(n)) { out -> f.inputStream().use { it.copyTo(out) } }
                    n
                }
            val a = m.getJSONArray("books")
            val fs = m.getJSONArray("folders")
            val fm =
                (0 until fs.length()).associate { fs.getJSONObject(it).getString("id") to uid() }
            transaction {
                repeat(fs.length()) {
                    val f = fs.getJSONObject(it)
                    insertOrThrow(
                        "folders",
                        null,
                        ContentValues().apply {
                            put("id", fm[f.getString("id")])
                            put("title", f.getString("title"))
                        },
                    )
                }
                repeat(a.length()) {
                    val j = a.getJSONObject(it)
                    val ps = j.getJSONArray("pages")
                    require(ps.length() in 1..10000)
                    val pages =
                        MutableList(ps.length()) { i ->
                            val p =
                                Sheet.from(
                                    JSONObject(ps.getJSONObject(i).toString()).put("id", uid())
                                )
                            p.marks.forEach { mark ->
                                if (mark.image.isNotEmpty()) require(remap.containsKey(mark.image))
                            }
                            Sheet.from(
                                p.json().also { pj ->
                                    val marks = pj.getJSONArray("marks")
                                    repeat(marks.length()) { mi ->
                                        val mark = marks.getJSONObject(mi)
                                        if (mark.optString("image").isNotEmpty())
                                            mark.put("image", remap[mark.getString("image")])
                                    }
                                }
                            )
                        }
                    val b =
                        Book(
                            uid(),
                            j.getString("title"),
                            if (j.getString("source").isEmpty()) ""
                            else remap[j.getString("source")] ?: error("缺少 PDF"),
                            fm[j.optString("folder")] ?: "",
                            j.optBoolean("favorite"),
                            j.optBoolean("trash"),
                            j.optLong("opened"),
                            j.optInt("current").coerceIn(0, pages.lastIndex),
                            pages = pages,
                            pdfInfo =
                                PdfInfo.from(j.optJSONObject("pdfInfo")).let { info ->
                                    info.copy(
                                        viewingSource =
                                            if (info.viewingSource.isBlank()) ""
                                            else remap[info.viewingSource] ?: error("缺少 PDF 阅读副本")
                                    )
                                },
                        )
                    val bookmarked = j.optJSONArray("bookmarks") ?: JSONArray()
                    val oldIds =
                        (0 until bookmarked.length()).map { bookmarked.getString(it) }.toSet()
                    setBookmarks(
                        b,
                        (0 until ps.length())
                            .filter { ps.getJSONObject(it).getString("id") in oldIds }
                            .map { pages[it].id }
                            .toSet(),
                    )
                    val state = persistableState(b)
                    insertOrThrow("books", null, values(b, state))
                    insertOrThrow(
                        "history",
                        null,
                        ContentValues().apply {
                            put("book", b.id)
                            put("version", 0)
                            put("state", state)
                        },
                    )
                }
            }
            return a.length()
        } finally {
            staged.deleteRecursively()
        }
    }
}

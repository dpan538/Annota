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

data class Point(val x: Float, val y: Float, val p: Float = 1f) {
    fun json() = JSONArray().put(x.toDouble()).put(y.toDouble()).put(p.toDouble())
}

data class Mark(
    val tool: String,
    val color: Int,
    val width: Float,
    val points: MutableList<Point> = mutableListOf(),
    val image: String = "",
    val filled: Boolean = false,
) {
    fun json() =
        JSONObject()
            .put("tool", tool)
            .put("color", color)
            .put("width", width.toDouble())
            .put("points", JSONArray().also { a -> points.forEach { a.put(it.json()) } })
            .put("image", image)
            .put("filled", filled)

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
                        )
                    }
                },
                j.optString("image"),
                j.optBoolean("filled"),
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
}

class Store(val context: Context) : SQLiteOpenHelper(context, "library.sqlite", null, 2) {
    val assetsDir = File(context.filesDir, "documents").apply { mkdirs() }
    private val statesDir = File(context.filesDir, "states").apply { mkdirs() }

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

    private fun values(b: Book) =
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
            put("state", writeState(b.state()))
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
    fun book(id: String): Book =
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
        transaction {
            insertOrThrow("books", null, values(b))
            insertOrThrow(
                "history",
                null,
                ContentValues().apply {
                    put("book", b.id)
                    put("version", 0)
                    put("state", writeState(b.state()))
                },
            )
        }
    }

    @Synchronized
    fun metadata(b: Book) {
        writableDatabase.update("books", values(b), "id=?", arrayOf(b.id))
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

    @Synchronized
    fun commit(b: Book) {
        val state = writeState(b.state())
        val next = b.cursor + 1
        transaction {
            delete("history", "book=? AND version>?", arrayOf(b.id, b.cursor.toString()))
            insertOrThrow(
                "history",
                null,
                ContentValues().apply {
                    put("book", b.id)
                    put("version", next)
                    put("state", state)
                },
            )
            b.cursor = next
            b.revision = next
            update("books", values(b), "id=?", arrayOf(b.id))
            // Keep a bounded durable undo history. The latest state remains in books.
            delete("history", "book=? AND version<?", arrayOf(b.id, (next - 50).toString()))
        }
        if (next % 20 == 0L) cleanupStates()
    }

    @Synchronized
    fun undo(b: Book, redo: Boolean): Boolean {
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
        metadata(b)
        return true
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
                insertOrThrow("books", null, values(book))
                insertOrThrow(
                    "history",
                    null,
                    ContentValues().apply {
                        put("book", book.id)
                        put("version", 0)
                        put("state", writeState(book.state()))
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
        val bs = books()
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
                    insertOrThrow("books", null, values(b))
                    insertOrThrow(
                        "history",
                        null,
                        ContentValues().apply {
                            put("book", b.id)
                            put("version", 0)
                            put("state", writeState(b.state()))
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

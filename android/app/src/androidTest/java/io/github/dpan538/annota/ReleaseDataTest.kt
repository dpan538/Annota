package io.github.dpan538.annota

import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test

/**
 * Signed instrumentation can compare private data even after the installed APK is non-debuggable.
 */
class ReleaseDataTest {
    @Test
    fun snapshot() {
        Assume.assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        fun digest(file: File): String =
            file.inputStream().use { input ->
                val sha = MessageDigest.getInstance("SHA-256")
                val data = ByteArray(65536)
                while (true) {
                    val n = input.read(data)
                    if (n < 0) break
                    sha.update(data, 0, n)
                }
                sha.digest().joinToString("") { "%02x".format(it) }
            }
        val result = JSONObject()
        SQLiteDatabase.openDatabase(
                context.getDatabasePath("library.sqlite").path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            .use { db ->
                val books = JSONArray()
                db.rawQuery("SELECT * FROM books ORDER BY id", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val book = JSONObject()
                        cursor.columnNames.forEachIndexed { i, name ->
                            book.put(name, cursor.getString(i))
                        }
                        val state = book.getString("state")
                        if (!state.startsWith("["))
                            book.put("stateSha256", digest(File(context.filesDir, "states/$state")))
                        val source = book.getString("source")
                        if (source.isNotEmpty())
                            book.put(
                                "sourceSha256",
                                digest(File(context.filesDir, "documents/$source")),
                            )
                        books.put(book)
                    }
                }
                result.put("books", books)
                val flags = JSONArray()
                db.rawQuery("SELECT key,value FROM flags ORDER BY key", null).use { cursor ->
                    while (cursor.moveToNext()) flags.put(
                        JSONObject()
                            .put("key", cursor.getString(0))
                            .put("value", cursor.getString(1))
                    )
                }
                result.put("flags", flags)
            }
        val settings = context.getSharedPreferences("settings", 0)
        result.put(
            "settings",
            JSONObject()
                .put("english", settings.getBoolean("english", false))
                .put("theme", settings.getInt("theme", 0))
                .put("buttonSize", settings.getInt("buttonSize", 68)),
        )
        for (name in listOf("reading-viewports", "reading-display")) {
            val all = context.getSharedPreferences(name, 0).all
            result.put(
                name,
                JSONObject().apply { all.keys.sorted().forEach { key -> put(key, all[key]) } },
            )
        }
        result.put("pendingUpdateCleared", !settings.contains("pendingUpdate"))
        inst.sendStatus(0, Bundle().apply { putString("annotaSnapshot", result.toString()) })
    }
}

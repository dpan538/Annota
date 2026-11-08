package io.github.dpan538.annota

import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest

/**
 * Deterministic double-hashing Bloom filter shared with the offline dictionary build.
 *
 * Build-time contract (must stay byte-for-byte identical to the Kotlin side, otherwise a present
 * key would be reported as absent and the retrieval path would silently lose data):
 * - key bytes: UTF-8 of the key exactly as stored in `en-zh.sqlite` (`entries.key`,
 *   `entries.boundary_key`, `aliases.key`, `aliases.boundary_key`, `forms.key`) and the wiktionary
 *   lookup keys (`aliases.key`, `forms.form`, `source_forms.form`, `english_aliases.key`).
 *   Schema-5 assets keep entry keys/boundaries on `entries` and only author aliases in `aliases`;
 *   older assets lack `entries.boundary_key`, so that probe is simply skipped when absent.
 * - digest: `SHA-256(key bytes)`.
 * - `h1`: digest bytes `0..3` read as an unsigned big-endian 32-bit integer.
 * - `h2`: digest bytes `4..7` read as an unsigned big-endian 32-bit integer.
 * - bit index for `i in 0 until hashes`: `(h1 + i * h2) mod nbits`.
 * - `lookup_filter.bits` is a little-endian bit map: `bitIndex` lives in `bits[bitIndex ushr 3]` at
 *   mask `1 shl (bitIndex and 7)`.
 *
 * False positives are allowed (a candidate is then simply looked up and found absent), false
 * negatives are not. When the table is missing (dictionary assets older than schema 4) the filter
 * reports every key as possibly present, which keeps the old exact SQL path intact.
 */
class LookupFilter private constructor(private val parts: List<Part>) {
    private class Part(val bits: ByteArray, val nbits: Int, val hashes: Int) {
        fun mightContain(key: String): Boolean {
            val (h1, h2) = hashes(key)
            for (i in 0 until hashes) {
                val bit = ((h1 + i.toLong() * h2) % nbits).toInt()
                val byteIndex = bit ushr 3
                if (byteIndex !in bits.indices) return true
                if (bits[byteIndex].toInt() and (1 shl (bit and 7)) == 0) return false
            }
            return true
        }
    }

    /** True when at least one filter row was loaded; an empty filter allows everything. */
    val isAvailable: Boolean
        get() = parts.isNotEmpty()

    /** True when the key may be present. A false value is authoritative: the key is absent. */
    fun mightContain(key: String): Boolean = parts.isEmpty() || parts.any { it.mightContain(key) }

    companion object {
        private val digest = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

        private fun hashes(key: String): Pair<Long, Long> {
            val value = digest.get()!!
            value.reset()
            val bytes = value.digest(key.toByteArray(Charsets.UTF_8))
            val h1 =
                ((bytes[0].toLong() and 0xff) shl 24) or
                    ((bytes[1].toLong() and 0xff) shl 16) or
                    ((bytes[2].toLong() and 0xff) shl 8) or
                    (bytes[3].toLong() and 0xff)
            val h2 =
                ((bytes[4].toLong() and 0xff) shl 24) or
                    ((bytes[5].toLong() and 0xff) shl 16) or
                    ((bytes[6].toLong() and 0xff) shl 8) or
                    (bytes[7].toLong() and 0xff)
            return h1 to h2
        }

        /**
         * Reads every `lookup_filter` row. Multiple rows are combined with "any may contain", so a
         * key listed in any covered table is never short-circuited away. Returns null when the
         * table is absent, empty, malformed, or fails a consistency probe against the real key
         * columns; callers then treat all keys as possible.
         *
         * [secondary] optionally exposes the wiktionary database so its covered columns are probed
         * too. It is only opened after a bitmap has been read, keeping pre-upgrade assets on the
         * zero-cost path.
         */
        fun open(db: SQLiteDatabase, secondary: (() -> SQLiteDatabase?)? = null): LookupFilter? {
            val loaded =
                runCatching {
                        db.rawQuery("SELECT bits,nbits,hashes FROM lookup_filter", null).use {
                            cursor ->
                            buildList {
                                while (cursor.moveToNext()) {
                                    val bits = cursor.getBlob(0) ?: continue
                                    val nbits = cursor.getInt(1)
                                    val hashes = cursor.getInt(2)
                                    if (nbits <= 0 || hashes <= 0) continue
                                    if (bits.size.toLong() * 8L < nbits.toLong()) continue
                                    add(Part(bits, nbits, hashes))
                                }
                            }
                        }
                    }
                    .getOrNull()
                    .orEmpty()
            val filter = loaded.takeIf { it.isNotEmpty() }?.let(::LookupFilter) ?: return null
            // A truncated or tampered bitmap must degrade to the exact SQL path, never turn a
            // present key into a false negative. Probe keys that are guaranteed to exist.
            if (!filter.selfCheck(db)) return null
            return filter.takeIf { selfCheckSecondary(it, secondary?.invoke()) }
        }

        private fun selfCheckSecondary(filter: LookupFilter, db: SQLiteDatabase?): Boolean {
            if (db == null) return true
            val probes =
                listOf(
                    "SELECT key FROM aliases WHERE key IS NOT NULL AND key<>'' LIMIT 4",
                    "SELECT form FROM forms WHERE form IS NOT NULL AND form<>'' LIMIT 4",
                    "SELECT form FROM source_forms WHERE form IS NOT NULL AND form<>'' LIMIT 4",
                    "SELECT key FROM english_aliases WHERE key IS NOT NULL AND key<>'' LIMIT 4",
                )
            return probes.all { probe ->
                val keys =
                    runCatching {
                            db.rawQuery(probe, null).use { cursor ->
                                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                            }
                        }
                        .getOrNull()
                        .orEmpty()
                keys.all { filter.mightContain(it) }
            }
        }

        private fun LookupFilter.selfCheck(db: SQLiteDatabase): Boolean {
            // Schema-5 assets cover entries.key/entries.boundary_key/aliases.key/
            // aliases.boundary_key/forms.key. A probe on a table or column that does not exist
            // yields no keys, so pre-schema-5 assets degrade instead of rejecting the filter.
            val probes =
                listOf(
                    "SELECT key FROM entries WHERE key IS NOT NULL AND key<>'' LIMIT 4",
                    "SELECT boundary_key FROM entries WHERE boundary_key IS NOT NULL AND boundary_key<>'' LIMIT 4",
                    "SELECT key FROM aliases WHERE key IS NOT NULL AND key<>'' LIMIT 4",
                    "SELECT boundary_key FROM aliases WHERE boundary_key IS NOT NULL AND boundary_key<>'' LIMIT 4",
                    "SELECT key FROM forms WHERE key IS NOT NULL AND key<>'' LIMIT 4",
                )
            for (probe in probes) {
                val keys =
                    runCatching {
                            db.rawQuery(probe, null).use { cursor ->
                                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                            }
                        }
                        .getOrNull()
                        .orEmpty()
                for (key in keys) if (!mightContain(key)) return false
            }
            return true
        }
    }
}

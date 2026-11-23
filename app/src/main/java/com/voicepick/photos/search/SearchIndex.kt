package com.voicepick.photos.search

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device index of what each photo contains: one row per MediaStore image id with the
 * detected [Category] keys and the recognized text (lowercase, accent-free).
 * Rows exist for every processed photo (also ones without text) so they aren't processed again.
 *
 * AI edition: `embedding` holds the photo's TinyCLIP vector (float32, little-endian). NULL means
 * "not computed yet" (e.g. indexed by the standard edition); an empty blob means "tried and failed".
 */
class SearchIndex private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "search_index.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE photos (
                id INTEGER PRIMARY KEY,
                modified INTEGER NOT NULL,
                categories TEXT NOT NULL,
                text TEXT NOT NULL,
                embedding BLOB
            )""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 → v2: keep the recognized text, add the embedding column.
        if (oldVersion < 2) db.execSQL("ALTER TABLE photos ADD COLUMN embedding BLOB")
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS photos")
        onCreate(db)
    }

    /** What is stored for one photo. */
    data class Indexed(val modified: Long, val hasEmbedding: Boolean)

    /** id → what's indexed for it. */
    fun indexedState(): Map<Long, Indexed> {
        val out = HashMap<Long, Indexed>()
        readableDatabase.rawQuery("SELECT id, modified, embedding IS NOT NULL FROM photos", null).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = Indexed(c.getLong(1), c.getInt(2) != 0)
        }
        return out
    }

    /**
     * Stores a fully processed photo. [embedding] is only meaningful when [embeddingTried]:
     * null then records a failure, so the photo isn't retried on every run.
     */
    fun put(
        id: Long,
        modified: Long,
        categories: Set<Category>,
        text: String,
        embedding: FloatArray? = null,
        embeddingTried: Boolean = false,
    ) {
        val values = ContentValues().apply {
            put("id", id)
            put("modified", modified)
            // ",passport,document," so a category can be matched with LIKE '%,key,%'.
            put("categories", categories.joinToString(",", prefix = ",", postfix = ",") { it.key })
            put("text", text.take(MAX_TEXT))
            if (embeddingTried) put("embedding", toBlob(embedding)) else putNull("embedding")
        }
        writableDatabase.insertWithOnConflict("photos", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Adds the embedding to an already text-indexed photo (null = failed). */
    fun setEmbedding(id: Long, embedding: FloatArray?) {
        val values = ContentValues().apply { put("embedding", toBlob(embedding)) }
        writableDatabase.update("photos", values, "id = ?", arrayOf(id.toString()))
    }

    /** All usable embeddings, for visual search (~2 KB per photo). */
    fun loadEmbeddings(): List<Pair<Long, FloatArray>> {
        val out = ArrayList<Pair<Long, FloatArray>>()
        readableDatabase.rawQuery("SELECT id, embedding FROM photos WHERE length(embedding) > 0", null).use { c ->
            while (c.moveToNext()) out += c.getLong(0) to fromBlob(c.getBlob(1))
        }
        return out
    }

    /** Drops rows for photos that no longer exist. */
    fun retainOnly(existingIds: Set<Long>) {
        val stale = indexedState().keys - existingIds
        if (stale.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            stale.forEach { db.delete("photos", "id = ?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Deletes everything that was indexed. */
    fun clear() {
        writableDatabase.delete("photos", null, null)
    }

    /** Photos ready to search; with [withEmbedding], only those whose embedding was also processed. */
    fun count(withEmbedding: Boolean = false): Int {
        val sql = "SELECT COUNT(*) FROM photos" + if (withEmbedding) " WHERE embedding IS NOT NULL" else ""
        return readableDatabase.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** Ids of indexed photos matching [query]. */
    fun search(query: SearchQuery): Set<Long> {
        if (query.isEmpty) return emptySet()
        val clauses = ArrayList<String>()
        val args = ArrayList<String>()
        if (query.categories.isNotEmpty()) {
            clauses += query.categories.joinToString(" OR ", "(", ")") { "categories LIKE ?" }
            query.categories.forEach { args += "%,${it.key},%" }
        }
        query.terms.forEach { term ->
            clauses += "text LIKE ? ESCAPE '\\'"
            args += "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
        val out = HashSet<Long>()
        readableDatabase.rawQuery(
            "SELECT id FROM photos WHERE " + clauses.joinToString(" AND "),
            args.toTypedArray(),
        ).use { c -> while (c.moveToNext()) out += c.getLong(0) }
        return out
    }

    companion object {
        private const val MAX_TEXT = 4000

        fun toBlob(v: FloatArray?): ByteArray {
            if (v == null) return ByteArray(0)
            val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            v.forEach { buf.putFloat(it) }
            return buf.array()
        }

        fun fromBlob(b: ByteArray): FloatArray {
            val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(b.size / 4) { buf.getFloat() }
        }

        @Volatile private var instance: SearchIndex? = null

        fun get(context: Context): SearchIndex =
            instance ?: synchronized(this) { instance ?: SearchIndex(context).also { instance = it } }
    }
}

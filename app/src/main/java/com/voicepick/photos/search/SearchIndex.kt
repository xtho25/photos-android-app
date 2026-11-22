package com.voicepick.photos.search

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * On-device index of what each photo contains: one row per MediaStore image id with the
 * detected [Category] keys and the recognized text (lowercase, accent-free).
 * Rows exist for every processed photo (also ones without text) so they aren't processed again.
 */
class SearchIndex private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "search_index.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE photos (
                id INTEGER PRIMARY KEY,
                modified INTEGER NOT NULL,
                categories TEXT NOT NULL,
                text TEXT NOT NULL
            )""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS photos")
        onCreate(db)
    }

    /** id → DATE_MODIFIED of everything indexed. */
    fun indexedVersions(): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        readableDatabase.rawQuery("SELECT id, modified FROM photos", null).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = c.getLong(1)
        }
        return out
    }

    fun put(id: Long, modified: Long, categories: Set<Category>, text: String) {
        val values = ContentValues().apply {
            put("id", id)
            put("modified", modified)
            // ",passport,document," so a category can be matched with LIKE '%,key,%'.
            put("categories", categories.joinToString(",", prefix = ",", postfix = ",") { it.key })
            put("text", text.take(MAX_TEXT))
        }
        writableDatabase.insertWithOnConflict("photos", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Drops rows for photos that no longer exist. */
    fun retainOnly(existingIds: Set<Long>) {
        val stale = indexedVersions().keys - existingIds
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

    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM photos", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

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

        @Volatile private var instance: SearchIndex? = null

        fun get(context: Context): SearchIndex =
            instance ?: synchronized(this) { instance ?: SearchIndex(context).also { instance = it } }
    }
}

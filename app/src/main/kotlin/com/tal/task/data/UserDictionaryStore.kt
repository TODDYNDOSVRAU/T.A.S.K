package com.tal.task.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.tal.task.engine.Language
import com.tal.task.engine.TrieDictionary

/**
 * Private on-device SQLite dictionary store for locally learned words (D4 / D7).
 * Never writes when password/incognito flags are active.
 */
class UserDictionaryStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "task_user_dict.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE learned_words (
                word TEXT NOT NULL,
                lang TEXT NOT NULL,
                freq INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (word, lang)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun loadInto(dictionary: TrieDictionary) {
        val db = readableDatabase
        db.query(
            "learned_words",
            arrayOf("word", "freq"),
            "lang = ?",
            arrayOf(dictionary.language.code),
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val word = cursor.getString(0)
                val freq = cursor.getInt(1)
                dictionary.insertWord(word, freq, isUserWord = true)
            }
        }
    }

    fun recordWord(word: String, language: Language, incognito: Boolean) {
        if (incognito) return
        val trimmed = word.trim()
        if (trimmed.length < 2 || trimmed.length > 32) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val existingFreq = db.query(
            "learned_words",
            arrayOf("freq"),
            "word = ? AND lang = ?",
            arrayOf(trimmed, language.code),
            null,
            null,
            null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

        val cv = ContentValues().apply {
            put("word", trimmed)
            put("lang", language.code)
            put("freq", (existingFreq + 200).coerceAtMost(20_000))
            put("updated_at", now)
        }
        db.insertWithOnConflict("learned_words", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun countLearnedWords(): Int {
        val db = readableDatabase
        db.rawQuery("SELECT COUNT(*) FROM learned_words", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun clearAll() {
        writableDatabase.delete("learned_words", null, null)
    }
}

data class ClipboardEntry(
    val text: String,
    val pinned: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
)

object LocalClipboardStore {
    private const val MAX_ITEMS = 20
    private val entries = ArrayList<ClipboardEntry>()

    @Synchronized
    fun push(text: String, incognito: Boolean) {
        if (incognito) return
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > 512) return
        val existing = entries.indexOfFirst { it.text == trimmed }
        if (existing >= 0) {
            val wasPinned = entries[existing].pinned
            entries.removeAt(existing)
            entries.add(0, ClipboardEntry(trimmed, pinned = wasPinned))
        } else {
            entries.add(0, ClipboardEntry(trimmed))
        }
        while (entries.size > MAX_ITEMS) {
            val idx = entries.indexOfLast { !it.pinned }
            if (idx >= 0) entries.removeAt(idx) else break
        }
    }

    @Synchronized
    fun togglePin(index: Int) {
        if (index in entries.indices) {
            entries[index] = entries[index].copy(pinned = !entries[index].pinned)
        }
    }

    @Synchronized
    fun snapshot(): List<ClipboardEntry> = entries.toList()

    @Synchronized
    fun clearUnpinned() {
        entries.removeAll { !it.pinned }
    }

    @Synchronized
    fun clearAll() {
        entries.clear()
    }
}

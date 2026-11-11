package com.pedant.app

import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import java.text.Normalizer
import java.util.Locale

/**
 * A language's word dictionary, built from English Wiktionary (CC BY-SA 4.0) by
 * tools/build_dictionaries.py: spelling, part of speech, IPA, short English meanings, and the word
 * each form belongs to.
 */
class Dictionary(file: File) : Closeable {

    private val db = SQLiteDatabase.openDatabase(
        file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    )

    data class Entry(val word: String, val pos: String?, val ipa: String?, val meanings: List<String>, val formOf: String?)

    /** What the word bubble shows for a word. [ipa] is null when the dictionary has none. */
    data class Description(val meaning: String, val ipa: String?)

    fun entries(word: String): List<Entry> =
        db.rawQuery("SELECT word, pos, ipa, meanings, form_of FROM entries WHERE key = ?", arrayOf(key(word))).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Entry(c.getString(0), c.getString(1), c.getString(2), c.getString(3).split('\n'), c.getString(4)))
                }
            }
        }

    /**
     * The meanings of [word], one line per part of speech, followed by the meanings of the word it
     * is a form of ("vais" → "aller"). Null if the dictionary doesn't have it.
     */
    fun describe(word: String): Description? {
        val found = entries(word)
        if (found.isEmpty()) return null
        val lines = found.map { line(it) }.toMutableList()
        found.mapNotNull { it.formOf }.distinct().take(1).forEach { base ->
            entries(base).filter { it.formOf == null }.take(2).forEach { lines += "${it.word}: ${line(it)}" }
        }
        return Description(lines.distinct().joinToString("\n"), found.firstNotNullOfOrNull { it.ipa })
    }

    private fun line(e: Entry): String {
        val pos = e.pos?.let { POS_NAMES[it] ?: it }
        val meanings = e.meanings.joinToString("; ")
        return if (pos == null) meanings else "($pos) $meanings"
    }

    override fun close() = db.close()

    companion object {
        /** The lookup key; build_dictionaries.py normalizes words the same way. */
        fun key(word: String): String = Normalizer.normalize(word, Normalizer.Form.NFC).lowercase(Locale.ROOT)

        private val POS_NAMES = mapOf(
            "adj" to "adjective", "adv" to "adverb", "prep" to "preposition", "conj" to "conjunction",
            "pron" to "pronoun", "det" to "determiner", "intj" to "interjection", "num" to "number",
            "name" to "name", "particle" to "particle", "article" to "article",
        )

        /** Shown with dictionary meanings, as the license requires. */
        const val CREDIT = "From Wiktionary (CC BY-SA 4.0)"
    }
}

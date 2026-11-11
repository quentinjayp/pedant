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
     * The meanings of [word], meanings first. A form ("dessins") shows its base word's meanings,
     * with what form it is noted after:
     *
     *     drawing (act of drawing); design (pattern)
     *     noun · plural of dessin
     *
     * When the word is more than one part of speech, each gets its own line, labelled at the end.
     * Null if the dictionary doesn't have the word.
     */
    fun describe(word: String): Description? {
        val found = entries(word)
        if (found.isEmpty()) return null
        val senses = found.map { e ->
            val base = e.formOf?.let { baseEntries(it, e.pos) }.orEmpty()
            if (base.isEmpty()) Sense(e.pos, e.meanings, note = null)
            else Sense(e.pos, base.flatMap { it.meanings }, note = e.meanings.first())
        }
        // One line per part of speech.
        val groups = senses.groupBy { it.pos }.map { (pos, group) ->
            Sense(pos, group.flatMap { it.meanings }.distinct().take(MAX_MEANINGS), group.firstNotNullOfOrNull { it.note })
        }
        val text = if (groups.size == 1) {
            val g = groups[0]
            listOf(g.meanings.joinToString("; "), listOfNotNull(posName(g.pos), g.note).joinToString(" · "))
                .filter { it.isNotEmpty() }
                .joinToString("\n")
        } else {
            groups.joinToString("\n") { g ->
                val label = listOfNotNull(posName(g.pos), g.note).joinToString("; ")
                if (label.isEmpty()) g.meanings.joinToString("; ") else "${g.meanings.joinToString("; ")} ($label)"
            }
        }
        return Description(text, found.firstNotNullOfOrNull { it.ipa })
    }

    private data class Sense(val pos: String?, val meanings: List<String>, val note: String?)

    /** [base]'s own entries (not forms of something else), the same part of speech first. */
    private fun baseEntries(base: String, pos: String?): List<Entry> {
        val all = entries(base).filter { it.formOf == null }
        return all.filter { it.pos == pos }.ifEmpty { all }
    }

    private fun posName(pos: String?): String? = pos?.let { POS_NAMES[it] ?: it }

    override fun close() = db.close()

    companion object {
        /** The lookup key; build_dictionaries.py normalizes words the same way. */
        fun key(word: String): String = Normalizer.normalize(word, Normalizer.Form.NFC).lowercase(Locale.ROOT)

        private val POS_NAMES = mapOf(
            "adj" to "adjective", "adv" to "adverb", "prep" to "preposition", "conj" to "conjunction",
            "pron" to "pronoun", "det" to "determiner", "intj" to "interjection", "num" to "number",
            "name" to "name", "particle" to "particle", "article" to "article",
        )

        private const val MAX_MEANINGS = 4

        /** Shown with dictionary meanings, as the license requires. */
        const val CREDIT = "From Wiktionary (CC BY-SA 4.0)"
    }
}

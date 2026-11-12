package com.pedant.app

import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import java.text.Normalizer
import java.util.Locale

/**
 * A language's word dictionary, built from English Wiktionary (CC BY-SA 4.0) by
 * tools/build_dictionaries.py: spelling, part of speech, IPA, short English meanings, and the word
 * each form belongs to. A word's entries come most common part of speech first ("est", is, before
 * "est", east), from counts in Universal Dependencies treebanks.
 */
class Dictionary(file: File) : Closeable {

    private val db = SQLiteDatabase.openDatabase(
        file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    )

    /** [formMeanings]: a verb form's base meanings put in its form ("eu": had, not to have), if any. */
    data class Entry(
        val word: String,
        val pos: String?,
        val ipa: String?,
        val meanings: List<String>,
        val formOf: String?,
        val formMeanings: List<String>? = null,
    )

    /** What the word bubble shows for a word. [ipa] is null when the dictionary has none. */
    data class Description(val meaning: String, val ipa: String?)

    /** Dictionaries built before these columns were added do without them until updated. */
    private val columns = db.rawQuery("PRAGMA table_info(entries)", null).use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(1)) }
    }
    private val query = "SELECT word, pos, ipa, meanings, form_of, " +
        (if ("form_meanings" in columns) "form_meanings" else "NULL") +
        " FROM entries WHERE key = ? ORDER BY " + (if ("freq" in columns) "freq DESC, rowid" else "rowid")

    fun entries(word: String): List<Entry> =
        db.rawQuery(query, arrayOf(key(word))).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Entry(
                            c.getString(0), c.getString(1), c.getString(2), c.getString(3).split('\n'), c.getString(4),
                            c.getString(5)?.split('\n'),
                        ),
                    )
                }
            }
        }

    /**
     * [word]'s entries, spelled as it is ("eu", not the initialism "EU"); failing that, in lower case
     * (a capital at the start of a sentence); failing that, however they're spelled.
     */
    private fun matching(word: String): List<Entry> {
        val all = entries(word)
        val exact = spelling(word)
        return all.filter { spelling(it.word) == exact }
            .ifEmpty { all.filter { spelling(it.word) == exact.lowercase(Locale.ROOT) } }
            .ifEmpty { all }
    }

    private fun spelling(word: String) = Normalizer.normalize(word, Normalizer.Form.NFC).replace('’', '\'')

    /**
     * The meanings of [word], meanings first. A form ("dessins") shows its base word's meanings,
     * with what form it is noted after:
     *
     *     drawing (act of drawing); design (pattern)
     *     noun · plural of dessin
     *
     * When the word is more than one part of speech, each gets its own line, labelled at the end.
     *
     * An elided word the dictionary doesn't have whole, like "d'encouragement", shows the main word
     * with the elided part added after ("d': apocopic form of de: of"), and their IPA joined.
     * Null if the dictionary doesn't have the word.
     */
    fun describe(word: String): Description? {
        describeWhole(word)?.let { return it }
        val cut = word.indexOfFirst { it == '\'' || it == '’' }
        if (cut <= 0 || cut == word.lastIndex) return null
        val elided = word.substring(0, cut + 1)
        val main = describeWhole(word.substring(cut + 1)) ?: return null
        val part = entries(elided).firstOrNull() ?: return null
        val ipa = if (main.ipa != null && part.ipa != null) part.ipa.trimEnd('‿') + main.ipa else null
        return Description(main.meaning + "\n" + "${part.word}: ${part.meanings.first()}", ipa)
    }

    private fun describeWhole(word: String): Description? {
        val found = matching(word)
        if (found.isEmpty()) return null
        val senses = found.map { e ->
            val base = e.formOf?.let { baseEntries(it, e.pos) }.orEmpty()
            when {
                // A verb form: its base word's meanings in its form ("had"), then what form it is.
                e.formOf != null && e.formMeanings != null -> Sense(e.pos, e.formMeanings, note = e.meanings.first())
                base.isEmpty() -> Sense(e.pos, e.meanings, note = null)
                else -> Sense(e.pos, base.flatMap { it.meanings }, note = e.meanings.first())
            }
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
        fun key(word: String): String =
            Normalizer.normalize(word, Normalizer.Form.NFC).lowercase(Locale.ROOT).replace('’', '\'')

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

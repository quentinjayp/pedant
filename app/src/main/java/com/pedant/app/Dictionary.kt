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

    data class Entry(val word: String, val pos: String?, val ipa: String?, val meanings: List<String>, val formOf: String?)

    /**
     * One part of speech of a word: [label] is the part of speech and, for a form, what form it is
     * ("verb · past participle of avoir"); [meanings] are its meanings (for a form, its base word's).
     */
    data class Group(val label: String?, val meanings: List<String>)

    /** What the word bubble shows for a word. [ipa] is null when the dictionary has none. */
    data class Description(val groups: List<Group>, val ipa: String?) {
        /** The same as plain text, a line per label and per meaning. */
        val text: String get() = groups.joinToString("\n") { g -> (listOfNotNull(g.label) + g.meanings).joinToString("\n") }
    }

    /** Dictionaries built before the counts were added keep Wiktionary's order until updated. */
    private val order = db.rawQuery("PRAGMA table_info(entries)", null).use { c ->
        var hasFreq = false
        while (c.moveToNext()) if (c.getString(1) == "freq") hasFreq = true
        if (hasFreq) "freq DESC, rowid" else "rowid"
    }

    /**
     * [word]'s entries, spelled as it is ("eu", not the initialism "EU"). A word capitalised only at
     * the start ("Dobrý", maybe the start of a sentence) also takes its lower-case entries, the
     * most common first ("dobrý", good, before the surname). Failing that, however they're spelled.
     */
    private fun matching(word: String): List<Entry> {
        val all = entries(word)
        val exact = spelling(word)
        val lower = exact.lowercase(Locale.ROOT)
        val capitalised = exact != lower && exact.drop(1) == lower.drop(1)
        val wanted = if (capitalised) setOf(exact, lower) else setOf(exact)
        return all.filter { spelling(it.word) in wanted }
            .ifEmpty { all.filter { spelling(it.word) == lower } }
            .ifEmpty { all }
    }

    private fun spelling(word: String) = Normalizer.normalize(word, Normalizer.Form.NFC).replace('’', '\'')

    fun entries(word: String): List<Entry> =
        db.rawQuery(
            "SELECT word, pos, ipa, meanings, form_of FROM entries WHERE key = ? ORDER BY $order", arrayOf(key(word)),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Entry(c.getString(0), c.getString(1), c.getString(2), c.getString(3).split('\n'), c.getString(4)))
                }
            }
        }

    /**
     * The meanings of [word], a group per part of speech, the most common first. A form ("dessins",
     * "eu") shows its base word's meanings under what form it is:
     *
     *     verb · past participle of avoir
     *     to have
     *     to own
     *
     * A word the dictionary doesn't have whole is looked up in its parts, split at an apostrophe
     * ("d'encouragement": d' + encouragement) or hyphens ("allez-vous": allez + vous; "n'est-ce":
     * n' + est + ce), each part's groups labelled with the part, in order, and their IPA joined.
     * Words written with a hyphen that are one word ("peut-être", "guarda-chuva") have their own
     * entries, so they're never split. Null if no part is in the dictionary.
     */
    fun describe(word: String): Description? = describeWhole(word) ?: describeParts(word)

    private fun describeParts(word: String): Description? {
        val cut = word.indexOfFirst { it == '\'' || it == '’' }
        val parts = when {
            cut in 1 until word.lastIndex -> listOf(word.substring(0, cut + 1), word.substring(cut + 1))
            '-' in word.trim('-') -> word.split('-').filter { it.isNotEmpty() }
            else -> return null
        }
        val found = parts.map { part -> part to describe(part) }
        if (found.all { it.second == null }) return null
        val groups = found.flatMap { (part, d) ->
            d?.groups.orEmpty().map { g -> Group(listOfNotNull(part, g.label).joinToString(" · "), g.meanings) }
        }
        val ipas = found.map { it.second?.ipa }
        val ipa = if (ipas.all { it != null }) ipas.joinToString("") { it!!.trimEnd('‿') } else null
        return Description(groups, ipa)
    }

    private fun describeWhole(word: String): Description? {
        val found = matching(word)
        if (found.isEmpty()) return null
        val senses = found.map { e ->
            val base = e.formOf?.let { baseEntries(it, e.pos) }.orEmpty()
            if (base.isEmpty()) Sense(e.pos, e.meanings, note = null)
            else Sense(e.pos, base.flatMap { it.meanings }, note = e.meanings.first())
        }
        // A group per part of speech and, within it, per form: "den" (day) and "den" (genitive
        // plural of dno, bottom) are both nouns but stay apart, each under its own label.
        val groups = senses.groupBy { it.pos to it.note }.map { (key, group) ->
            Group(
                listOfNotNull(posName(key.first), key.second).joinToString(" · ").ifEmpty { null },
                group.flatMap { it.meanings }.distinct().take(MAX_MEANINGS),
            )
        }
        return Description(groups, found.firstNotNullOfOrNull { it.ipa })
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

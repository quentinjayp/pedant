package com.pedant.app

/** What words are written with: letters, marks, digits, apostrophes and hyphens. */
private val WORD_PATTERN = Regex("[\\p{L}\\p{M}\\p{N}'’-]+")

private fun isChinese(codePoint: Int) = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN

/**
 * The words of [text], in order, each with where it is in [text]. This is the one way the app
 * splits text into words, whether to tap them, look them up or grade them. A word is a run of
 * letters, marks, digits, apostrophes and hyphens with at least one letter or digit in it. Chinese
 * is written without spaces, so each of its characters is a word.
 */
fun wordSpans(text: String): List<Pair<IntRange, String>> {
    val out = mutableListOf<Pair<IntRange, String>>()
    for (match in WORD_PATTERN.findAll(text)) {
        val token = match.value
        fun add(from: Int, to: Int) {
            val piece = token.substring(from, to)
            if (piece.codePoints().anyMatch(Character::isLetterOrDigit)) {
                out += (match.range.first + from until match.range.first + to) to piece
            }
        }
        // Where the run of other writing being gathered starts.
        var runStart = 0
        var i = 0
        while (i < token.length) {
            val codePoint = token.codePointAt(i)
            val next = i + Character.charCount(codePoint)
            if (isChinese(codePoint)) {
                if (i > runStart) add(runStart, i)
                add(i, next)
                runStart = next
            }
            i = next
        }
        if (runStart < token.length) add(runStart, token.length)
    }
    return out
}

/** The words of [text], in order; see [wordSpans]. */
fun wordsOf(text: String): List<String> = wordSpans(text).map { it.second }

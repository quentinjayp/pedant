package com.pedant.app

/** A word as tappable in the app: letters, marks, digits, apostrophes and hyphens. */
val WORD_PATTERN = Regex("[\\p{L}\\p{M}\\p{N}'’-]+")

/** Whether [text] is written without spaces between words: it has Chinese characters. */
fun writtenWithoutSpaces(text: String): Boolean =
    text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }

/** Languages written without spaces (such as Chinese) are split into single characters. */
fun splitWord(token: String): List<String> =
    if (writtenWithoutSpaces(token)) {
        token.map { it.toString() }
    } else {
        listOf(token)
    }

/** The tappable words of [text], in order. */
fun tappableWords(text: String): List<String> = WORD_PATTERN.findAll(text).flatMap { splitWord(it.value) }.toList()

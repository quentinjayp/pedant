package com.pedant.app

/** A word as tappable in the app: letters, marks, digits, apostrophes and hyphens. */
val WORD_PATTERN = Regex("[\\p{L}\\p{M}\\p{N}'’-]+")

/** Languages written without spaces (such as Chinese) are split into single characters. */
fun splitWord(token: String): List<String> =
    if (token.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) {
        token.map { it.toString() }
    } else {
        listOf(token)
    }

/** The tappable words of [text], in order. */
fun tappableWords(text: String): List<String> = WORD_PATTERN.findAll(text).flatMap { splitWord(it.value) }.toList()

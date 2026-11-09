package com.pedant.app

/**
 * Turns IPA sounds into an English-style respelling ("Sounds like"), e.g. /pʁɑ̃dʁ/ → "prahndr".
 * It works from the same sounds shown as IPA, so the two always agree.
 */
object Respell {
    /** Two-symbol sounds, checked before single symbols. */
    private val PAIRS = mapOf(
        "tʃ" to "ch", "dʒ" to "j", "ts" to "ts", "dz" to "dz",
        "ɑ̃" to "ahn", "ã" to "ahn", "ɔ̃" to "ohn", "õ" to "ohn",
        "ɛ̃" to "an", "ẽ" to "en", "œ̃" to "uhn", "ĩ" to "een", "ũ" to "oon",
    )

    private val VOWELS = mapOf(
        "i" to "ee", "ɪ" to "i", "y" to "ew", "ʏ" to "ew", "e" to "ay", "ɛ" to "eh", "ə" to "uh",
        "ɘ" to "uh", "ø" to "uh", "œ" to "uh", "a" to "ah", "ɑ" to "ah", "æ" to "a", "ɐ" to "uh",
        "ʌ" to "uh", "ɔ" to "aw", "o" to "oh", "ɒ" to "o", "u" to "oo", "ʊ" to "oo", "ɯ" to "oo",
        "ɜ" to "ur", "ɤ" to "uh", "ɨ" to "ih", "ʉ" to "oo",
    )

    private val CONSONANTS = mapOf(
        "p" to "p", "b" to "b", "t" to "t", "d" to "d", "k" to "k", "g" to "g", "ɡ" to "g", "q" to "k",
        "f" to "f", "v" to "v", "s" to "s", "z" to "z", "ʃ" to "sh", "ʒ" to "zh", "h" to "h", "ɦ" to "h",
        "m" to "m", "n" to "n", "ɲ" to "ny", "ŋ" to "ng", "l" to "l", "ɫ" to "l", "ʎ" to "ly",
        "r" to "r", "ʁ" to "r", "ɹ" to "r", "ʀ" to "r", "ɾ" to "r", "ɻ" to "r", "χ" to "kh", "x" to "kh",
        "j" to "y", "w" to "w", "ɥ" to "w", "θ" to "th", "ð" to "th", "ç" to "h", "ʂ" to "sh",
        "ʐ" to "zh", "ɕ" to "sh", "ʑ" to "zh", "β" to "v", "ɣ" to "g", "ɟ" to "gy", "c" to "ky",
    )

    /** Consonant pairs that start a syllable together, as in "pr", "dr", "bl". */
    private val ONSETS = setOf("p", "b", "t", "d", "k", "g", "ɡ", "f", "v")
    private val LIQUIDS = setOf("r", "ʁ", "ɹ", "ʀ", "ɾ", "l", "j", "w", "ɥ")
    private val GLIDES = setOf("j", "w", "ɥ")

    private data class Sound(val ipa: String, val letters: String, val vowel: Boolean)

    fun fromIpa(ipa: String): String {
        val sounds = parse(ipa)
        if (sounds.isEmpty()) return ""
        // One syllable per vowel. Between vowels, a single consonant (or a pair like "dr") starts
        // the next syllable; any other consonants stay with the syllable before.
        val syllables = mutableListOf<StringBuilder>()
        var pending = mutableListOf<Sound>()
        for (sound in sounds) {
            if (!sound.vowel) {
                pending += sound
                continue
            }
            if (syllables.isEmpty()) {
                syllables += StringBuilder()
            } else {
                val keep = when {
                    pending.size <= 1 -> 0
                    // A consonant before a glide ("sw" in "suis"), or a pair like "dr", starts together.
                    pending.last().ipa in GLIDES -> pending.size - 2
                    pending[pending.size - 2].ipa in ONSETS && pending.last().ipa in LIQUIDS -> pending.size - 2
                    else -> pending.size - 1
                }
                pending.take(keep).forEach { syllables.last().append(it.letters) }
                pending = pending.drop(keep).toMutableList()
                syllables += StringBuilder()
            }
            pending.forEach { syllables.last().append(it.letters) }
            pending.clear()
            syllables.last().append(sound.letters)
        }
        if (syllables.isEmpty()) syllables += StringBuilder()
        pending.forEach { syllables.last().append(it.letters) }
        return syllables.joinToString("-")
    }

    private fun parse(ipa: String): List<Sound> {
        val text = ipa.filter { !it.isWhitespace() && it !in "/ˈˌːˑ.˞ʰʲʷ" }
        val out = mutableListOf<Sound>()
        var i = 0
        while (i < text.length) {
            val pair = if (i + 1 < text.length) text.substring(i, i + 2) else null
            if (pair != null && pair in PAIRS) {
                out += Sound(pair, PAIRS.getValue(pair), vowel = pair.last() == '̃')
                i += 2
                continue
            }
            val c = text[i].toString()
            // A nasal mark after a vowel not covered above: add "n".
            if (c == "̃") {
                if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(letters = out.last().letters + "n")
            } else {
                VOWELS[c]?.let { out += Sound(c, it, vowel = true) }
                    ?: CONSONANTS[c]?.let { out += Sound(c, it, vowel = false) }
            }
            i++
        }
        return out
    }
}

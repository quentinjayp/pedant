package com.pedant.app

import java.io.File
import java.text.Normalizer

/**
 * espeak-ng's phonemes for a phrase, word by word: the sounds Piper is given to say, which are the
 * expected sounds shown to the learner ("un" /œ̃/). The same espeak-ng as sherpa-onnx's (see
 * src/main/cpp), reading the espeak-ng-data that comes with the voices.
 */
object Phonemizer {
    private val loaded = runCatching { System.loadLibrary("pedant_espeak") }.isSuccess
    private var started = false

    private external fun nativeInit(dataDir: String): Boolean
    private external fun nativePhonemize(voice: String, text: String): String?

    /**
     * Each of [words]' sounds in [phrase], as symbols like ZIPA's (one per sound, marks on their
     * own, no stress marks). Taken from the whole phrase as espeak-ng reads it (with its liaisons)
     * when it has a word for each of [words]; otherwise each word on its own. Null if espeak-ng
     * can't be used.
     */
    @Synchronized
    fun words(dataDir: File, voice: String, phrase: String, words: List<String>): List<List<String>>? {
        if (!loaded) return null
        if (!started) started = runCatching { nativeInit(dataDir.path) }.getOrDefault(false)
        if (!started) return null
        val whole = nativePhonemize(voice, phrase)?.split(SPACES)?.filter { it.isNotEmpty() } ?: return null
        val each = if (whole.size == words.size) whole else words.map { w ->
            nativePhonemize(voice, w)?.replace(SPACES, "") ?: return null
        }
        return each.map(::symbols)
    }

    /**
     * IPA as separate sounds, without stress marks, syllable breaks or links, espeak-ng's language
     * switches ("(en)" where it reads a "/" in English) or its tone numbers (Vietnamese, Mandarin;
     * ZIPA doesn't hear tones).
     */
    fun symbols(ipa: String): List<String> =
        Normalizer.normalize(ipa.replace(LANGUAGE_SWITCH, ""), Normalizer.Form.NFD)
            .filterNot { it in DROP || it.isWhitespace() || it.isDigit() }
            .map { it.toString() }

    private val SPACES = Regex("\\s+")
    private val LANGUAGE_SWITCH = Regex("\\([a-z-]+\\)")
    private const val DROP = "ˈˌ.‿͜͡-"
}

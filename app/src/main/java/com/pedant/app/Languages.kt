package com.pedant.app

import android.content.Context
import org.json.JSONObject

/**
 * A language the app can hold a conversation in, with the Piper voice that speaks it and
 * [ttsRewrites]: fixes for text espeak-ng misreads, applied before it reads any.
 */
data class Language(
    val code: String,
    val englishName: String,
    val nativeName: String,
    val piperVoice: String,
    val ttsRewrites: List<Pair<Regex, String>> = emptyList(),
    /** The espeak-ng voice the Piper voice reads text with (its .onnx.json's espeak.voice). */
    val espeakVoice: String,
    /** The articles a singular noun takes, one per gender ("le", "la"), for the quiz; empty if they don't show it. */
    val articles: List<String> = emptyList(),
    /**
     * Whether the language drops most final consonants, said or not by rules a learner has to
     * know (French's), so the quiz asks which are said.
     */
    val finalConsonants: Boolean = false,
    /** Whether nouns are written with a capital wherever they are (German), so a capital doesn't mark a sentence's start. */
    val capitalNouns: Boolean = false,
    /** The bands it's offered at, to learn: none for a language Gemma writes too poorly (languages.json). */
    val bands: Set<Band> = Band.entries.toSet(),
    /** Whether it's offered as the learner's own language (languages.json's own). */
    val ownOffered: Boolean = true,
) {
    /** The language's English name after "a" or "an", as Gemma's instructions put it ("an English", "a French"). */
    val withArticle: String get() = (if (englishName.first() in "AEIO") "an " else "a ") + englishName

    /** [text] with [ttsRewrites] applied, as espeak-ng should be given it. */
    fun espeakText(text: String): String =
        ttsRewrites.fold(text) { t, (pattern, replacement) -> pattern.replace(t, replacement) }
}

/**
 * The app's languages, from assets/languages.json (which says why each is, or isn't, there). The
 * tools read the same file (tools/app_languages.py), so the two can't drift.
 */
lateinit var LANGUAGES: List<Language>
    private set

/** The learner's own language until they choose another. */
const val DEFAULT_OWN = "en"

/** Reads [LANGUAGES]; called once as the app starts, before anything uses them. */
fun loadLanguages(context: Context) {
    if (::LANGUAGES.isInitialized) return
    val list = JSONObject(context.assets.open("languages.json").bufferedReader().use { it.readText() })
        .getJSONArray("languages")
    LANGUAGES = List(list.length()) { i ->
        val o = list.getJSONObject(i)
        val rewrites = o.optJSONArray("ttsRewrites")
        Language(
            code = o.getString("code"),
            englishName = o.getString("englishName"),
            nativeName = o.getString("nativeName"),
            piperVoice = o.getString("piperVoice"),
            ttsRewrites = List(rewrites?.length() ?: 0) { r ->
                rewrites!!.getJSONObject(r).let { Regex(it.getString("pattern")) to it.getString("replacement") }
            },
            espeakVoice = o.getString("espeakVoice"),
            articles = o.optJSONArray("articles")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
            finalConsonants = o.optBoolean("finalConsonants"),
            capitalNouns = o.optBoolean("capitalNouns"),
            bands = o.optJSONArray("bands")?.let { a ->
                (0 until a.length()).mapNotNull { i -> Band.entries.firstOrNull { it.name == a.getString(i) } }.toSet()
            } ?: Band.entries.toSet(),
            ownOffered = o.optBoolean("own", true),
        )
    }
}

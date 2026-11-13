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
) {
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
        )
    }
}

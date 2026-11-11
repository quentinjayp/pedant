package com.pedant.app

import android.content.Context
import org.json.JSONObject

/**
 * Gemma's instructions, from assets/instructions.json. The same file is read by
 * tools/build_openings.py, which writes the openings that ship with the app, so the two can't drift.
 */
object Instructions {
    private lateinit var json: JSONObject

    fun load(context: Context) {
        if (!::json.isInitialized) {
            json = JSONObject(context.assets.open("instructions.json").bufferedReader().use { it.readText() })
        }
    }

    private fun text(name: String, language: Language) = json.getString(name).replace("{language}", language.englishName)

    val start: String get() = json.getString("start")

    fun said(phrase: String) = json.getString("said").replace("{phrase}", phrase)

    fun heard(transcript: String, sounds: String) =
        json.getString("heard").replace("{transcript}", transcript).replace("{sounds}", sounds)

    /** What Gemma is told for a conversation: the mode's instructions, then how to open it. */
    fun system(language: Language, mode: Mode, scenario: Scenario): String {
        val role = json.getJSONObject("roles").let { if (it.isNull(scenario.name)) null else it.getString(scenario.name) }
        val opening = if (role == null) text("openDefault", language) else text("openRole", language).replace("{role}", role)
        return json.getJSONObject("modes").getString(mode.name).replace("{language}", language.englishName) + "\n\n" + opening
    }

    fun correction(language: Language) = text("correction", language)
    fun replyTranslate(language: Language) = text("replyTranslate", language)
    fun translate(language: Language) = text("translate", language)
    fun lookup(language: Language) = text("lookup", language)
    fun sentenceLookup(language: Language) = text("sentenceLookup", language)
}

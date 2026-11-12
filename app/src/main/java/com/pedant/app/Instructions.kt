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

    fun start(register: Register) = json.getString("start").replace("{register}", register.name.lowercase())

    fun said(phrase: String, register: Register) =
        json.getString("said").replace("{register}", register.name.lowercase()).replace("{phrase}", phrase)

    /** What Gemma is told for a conversation: the learning instructions, then how to open it. */
    fun system(language: Language, scenario: Scenario): String {
        val role = json.getJSONObject("roles").let { if (it.isNull(scenario.name)) null else it.getString(scenario.name) }
        val opening = if (role == null) text("openDefault", language) else text("openRole", language).replace("{role}", role)
        return text("learning", language) + "\n\n" + opening
    }

    fun replyTranslate(language: Language) = text("replyTranslate", language)
    fun translate(language: Language, register: Register) =
        text("translate", language).replace("{register}", register.name.lowercase())
    fun lookup(language: Language) = text("lookup", language)
    fun sentenceLookup(language: Language) = text("sentenceLookup", language)
}

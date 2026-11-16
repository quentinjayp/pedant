package com.pedant.app

import android.content.Context
import org.json.JSONObject

/**
 * Gemma's instructions, from assets/instructions.json. The same file is read by
 * tools/build_openings.py, which writes the openings that ship with the app, so the two can't drift.
 * Each takes the language being learned and the learner's own language ([own]).
 */
object Instructions {
    private lateinit var json: JSONObject

    fun load(context: Context) {
        if (!::json.isInitialized) {
            json = JSONObject(context.assets.open("instructions.json").bufferedReader().use { it.readText() })
        }
    }

    private fun text(name: String, language: Language, own: Language) = json.getString(name)
        .replace("{language}", language.englishName)
        .replace("{anOwn}", own.withArticle)
        .replace("{own}", own.englishName)

    fun start(register: Register) = json.getString("start").replace("{register}", register.name.lowercase())

    fun said(phrase: String, register: Register) =
        json.getString("said").replace("{register}", register.name.lowercase()).replace("{phrase}", phrase)

    /** What Gemma is told for a conversation: the learning instructions, then how to open it. */
    fun system(language: Language, scenario: Scenario, own: Language): String {
        val role = json.getJSONObject("roles").let { if (it.isNull(scenario.name)) null else it.getString(scenario.name) }
        val opening = if (role == null) text("openDefault", language, own) else text("openRole", language, own).replace("{role}", role)
        return text("learning", language, own) + "\n\n" + opening
    }

    fun replyTranslate(language: Language, own: Language) = text("replyTranslate", language, own)
    fun translate(language: Language, register: Register, own: Language) =
        text("translate", language, own).replace("{register}", register.name.lowercase())
    fun lookup(language: Language, own: Language) = text("lookup", language, own)
    fun expression(language: Language, own: Language) = text("expression", language, own)
    fun sentenceLookup(language: Language, own: Language) = text("sentenceLookup", language, own)
    fun quizForms(language: Language, own: Language) = text("quizForms", language, own)
    fun quizAlike(language: Language, own: Language) = text("quizAlike", language, own)
    fun quizArticles(language: Language, own: Language) =
        text("quizArticles", language, own).replace("{articles}", articleTitle(language.articles).dropLast(1).lowercase())
}

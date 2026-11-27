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

    private fun text(name: String, language: Language, own: Language) = fill(json.getString(name), language, own)

    private fun fill(text: String, language: Language, own: Language) = text
        .replace("{language}", language.englishName)
        .replace("{anOwn}", own.withArticle)
        .replace("{own}", own.englishName)

    /** [text] with [band]'s parts in place of {band}, {reply}, {translation} and {translationGuide}. */
    private fun withBand(text: String, band: Band): String {
        val b = json.getJSONObject("bands").getJSONObject(band.name)
        return text.replace("{band}", b.getString("band")).replace("{reply}", b.getString("reply"))
            .replace("{translation}", b.getString("translation")).replace("{translationGuide}", b.getString("translationGuide"))
    }

    fun start(register: Register) = json.getString("start").replace("{register}", register.name.lowercase())

    fun said(phrase: String, register: Register) =
        json.getString("said").replace("{register}", register.name.lowercase()).replace("{phrase}", phrase)

    /** What Gemma is told for a conversation: the learning instructions at [band], then how to open it. */
    fun system(language: Language, scenario: Scenario, own: Language, band: Band): String {
        val role = scenario.role
        val setting = scenario.setting(language)
        val opening = when {
            role == null -> text("openDefault", language, own)
            // A retired scenario has no settings: Gemma isn't told where.
            setting == null -> text("openRole", language, own).replace("Where: {setting}.\n", "").replace("{role}", role)
            else -> text("openRole", language, own).replace("{role}", role).replace("{setting}", setting)
        }
        return fill(withBand(json.getString("learning"), band), language, own) + "\n\n" + opening
    }

    fun replyTranslate(language: Language, own: Language) = text("replyTranslate", language, own)
    fun translate(language: Language, register: Register, own: Language, band: Band) =
        fill(withBand(json.getString("translate"), band), language, own).replace("{register}", register.name.lowercase())
    fun lookup(language: Language, own: Language) = text("lookup", language, own)
    fun expression(language: Language, own: Language) = text("expression", language, own)
    fun sentenceLookup(language: Language, own: Language) = text("sentenceLookup", language, own)
    fun quizForms(language: Language, own: Language) = text("quizForms", language, own)
    fun quizAlike(language: Language, own: Language) = text("quizAlike", language, own)
    fun quizArticles(language: Language, own: Language) =
        text("quizArticles", language, own).replace("{articles}", articleTitle(language.articles).dropLast(1).lowercase())
}

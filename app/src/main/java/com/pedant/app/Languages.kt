package com.pedant.app

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
 * espeak-ng reads a French hyphenated "-y" at the end of a word ("Allons-y", "Vas-y") as the
 * letter's name, "i grec"; "-i" is read correctly.
 */
private val FRENCH_HYPHEN_Y = Regex("(?<=\\p{L})-[yY](?![\\p{L}\\p{N}])") to "-i"

/**
 * The languages supported by Gemma 4 E4B, Omnilingual ASR, ZIPA and Piper together. Arabic was
 * removed: it's written without most vowels, so its expected sounds (espeak-ng's) are often guesses,
 * and the app teaches and judges by sounds.
 */
val LANGUAGES = listOf(
    Language("cs", "Czech", "Čeština", "cs_CZ-jirka-medium", espeakVoice = "cs"),
    Language("da", "Danish", "Dansk", "da_DK-talesyntese-medium", espeakVoice = "da"),
    Language("de", "German", "Deutsch", "de_DE-thorsten-medium", espeakVoice = "de"),
    Language("el", "Greek", "Ελληνικά", "el_GR-rapunzelina-low", espeakVoice = "el"),
    Language("en", "English", "English", "en_US-lessac-medium", espeakVoice = "en-us"),
    Language("es", "Spanish", "Español", "es_ES-davefx-medium", espeakVoice = "es"),
    Language("fi", "Finnish", "Suomi", "fi_FI-harri-medium", espeakVoice = "fi"),
    Language("fr", "French", "Français", "fr_FR-siwis-medium", ttsRewrites = listOf(FRENCH_HYPHEN_Y), espeakVoice = "fr"),
    Language("hi", "Hindi", "हिन्दी", "hi_IN-pratham-medium", espeakVoice = "hi"),
    Language("hu", "Hungarian", "Magyar", "hu_HU-anna-medium", espeakVoice = "hu"),
    Language("id", "Indonesian", "Bahasa Indonesia", "id_ID-news_tts-medium", espeakVoice = "id"),
    Language("it", "Italian", "Italiano", "it_IT-paola-medium", espeakVoice = "it"),
    Language("nl", "Dutch", "Nederlands", "nl_BE-nathalie-medium", espeakVoice = "nl"),
    Language("no", "Norwegian", "Norsk", "no_NO-talesyntese-medium", espeakVoice = "nb"),
    Language("pl", "Polish", "Polski", "pl_PL-gosia-medium", espeakVoice = "pl"),
    Language("pt", "Portuguese", "Português", "pt_BR-faber-medium", espeakVoice = "pt-br"),
    Language("ro", "Romanian", "Română", "ro_RO-mihai-medium", espeakVoice = "ro"),
    Language("ru", "Russian", "Русский", "ru_RU-irina-medium", espeakVoice = "ru"),
    Language("sk", "Slovak", "Slovenčina", "sk_SK-lili-medium", espeakVoice = "sk"),
    Language("sl", "Slovenian", "Slovenščina", "sl_SI-artur-medium", espeakVoice = "sl"),
    Language("sr", "Serbian", "Српски", "sr_RS-serbski_institut-medium", espeakVoice = "sr"),
    Language("sv", "Swedish", "Svenska", "sv_SE-nst-medium", espeakVoice = "sv"),
    Language("sw", "Swahili", "Kiswahili", "sw_CD-lanfrica-medium", espeakVoice = "sw"),
    Language("tr", "Turkish", "Türkçe", "tr_TR-dfki-medium", espeakVoice = "tr"),
    Language("uk", "Ukrainian", "Українська", "uk_UA-tetiana-high", espeakVoice = "uk"),
    Language("vi", "Vietnamese", "Tiếng Việt", "vi_VN-vais1000-medium", espeakVoice = "vi"),
    Language("zh", "Mandarin", "中文", "zh_CN-huayan-medium", espeakVoice = "cmn"),
)

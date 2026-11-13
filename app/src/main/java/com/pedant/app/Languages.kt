package com.pedant.app

/**
 * A language the app can hold a conversation in, with the Piper voices that can speak it (first
 * choice first) and [ttsRewrites]: fixes for text espeak-ng misreads, applied before speaking.
 */
data class Language(
    val code: String,
    val englishName: String,
    val nativeName: String,
    val piperVoices: List<String>,
    val ttsRewrites: List<Pair<Regex, String>> = emptyList(),
    /** The espeak-ng voice the Piper voices read text with (their .onnx.json's espeak.voice). */
    val espeakVoice: String,
)

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
    Language("cs", "Czech", "Čeština", listOf("cs_CZ-jirka-medium"), espeakVoice = "cs"),
    Language("da", "Danish", "Dansk", listOf("da_DK-talesyntese-medium"), espeakVoice = "da"),
    Language("de", "German", "Deutsch", listOf("de_DE-thorsten-medium"), espeakVoice = "de"),
    Language("el", "Greek", "Ελληνικά", listOf("el_GR-rapunzelina-low"), espeakVoice = "el"),
    Language("en", "English", "English", listOf("en_US-lessac-medium"), espeakVoice = "en-us"),
    Language("es", "Spanish", "Español", listOf("es_ES-davefx-medium"), espeakVoice = "es"),
    Language("fi", "Finnish", "Suomi", listOf("fi_FI-harri-medium"), espeakVoice = "fi"),
    Language("fr", "French", "Français", listOf("fr_FR-siwis-medium"), ttsRewrites = listOf(FRENCH_HYPHEN_Y), espeakVoice = "fr"),
    Language("hi", "Hindi", "हिन्दी", listOf("hi_IN-pratham-medium"), espeakVoice = "hi"),
    Language("hu", "Hungarian", "Magyar", listOf("hu_HU-anna-medium"), espeakVoice = "hu"),
    Language("id", "Indonesian", "Bahasa Indonesia", listOf("id_ID-news_tts-medium"), espeakVoice = "id"),
    Language("it", "Italian", "Italiano", listOf("it_IT-paola-medium"), espeakVoice = "it"),
    Language("nl", "Dutch", "Nederlands", listOf("nl_BE-nathalie-medium"), espeakVoice = "nl"),
    Language("no", "Norwegian", "Norsk", listOf("no_NO-talesyntese-medium"), espeakVoice = "nb"),
    Language("pl", "Polish", "Polski", listOf("pl_PL-gosia-medium"), espeakVoice = "pl"),
    Language("pt", "Portuguese", "Português", listOf("pt_BR-faber-medium"), espeakVoice = "pt-br"),
    Language("ro", "Romanian", "Română", listOf("ro_RO-mihai-medium"), espeakVoice = "ro"),
    Language("ru", "Russian", "Русский", listOf("ru_RU-irina-medium"), espeakVoice = "ru"),
    Language("sk", "Slovak", "Slovenčina", listOf("sk_SK-lili-medium"), espeakVoice = "sk"),
    Language("sl", "Slovenian", "Slovenščina", listOf("sl_SI-artur-medium"), espeakVoice = "sl"),
    Language("sr", "Serbian", "Српски", listOf("sr_RS-serbski_institut-medium"), espeakVoice = "sr"),
    Language("sv", "Swedish", "Svenska", listOf("sv_SE-nst-medium"), espeakVoice = "sv"),
    Language("sw", "Swahili", "Kiswahili", listOf("sw_CD-lanfrica-medium"), espeakVoice = "sw"),
    Language("tr", "Turkish", "Türkçe", listOf("tr_TR-dfki-medium"), espeakVoice = "tr"),
    Language("uk", "Ukrainian", "Українська", listOf("uk_UA-tetiana-high"), espeakVoice = "uk"),
    Language("vi", "Vietnamese", "Tiếng Việt", listOf("vi_VN-vais1000-medium"), espeakVoice = "vi"),
    Language("zh", "Mandarin", "中文", listOf("zh_CN-huayan-medium"), espeakVoice = "cmn"),
)

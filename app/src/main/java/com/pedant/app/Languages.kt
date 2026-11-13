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
)

/**
 * espeak-ng reads a French hyphenated "-y" at the end of a word ("Allons-y", "Vas-y") as the
 * letter's name, "i grec"; "-i" is read correctly.
 */
private val FRENCH_HYPHEN_Y = Regex("(?<=\\p{L})-[yY](?![\\p{L}\\p{N}])") to "-i"

/** The 28 languages supported by Gemma 4 E4B, Omnilingual ASR, ZIPA and Piper together. */
val LANGUAGES = listOf(
    Language("ar", "Arabic", "العربية", listOf("ar_JO-kareem-medium")),
    Language("cs", "Czech", "Čeština", listOf("cs_CZ-jirka-medium")),
    Language("da", "Danish", "Dansk", listOf("da_DK-talesyntese-medium")),
    Language("de", "German", "Deutsch", listOf("de_DE-thorsten-medium")),
    Language("el", "Greek", "Ελληνικά", listOf("el_GR-rapunzelina-low")),
    Language("en", "English", "English", listOf("en_US-lessac-medium")),
    Language("es", "Spanish", "Español", listOf("es_ES-davefx-medium")),
    Language("fi", "Finnish", "Suomi", listOf("fi_FI-harri-medium")),
    Language("fr", "French", "Français", listOf("fr_FR-siwis-medium"), ttsRewrites = listOf(FRENCH_HYPHEN_Y)),
    Language("hi", "Hindi", "हिन्दी", listOf("hi_IN-pratham-medium")),
    Language("hu", "Hungarian", "Magyar", listOf("hu_HU-anna-medium")),
    Language("id", "Indonesian", "Bahasa Indonesia", listOf("id_ID-news_tts-medium")),
    Language("it", "Italian", "Italiano", listOf("it_IT-paola-medium")),
    Language("nl", "Dutch", "Nederlands", listOf("nl_BE-nathalie-medium")),
    Language("no", "Norwegian", "Norsk", listOf("no_NO-talesyntese-medium")),
    Language("pl", "Polish", "Polski", listOf("pl_PL-gosia-medium")),
    Language("pt", "Portuguese", "Português", listOf("pt_BR-faber-medium")),
    Language("ro", "Romanian", "Română", listOf("ro_RO-mihai-medium")),
    Language("ru", "Russian", "Русский", listOf("ru_RU-irina-medium")),
    Language("sk", "Slovak", "Slovenčina", listOf("sk_SK-lili-medium")),
    Language("sl", "Slovenian", "Slovenščina", listOf("sl_SI-artur-medium")),
    Language("sr", "Serbian", "Српски", listOf("sr_RS-serbski_institut-medium")),
    Language("sv", "Swedish", "Svenska", listOf("sv_SE-nst-medium")),
    Language("sw", "Swahili", "Kiswahili", listOf("sw_CD-lanfrica-medium")),
    Language("tr", "Turkish", "Türkçe", listOf("tr_TR-dfki-medium")),
    Language("uk", "Ukrainian", "Українська", listOf("uk_UA-tetiana-high", "uk_UA-lada-x_low")),
    Language("vi", "Vietnamese", "Tiếng Việt", listOf("vi_VN-vais1000-medium")),
    Language("zh", "Mandarin", "中文", listOf("zh_CN-huayan-medium")),
)

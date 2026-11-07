package com.pedant.app

/** A language the app can hold a conversation in, with the Piper voice that speaks it. */
data class Language(
    val code: String,
    val englishName: String,
    val nativeName: String,
    val piperVoice: String,
)

/** The 28 languages supported by Gemma 4 E4B, Omnilingual ASR, ZIPA and Piper together. */
val LANGUAGES = listOf(
    Language("ar", "Arabic", "العربية", "ar_JO-kareem-medium"),
    Language("cs", "Czech", "Čeština", "cs_CZ-jirka-medium"),
    Language("da", "Danish", "Dansk", "da_DK-talesyntese-medium"),
    Language("de", "German", "Deutsch", "de_DE-thorsten-medium"),
    Language("el", "Greek", "Ελληνικά", "el_GR-rapunzelina-low"),
    Language("en", "English", "English", "en_US-lessac-medium"),
    Language("es", "Spanish", "Español", "es_ES-davefx-medium"),
    Language("fi", "Finnish", "Suomi", "fi_FI-harri-medium"),
    Language("fr", "French", "Français", "fr_FR-siwis-medium"),
    Language("hi", "Hindi", "हिन्दी", "hi_IN-pratham-medium"),
    Language("hu", "Hungarian", "Magyar", "hu_HU-anna-medium"),
    Language("id", "Indonesian", "Bahasa Indonesia", "id_ID-news_tts-medium"),
    Language("it", "Italian", "Italiano", "it_IT-paola-medium"),
    Language("nl", "Dutch", "Nederlands", "nl_NL-pim-medium"),
    Language("no", "Norwegian", "Norsk", "no_NO-talesyntese-medium"),
    Language("pl", "Polish", "Polski", "pl_PL-gosia-medium"),
    Language("pt", "Portuguese", "Português", "pt_BR-faber-medium"),
    Language("ro", "Romanian", "Română", "ro_RO-mihai-medium"),
    Language("ru", "Russian", "Русский", "ru_RU-irina-medium"),
    Language("sk", "Slovak", "Slovenčina", "sk_SK-lili-medium"),
    Language("sl", "Slovenian", "Slovenščina", "sl_SI-artur-medium"),
    Language("sr", "Serbian", "Српски", "sr_RS-serbski_institut-medium"),
    Language("sv", "Swedish", "Svenska", "sv_SE-nst-medium"),
    Language("sw", "Swahili", "Kiswahili", "sw_CD-lanfrica-medium"),
    Language("tr", "Turkish", "Türkçe", "tr_TR-dfki-medium"),
    Language("uk", "Ukrainian", "Українська", "uk_UA-ukrainian_tts-medium"),
    Language("vi", "Vietnamese", "Tiếng Việt", "vi_VN-vais1000-medium"),
    Language("zh", "Mandarin", "中文", "zh_CN-xiao_ya-medium"),
)

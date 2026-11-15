package com.pedant.app

/** How formally Gemma speaks and translates: chosen for each conversation, changeable at any time. */
enum class Register(val title: String) {
    Formal("Formal"),
    Casual("Casual"),
}

/**
 * How much help the learner gets with a phrase. In [Beginner] they're taken through it one word at a
 * time before saying it whole; in [Intermediate] they say it whole straight away.
 */
enum class Mode(val title: String, val about: String) {
    Beginner("Beginner", "Say what you want in English, then say it one word at a time before the whole phrase."),
    Intermediate("Intermediate", "Say what you want in English, then repeat it back in the language until you get it right."),
}

/**
 * How the phrase to say is given to the learner: said by the voice and shown as text, or only one
 * of the two. Never neither.
 */
enum class PhraseGuide(val title: String) {
    VoiceAndText("Voice guide and translation"),

    /** The text stays hidden until the first try, so the learner repeats what they heard. */
    VoiceOnly("Voice only"),

    /** The voice doesn't say the phrase, so the learner reads it. */
    TextOnly("Translation only"),
}

/**
 * A setting for a conversation; [scene] is shown to the learner at the top of the conversation.
 * Who Gemma plays in it is in assets/instructions.json, under "roles".
 */
enum class Scenario(val title: String, val scene: String) {
    Default(
        "Default",
        "Talk about whatever you like.",
    ),
    Recommendations(
        "Asking for recommendations",
        "You've stopped a friendly local on the street to ask for recommendations.",
    ),
    Coffee(
        "Coffee with a friend",
        "You're catching up with a friend over coffee at a café.",
    ),
    FirstDate(
        "First date at a restaurant",
        "You're on a first date at a restaurant, getting to know each other.",
    ),
    BusStop(
        "Bus stop",
        "You're waiting at a bus stop; the person next to you is waiting too.",
    ),
    Airport(
        "Airport",
        "You're waiting at an airport gate next to another traveller.",
    ),
}

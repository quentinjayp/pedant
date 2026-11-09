package com.pedant.app

/**
 * A setting for a conversation. Gemma plays the other person ([role]); [scene] is shown to the
 * learner at the top of the conversation.
 */
enum class Scenario(val title: String, val scene: String, val role: String?) {
    Default(
        "Default",
        "Talk about whatever you like.",
        null,
    ),
    Recommendations(
        "Asking for recommendations",
        "You've stopped a friendly local on the street to ask for recommendations.",
        "You are a friendly local. The learner is a visitor who has stopped you on the street to ask " +
            "for recommendations, such as places to eat and things to see.",
    ),
    Coffee(
        "Coffee with a friend",
        "You're catching up with a friend over coffee at a café.",
        "You are the learner's friend, meeting them for coffee at a café to catch up.",
    ),
    FirstDate(
        "First date at a restaurant",
        "You're on a first date at a restaurant, getting to know each other.",
        "You are on a first date with the learner at a restaurant, getting to know each other.",
    ),
    BusStop(
        "Bus stop",
        "You're waiting at a bus stop; the person next to you is waiting too.",
        "You are a stranger waiting at a bus stop next to the learner, making small talk.",
    ),
    Airport(
        "Airport",
        "You're waiting at an airport gate next to another traveller.",
        "You are a fellow traveller waiting at an airport gate next to the learner, making small talk.",
    ),
}

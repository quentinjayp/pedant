package com.pedant.app

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

package com.pedant.app

/** Sample phrases, results and questions shared by the quiz's tests and screenshots. */
object QuizSamples {
    val phrases = listOf(
        QuizSource("Je voudrais un café, s'il vous plaît.", "I'd like a coffee, please."),
        QuizSource("Les amis sont arrivés hier soir.", "The friends arrived last night."),
        QuizSource("Je dois me rendre au centre de tir pour pointer mon fusil.", "I have to go to the gun range to sight in my rifle."),
        QuizSource("Nous allons jouer au parc.", "We're going to play at the park."),
    )

    /** An attempt at [phrase]: its words right, except those at [wrong]. */
    fun result(phrase: String, wrong: Set<Int> = emptySet()) = AttemptResult(
        wordScore = 1.0 - wrong.size.toDouble() / wordsOf(phrase).size,
        soundScore = 0.8,
        passed = wrong.isEmpty(),
        words = wordsOf(phrase).mapIndexed { i, w ->
            WordResult(w, listOf("a"), listOf("a"), heard = "a", score = if (i in wrong) 0.3 else 1.0, heardWord = w, passed = i !in wrong)
        },
    )

    /** A conversation in which each of [phrases] was translated and tried. */
    fun chat(phrases: List<QuizSource>): List<ChatItem> = phrases.flatMap { p ->
        listOf(ChatItem.User(p.english), ChatItem.Say(p.english, p.phrase), ChatItem.Attempt(p.phrase, "", result(p.phrase), p.phrase))
    }

    /** "pour pointer mon fusil" etc. as espeak-ng reads the phrase (from espeak-ng 1.51, as symbols). */
    fun espeak(ipa: String): List<List<String>> = ipa.split(' ').map(Phonemizer::symbols)

    val fillGap = ChoiceQuestion(QuizKind.FillGap, "Nous ___ jouer au parc.", "We're going to play at the park.", null, listOf("allez", "allons", "aller"), 1)
    val article = ChoiceQuestion(QuizKind.Article, "café", null, null, listOf("le", "la"), 0)
    val heard = ChoiceQuestion(QuizKind.Heard, "", null, "dessus", listOf("dessous", "dessus", "des sous"), 1)
    val phraseMeaning = ChoiceQuestion(
        QuizKind.PhraseMeaning, "", null, "Les amis sont arrivés hier soir.",
        listOf("The friends arrived last night.", "I'd like a coffee, please.", "We're going to play at the park."), 0,
    )
    val wordMeaning = ChoiceQuestion(QuizKind.WordMeaning, "voudrais", null, null, listOf("to arrive", "to want", "friend"), 1)
    val order = OrderQuestion(listOf("nous", "allons", "jouer", "au", "parc"), listOf(2, 0, 4, 1, 3), "We're going to play at the park.")
    val match = MatchQuestion(
        listOf("café", "amis", "soir", "parc", "jouer"), listOf("coffee", "friends", "evening", "park", "to play"), listOf(2, 0, 4, 3, 1),
    )
    val speak = SpeakQuestion("I'd like a coffee, please.", "Je voudrais un café, s'il vous plaît.")
    val mistake = MistakeQuestion("Nous allez jouer au parc.", 1, "allons", "We're going to play at the park.")
    val finals: FinalsQuestion = checkNotNull(
        QuizMaker.finals(
            QuizSource("Les amis sont arrivés hier soir.", "The friends arrived last night."),
            espeak("lez ami sɔ̃t aʁive jɛʁ swaʁ"),
        ) { i -> listOf(espeak("le"), espeak("ami"), espeak("sɔ̃"), espeak("aʁive"), espeak("jɛʁ"), espeak("swaʁ"))[i].first() },
    )

    val all: List<QuizQuestion> = listOf(fillGap, article, heard, phraseMeaning, wordMeaning, order, match, speak, mistake, finals)
}

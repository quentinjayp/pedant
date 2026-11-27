package com.pedant.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/** The quiz's logic: what it's made of, how Gemma's answers are checked, and how it's saved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuizTest {

    @Test
    fun oneQuestionForEveryTwoPhrasesUpToTen() {
        fun phrases(n: Int) = (1..n).map { QuizSource("Phrase numéro $it ici.", "Phrase $it") }
        assertEquals(0, Quiz.length(QuizSamples.chat(phrases(1))))
        assertEquals(1, Quiz.length(QuizSamples.chat(phrases(3))))
        assertEquals(4, Quiz.length(QuizSamples.chat(phrases(8))))
        assertEquals(10, Quiz.length(QuizSamples.chat(phrases(30))))
        val quiz = Quiz.start(QuizSamples.chat(phrases(8)), Mode.Beginner, Band.Basic, Random(1))
        assertEquals(4, quiz.plan.size)
        assertEquals("each question on a different phrase", 4, quiz.plan.toSet().size)
    }

    @Test
    fun onlyPhrasesThatWereTriedCount() {
        val chat = listOf(
            ChatItem.Say("Hello", "Bonjour"),
            ChatItem.Say("Thanks", "Merci"),
            ChatItem.Attempt("merci", "", QuizSamples.result("Merci"), "Merci"),
        )
        assertEquals(listOf(QuizSource("Merci", "Thanks")), Quiz.phrases(chat))
    }

    @Test
    fun orderAndSpeakKeepToTheBandsWordLimits() {
        val five = QuizSource("Nous allons jouer au parc.", "x")
        val six = QuizSource("Nous allons jouer au parc demain.", "x")
        assertNotNull(QuizMaker.speak(five, Quiz.BASIC_WORDS))
        assertNull(QuizMaker.speak(six, Quiz.BASIC_WORDS))
        assertNotNull(QuizMaker.speak(six, Quiz.INDEPENDENT_WORDS))
        assertNull(QuizMaker.order(six, Quiz.BASIC_WORDS))
        val order = checkNotNull(QuizMaker.order(six, Quiz.INDEPENDENT_WORDS))
        assertNotEquals("doesn't start in order", order.words, order.start.map { order.words[it] })
    }

    @Test
    fun orderPicksASentenceThatFits() {
        val source = QuizSource("Bonjour. Je voudrais un café noir, s'il vous plaît, avec du sucre.", "x")
        val order = checkNotNull(QuizMaker.order(QuizSource("Je suis là. " + source.phrase, "x"), Quiz.BASIC_WORDS))
        assertEquals("the first word's capital doesn't give it away", listOf("je", "suis", "là"), order.words)
        assertNull("no own line for one sentence of several", order.hint)
    }

    @Test
    fun orderKeepsCapitalsThatArentTheSentencesStart() {
        val german = checkNotNull(QuizMaker.order(QuizSource("Kaffee ist gut.", "x"), Quiz.BASIC_WORDS, capitalNouns = true))
        assertEquals(listOf("Kaffee", "ist", "gut"), german.words)
        val english = checkNotNull(QuizMaker.order(QuizSource("I like coffee.", "x"), Quiz.BASIC_WORDS))
        assertEquals(listOf("I", "like", "coffee"), english.words)
    }

    @Test
    fun phraseMeaningNeedsTwoOtherLines() {
        assertNull(QuizMaker.phraseMeaning(QuizSamples.phrases[0], QuizSamples.phrases.take(2)))
        val q = checkNotNull(QuizMaker.phraseMeaning(QuizSamples.phrases[0], QuizSamples.phrases))
        assertEquals(QuizSamples.phrases[0].english, q.options[q.answer])
        assertEquals(3, q.options.toSet().size)
    }

    @Test
    fun formsAreCheckedAgainstTheSentence() {
        val sentence = "Nous allons jouer au parc."
        assertEquals(1 to listOf("allez", "aller"), QuizMaker.parseForms("WORD: allons\nWRONG: allez | aller", sentence))
        assertEquals("the word isn't in the sentence", null, QuizMaker.parseForms("WORD: allez\nWRONG: vont | va", sentence))
        assertEquals("a wrong form that's the word itself is dropped", 1 to listOf("allez"), QuizMaker.parseForms("WORD: allons\nWRONG: Allons | allez", sentence))
        assertNull("no wrong forms", QuizMaker.parseForms("WORD: allons\nWRONG: allons", sentence))
        assertEquals("several words aren't one form", 1 to listOf("aller"), QuizMaker.parseForms("WORD: **allons**\nWRONG: nous allez | aller", sentence))
    }

    @Test
    fun alikeAndArticlesAreFiltered() {
        assertEquals(listOf("dessous", "dessert"), QuizMaker.parseAlike("ALIKE: dessous | dessus | dessert", "dessus"))
        assertEquals(
            listOf("café" to "le", "table" to "la"),
            QuizMaker.parseArticles("café | le\ntable | la\nchose | les\nNONE", listOf("le", "la")),
        )
    }

    @Test
    fun meaningsAreShortened() {
        assertEquals("to want", QuizMaker.shortMeaning("to want (verb)"))
        assertEquals("coffee", QuizMaker.shortMeaning("(noun) coffee; a café"))
        assertEquals("friend", QuizMaker.shortMeaning("friend.\nmore"))
    }

    @Test
    fun replaceWordKeepsTheRest() {
        assertEquals("Nous ___ jouer au parc.", QuizMaker.replaceWord("Nous allons jouer au parc.", 1, "___"))
    }

    @Test
    fun finalConsonantsSaidSilentAndCarriedOver() {
        val source = QuizSamples.phrases[2]
        val inPhrase = QuizSamples.espeak("ʒə dwa mə ʁɑ̃dʁ o sɑ̃tʁ də tiʁ puʁ pwɛ̃te mɔ̃ fyzi")
        val q = checkNotNull(QuizMaker.finals(source, inPhrase) { null })
        val words = wordsOf(source.phrase)
        val outcomes = q.candidates.map { words[it] }.zip(q.finals).toMap()
        assertEquals(
            mapOf(
                "dois" to FinalSound.Silent, "tir" to FinalSound.Said, "pour" to FinalSound.Said,
                "pointer" to FinalSound.Silent, "mon" to FinalSound.Silent, "fusil" to FinalSound.Silent,
            ),
            outcomes,
        )
        // "mon adresse": the n is silent alone and said here, carried onto "adresse".
        val liaison = checkNotNull(
            QuizMaker.finals(QuizSource("Je dois changer mon adresse.", "x"), QuizSamples.espeak("ʒə dwa ʃɑ̃ʒe mɔ̃n adʁɛs")) { QuizSamples.espeak("mɔ̃").first() },
        )
        assertEquals(listOf(FinalSound.CarriedOver), liaison.finals.filter { it != FinalSound.Silent })
    }

    @Test
    fun finalsNeedsSaidAndSilentWords() {
        assertNull(QuizMaker.finals(QuizSource("Il part.", "x"), QuizSamples.espeak("il paʁ")) { null })
        assertNull("espeak-ng read it as more words", QuizMaker.finals(QuizSource("Il part.", "x"), QuizSamples.espeak("il pa ʁ")) { null })
    }

    @Test
    fun answersAreJudged() {
        assertTrue(isRight(QuizSamples.fillGap, QuizAnswer.Chosen(1)))
        assertFalse(isRight(QuizSamples.fillGap, QuizAnswer.Chosen(0)))
        assertTrue(isRight(QuizSamples.order, QuizAnswer.Ordered(listOf(0, 1, 2, 3, 4))))
        assertTrue(isRight(QuizSamples.mistake, QuizAnswer.Tapped(1)))
        assertTrue(isRight(QuizSamples.finals, QuizAnswer.Picked(QuizSamples.finals.said)))
        assertFalse(isRight(QuizSamples.finals, QuizAnswer.Picked(emptySet())))
        assertFalse("an answer of the wrong kind", isRight(QuizSamples.fillGap, QuizAnswer.Tapped(1)))
    }

    @Test
    fun aQuizIsSavedAndLoadedWhole() {
        val quiz = Quiz(Mode.Intermediate, QuizSamples.phrases, listOf(0, 1, 2, 3), QuizSamples.all, position = 3, band = Band.Independent)
        assertEquals(quiz, Quiz.fromJson(quiz.toJson()))
        val saved = SavedConversation(
            "fr", Scenario.None, Register.Formal, QuizSamples.chat(QuizSamples.phrases), emptyList(), null, emptyList(), 0, null,
            quiz = quiz, quizOfferedAt = 2, band = Band.Independent,
        )
        val dir = kotlin.io.path.createTempDirectory().toFile()
        saved.save(dir)
        val loaded = checkNotNull(SavedConversation.load(dir))
        assertEquals(quiz, loaded.quiz)
        assertEquals(2, loaded.quizOfferedAt)
        assertEquals(Band.Independent, loaded.band)
    }

    @Test
    fun articleTitles() {
        assertEquals("Le or la?", articleTitle(listOf("le", "la")))
        assertEquals("Der, die or das?", articleTitle(listOf("der", "die", "das")))
    }
}

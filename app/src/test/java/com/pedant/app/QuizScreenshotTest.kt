package com.pedant.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the quiz's screens with sample questions, before and after answering, to PNG files in
 * build/outputs/roborazzi/quiz (when run with -Proborazzi.test.record=true, as the quiz-tests job
 * in android.yml does). Not compared against anything: they're there to be looked at.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class QuizScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val french get() = LANGUAGES.first { it.code == "fr" }

    /** The quiz on [question] (the second of three), answered [answer] if given. */
    private fun shot(name: String, question: QuizQuestion?, answer: QuizAnswer? = null, mode: Mode = Mode.Intermediate) {
        val vm = AppViewModel(RuntimeEnvironment.getApplication())
        val questions = listOfNotNull(QuizSamples.fillGap, question)
        val quiz = Quiz(mode, QuizSamples.phrases, listOf(0, 1, 2), questions, position = 1)
        vm.showQuizForTest(french, quiz, answer)
        compose.setContent {
            PedantTheme {
                Surface(Modifier.fillMaxSize(), color = Pastel.Bg, contentColor = Pastel.Ink) { vm.quiz?.let { QuizScreen(vm, it) } }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/quiz/$name.png")
    }

    @Test fun fillGap() = shot("01-fill-gap", QuizSamples.fillGap)
    @Test fun fillGapWrong() = shot("02-fill-gap-wrong", QuizSamples.fillGap, QuizAnswer.Chosen(0))
    @Test fun article() = shot("03-article", QuizSamples.article)
    @Test fun articleRight() = shot("04-article-right", QuizSamples.article, QuizAnswer.Chosen(0))
    @Test fun heard() = shot("05-heard", QuizSamples.heard)
    @Test fun phraseMeaning() = shot("06-phrase-meaning-wrong", QuizSamples.phraseMeaning, QuizAnswer.Chosen(2))
    @Test fun wordMeaning() = shot("07-word-meaning", QuizSamples.wordMeaning)
    @Test fun order() = shot("08-order", QuizSamples.order)
    @Test fun orderWrong() = shot("09-order-wrong", QuizSamples.order, QuizAnswer.Ordered(listOf(0, 2, 1, 3, 4)))
    @Test fun match() = shot("10-match", QuizSamples.match)
    @Test fun matchDone() = shot("11-match-done", QuizSamples.match, QuizAnswer.Matched)
    @Test fun speak() = shot("12-speak", QuizSamples.speak, mode = Mode.Beginner)
    @Test fun speakWrong() = shot(
        "13-speak-wrong", QuizSamples.speak, QuizAnswer.Spoken(QuizSamples.result(QuizSamples.speak.phrase, wrong = setOf(1, 3))),
    )
    @Test fun mistake() = shot("14-mistake", QuizSamples.mistake)
    @Test fun mistakeWrong() = shot("15-mistake-wrong", QuizSamples.mistake, QuizAnswer.Tapped(3))
    @Test fun finals() = shot("16-finals", QuizSamples.finals)
    @Test fun finalsChecked() = shot(
        "17-finals-checked", QuizSamples.finals, QuizAnswer.Picked(QuizSamples.finals.said - QuizSamples.finals.candidates.first()),
    )
    @Test fun preparing() = shot("18-preparing", null)

    @Test
    fun offer() {
        val vm = AppViewModel(RuntimeEnvironment.getApplication())
        val saved = SavedConversation(
            "fr", Scenario.byId("meal"), Register.Casual, QuizSamples.chat(QuizSamples.phrases), emptyList(), null, emptyList(), 0, null,
        )
        vm.showQuizForTest(french, null, offer = AppViewModel.QuizOffer(saved, fromConversation = false))
        compose.setContent {
            PedantTheme {
                Surface(Modifier.fillMaxSize(), color = Pastel.Bg, contentColor = Pastel.Ink) {
                    vm.quizOffer?.let { QuizOfferDialog(vm, it) }
                }
            }
        }
        captureScreenRoboImage("build/outputs/roborazzi/quiz/19-offer.png")
    }
}

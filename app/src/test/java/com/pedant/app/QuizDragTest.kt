package com.pedant.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Dragging a word onto another in a put-it-in-order question moves it there. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class QuizDragTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun dragMovesAWord() {
        val vm = AppViewModel(RuntimeEnvironment.getApplication())
        val question = QuizSamples.order // starts as: jouer nous parc allons au
        val quiz = Quiz(Mode.Intermediate, QuizSamples.phrases, listOf(0), listOf(question), position = 0)
        vm.showQuizForTest(LANGUAGES.first { it.code == "fr" }, quiz, null)
        compose.setContent {
            PedantTheme {
                Surface(Modifier.fillMaxSize(), color = Pastel.Bg, contentColor = Pastel.Ink) { vm.quiz?.let { QuizScreen(vm, it) } }
            }
        }
        val from = compose.onNodeWithText("jouer").fetchSemanticsNode().boundsInRoot.center
        val to = compose.onNodeWithText("nous").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput {
            down(from)
            repeat(20) { i -> moveTo(from + (to - from) * ((i + 1) / 20f)) }
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Check").performClick()
        compose.waitForIdle()
        val answer = vm.quizAnswer as QuizAnswer.Ordered
        assertEquals(listOf("nous", "jouer", "parc", "allons", "au"), answer.order.map { question.words[it] })
    }
}

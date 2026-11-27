package com.pedant.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws New conversation, with no language chosen yet, with one chosen, and with the menus open, to
 * PNG files in build/outputs/roborazzi/new-conversation (when run with -Proborazzi.test.record=true,
 * as the quiz-tests job in android.yml does). Not compared against anything: they're there to be
 * looked at.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class NewConversationScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(language: String? = null): AppViewModel {
        val vm = AppViewModel(RuntimeEnvironment.getApplication())
        language?.let { code -> vm.chooseNewLanguage(LANGUAGES.first { it.code == code }) }
        compose.setContent {
            PedantTheme {
                Surface(Modifier.fillMaxSize(), color = Pastel.Bg, contentColor = Pastel.Ink) { LanguageScreen(vm) }
            }
        }
        return vm
    }

    private fun capture(name: String) = captureScreenRoboImage("build/outputs/roborazzi/new-conversation/$name.png")

    @Test
    fun noLanguage() {
        show()
        capture("01-no-language")
    }

    @Test
    fun languageChosen() {
        show("fr")
        capture("02-language-chosen")
    }

    @Test
    fun scenarios() {
        show("fr")
        compose.onNodeWithText("No Scenario").performClick()
        capture("03-scenarios")
    }

    @Test
    fun twoCards() {
        val vm = show("fr")
        compose.runOnIdle { vm.chooseScenario(ScenarioChoice.TwoCard) }
        compose.runOnIdle { vm.cards.firstOrNull()?.let(vm::pickCard) }
        capture("06-two-cards")
    }

    @Test
    fun oneScenario() {
        val vm = show("fr")
        compose.runOnIdle { Scenario.forBand(Band.Basic).firstOrNull { it.id == "shop" }?.let { vm.chooseScenario(ScenarioChoice.One(it)) } }
        capture("07-one-scenario")
    }

    @Test
    fun languages() {
        show()
        compose.onNodeWithText("Choose a language").performClick()
        capture("04-languages")
    }

    @Test
    fun languagesSearched() {
        show()
        compose.onNodeWithText("Choose a language").performClick()
        compose.onNodeWithText("Search").performTextInput("sl")
        capture("05-languages-searched")
    }
}

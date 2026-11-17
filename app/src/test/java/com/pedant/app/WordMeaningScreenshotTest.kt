package com.pedant.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the word bubble's meanings: a long list, which shows the Scroll hint, and a short one,
 * which doesn't. Written with the quiz's screenshots (see QuizScreenshotTest).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class WordMeaningScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, meanings: List<String>) {
        loadLanguages(RuntimeEnvironment.getApplication())
        val lookup = WordLookup(
            "prendre", "Je vais prendre un café.",
            meaning = meanings.joinToString("\n"), groups = listOf(Dictionary.Group("verb", meanings)),
            sounds = "pʁɑ̃dʁ", loading = false, soundsGenerated = false,
        )
        compose.setContent {
            PedantTheme {
                Surface(Modifier.fillMaxSize(), color = Pastel.White, contentColor = Pastel.Ink) {
                    Column(Modifier.padding(16.dp)) {
                        WordMeaning(lookup, appBusy = false, own = LANGUAGES.first { it.code == "en" }, maxHeight = 240.dp)
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/quiz/20-$name.png")
    }

    @Test fun long() = shot(
        "meanings-long",
        listOf(
            "to take", "to eat; to drink", "to get; to buy", "to rob; to deprive", "to make",
            "to catch, to work, to start", "to get (something) caught (in), to jam", "to gain",
            "to take (a certain amount of time)", "to take on, to assume", "(archaic) to marry",
        ),
    )

    @Test fun short() = shot("meanings-short", listOf("to take", "to eat; to drink"))
}

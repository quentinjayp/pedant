package com.pedant.app

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

enum class Mode(val title: String, val description: String) {
    Learning("Learning", "Say what you want in English, then repeat it back in the language until you get it right."),
    Conversation("Conversation", "Talk freely in the language. Mistakes are explained in English."),
}

/**
 * One tutor answer, split into its labelled parts. [correction] and [reply] are used in
 * Conversation mode; [reply], [english] and [say] in Learning mode.
 */
data class TutorTurn(
    val correction: String? = null,
    val reply: String = "",
    val english: String? = null,
    val say: String? = null,
)

/** Gemma 4 E4B acting as a conversation partner that corrects grammar in English. */
class Tutor(context: Context, modelFile: File, useGpu: Boolean) {

    private val engine: Engine = Engine(
        EngineConfig(
            modelPath = modelFile.path,
            backend = if (useGpu) Backend.GPU() else Backend.CPU(),
            maxNumTokens = 2048,
            cacheDir = context.cacheDir.path,
        ),
    ).also { it.initialize() }
    private var conversation: Conversation? = null

    fun startConversation(language: Language, mode: Mode) {
        conversation?.close()
        val prompt = when (mode) {
            Mode.Conversation -> conversationPrompt(language)
            Mode.Learning -> learningPrompt(language)
        }
        conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(prompt),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
            ),
        )
    }

    /** Streams the tutor's raw answer to [userText] as it is generated (cumulative text). */
    fun respond(userText: String): Flow<String> {
        val conv = checkNotNull(conversation) { "No conversation started" }
        val sb = StringBuilder()
        return conv.sendMessageAsync(Message.user(userText)).map { chunk ->
            sb.append(chunk.toString())
            sb.toString()
        }
    }

    fun close() {
        conversation?.close()
        engine.close()
    }

    companion object {
        const val CONVERSATION_START =
            "[The learner has just started the conversation. Greet them and ask one simple question. " +
                "Write CORRECTION: none]"
        const val LEARNING_START = "[START]"
        fun learningTranslate(english: String) = "[TRANSLATE] $english"
        fun learningSaid(phrase: String) = "[SAID] $phrase"

        /** Formats what the learner said for Gemma: the words heard plus the sounds heard. */
        fun heardMessage(transcript: String, sounds: String) =
            "Transcript: $transcript\nSounds heard (IPA): $sounds"

        private fun conversationPrompt(language: Language) = """
            You are a friendly conversation partner helping the user practise ${language.englishName}.
            The user is a language learner talking to you by voice.
            Each of their messages gives two readings of the same recording:
            - Transcript: the words a speech recognizer heard, written exactly as spoken (lowercase, no
              punctuation, mistakes kept, sometimes misheard).
            - Sounds heard (IPA): the sounds a phonetic recognizer heard, with ▁ between words.
            Use both to work out what the user said. Never correct capitalization or punctuation.

            Always answer in exactly this format and nothing else:
            CORRECTION: <in English. If the user's last message had grammar or word-choice mistakes, give the
            corrected sentence in ${language.englishName} and a one-sentence explanation in English. If a word
            was clearly mispronounced, mention it briefly. If there were no mistakes, write: none>
            REPLY: <your reply in ${language.englishName} only. Keep it short and simple (one to three
            sentences) and usually end with a question to keep the conversation going.>
        """.trimIndent()

        private fun learningPrompt(language: Language) = """
            You are a friendly ${language.englishName} tutor running a speaking drill with an English speaker.
            You receive three kinds of messages:
            - [START]: greet the learner in ${language.englishName} with one short, simple sentence.
            - [TRANSLATE] <English>: the learner wants to say this. It may be a raw speech transcript
              without punctuation. Translate it into natural, simple ${language.englishName}.
            - [SAID] <phrase>: the learner has just said this phrase in ${language.englishName}. Reply to it
              naturally in ${language.englishName} to continue the conversation.

            Answer in exactly one of these formats and nothing else.
            For [START] and [SAID]:
            REPLY: <one or two short, simple sentences in ${language.englishName}>
            ENGLISH: <the English translation of your reply>
            For [TRANSLATE]:
            SAY: <the ${language.englishName} translation only>
        """.trimIndent()

        private val LABELS = listOf("CORRECTION:", "REPLY:", "ENGLISH:", "SAY:")

        /** Splits the raw answer into its labelled parts. Works on partial (streaming) text too. */
        fun parse(raw: String): TutorTurn {
            val found = LABELS.mapNotNull { label -> raw.indexOf(label).takeIf { it >= 0 }?.let { label to it } }
                .sortedBy { it.second }
            fun part(label: String): String? {
                val i = found.indexOfFirst { it.first == label }
                if (i < 0) return null
                val start = found[i].second + label.length
                val end = found.getOrNull(i + 1)?.second ?: raw.length
                return raw.substring(start, end).trim()
            }
            return TutorTurn(
                correction = part("CORRECTION:")
                    ?.takeUnless { it.isEmpty() || it.trimEnd('.').equals("none", ignoreCase = true) },
                reply = part("REPLY:") ?: "",
                english = part("ENGLISH:")?.ifEmpty { null },
                say = part("SAY:")?.ifEmpty { null },
            )
        }
    }
}

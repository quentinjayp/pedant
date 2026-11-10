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
import kotlinx.coroutines.CancellationException
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
class Tutor(context: Context, modelFile: File) {

    // The CPU loads faster and responds better than the GPU.
    private val engine: Engine = Engine(
        EngineConfig(
            modelPath = modelFile.path,
            // Four threads leave the phone's other cores free for the screen and the system.
            backend = Backend.CPU(threadCount = 4),
            maxNumTokens = 2048,
            cacheDir = context.cacheDir.path,
        ),
    ).also { it.initialize() }
    private var conversation: Conversation? = null

    /**
     * Starts a new conversation. With [opening] (Gemma's saved answer to [startMessage]), the
     * conversation begins as if Gemma had just said it, so there is nothing to generate.
     */
    fun startConversation(language: Language, mode: Mode, scenario: Scenario, opening: String? = null) {
        conversation?.close()
        conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemPrompt(language, mode, scenario)),
                initialMessages = opening?.let { listOf(Message.user(startMessage(mode)), Message.model(it)) }.orEmpty(),
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

    /** The lookup conversation in progress, so it can be stopped. */
    @Volatile private var lookupConversation: Conversation? = null

    /** Looks up one [word] as used in [sentence]. Returns the raw answer; see [parseLookup]. */
    suspend fun lookUp(language: Language, word: String, sentence: String): String =
        ask(lookupPrompt(language), "Word: $word\nSentence: $sentence")

    /** Explains every word of [sentence] at once. Returns the raw answer; see [parseSentenceLookup]. */
    suspend fun lookUpSentence(language: Language, sentence: String): String =
        ask(sentenceLookupPrompt(language), sentence)

    /**
     * Asks a one-off question in its own short conversation, so the main one is left untouched.
     * Stops early if the coroutine is cancelled.
     */
    private suspend fun ask(instruction: String, question: String): String {
        val conv = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(instruction),
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
            ),
        )
        lookupConversation = conv
        try {
            val answer = StringBuilder()
            conv.sendMessageAsync(Message.user(question)).collect { answer.append(it.toString()) }
            return answer.toString()
        } catch (e: CancellationException) {
            conv.cancelProcess()
            throw e
        } finally {
            lookupConversation = null
            conv.close()
        }
    }

    /** Stops the main conversation's reply in progress. */
    fun cancelGeneration() {
        conversation?.cancelProcess()
    }

    /** Stops a lookup in progress so the main conversation can use Gemma. */
    fun cancelLookup() {
        lookupConversation?.cancelProcess()
    }

    fun close() {
        conversation?.close()
        engine.close()
    }

    companion object {
        /** The message that asks Gemma to open the conversation. */
        fun startMessage(mode: Mode) = when (mode) {
            Mode.Conversation -> "[START] Write CORRECTION: none"
            Mode.Learning -> "[START]"
        }

        private fun systemPrompt(language: Language, mode: Mode, scenario: Scenario) = when (mode) {
            Mode.Conversation -> conversationPrompt(language)
            Mode.Learning -> learningPrompt(language)
        } + "\n\n" + scenarioPrompt(language, scenario)

        /** Names the saved opening for these choices; it changes whenever the instructions change. */
        fun openingKey(language: Language, mode: Mode, scenario: Scenario): String {
            val hash = (systemPrompt(language, mode, scenario) + startMessage(mode)).hashCode()
            return "${language.code}-${mode.name}-${scenario.name}-${Integer.toHexString(hash)}"
        }
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

        /** How to open the conversation, and who to play in a scenario. */
        private fun scenarioPrompt(language: Language, scenario: Scenario): String {
            val role = scenario.role
            return if (role == null) {
                "When you receive [START], open the conversation by asking, in ${language.englishName}: " +
                    "\"Are you ready to learn some ${language.englishName}? What would you like to talk about today?\""
            } else {
                "Role-play: $role Stay in character as that person throughout, speaking naturally. " +
                    "When you receive [START], open the scene in character with one or two short, simple sentences."
            }
        }

        private fun learningPrompt(language: Language) = """
            You are a friendly ${language.englishName} tutor running a speaking drill with an English speaker.
            You receive three kinds of messages:
            - [START]: open the conversation in ${language.englishName} as described below.
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

        private fun lookupPrompt(language: Language) = """
            You explain ${language.englishName} words to an English-speaking learner.
            You get a word and the sentence it appears in. Answer in exactly this format and nothing else:
            MEANING: <a short English definition of the word as used in this sentence, with its part of speech>
        """.trimIndent()

        private fun sentenceLookupPrompt(language: Language) = """
            You explain ${language.englishName} words to an English-speaking learner.
            You get a ${language.englishName} sentence. For every word in it, in order, write one line in
            exactly this format and nothing else:
            word | short English meaning of the word as used in this sentence, with its part of speech
        """.trimIndent()

        /** Splits a sentence lookup into word (lower case) → meaning. */
        fun parseSentenceLookup(raw: String): Map<String, String> =
            raw.lines().mapNotNull { line ->
                val parts = line.split('|').map { it.trim().trim('*', '-', '•').trim() }
                if (parts.size < 2 || parts[0].isEmpty() || parts[1].isEmpty()) null
                else parts[0].lowercase() to parts[1]
            }.toMap()

        /** The meaning from a single-word lookup answer. */
        fun parseLookup(raw: String): String? =
            raw.substringAfter("MEANING:", "").trim().lines().firstOrNull()?.trim()?.ifEmpty { null }

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

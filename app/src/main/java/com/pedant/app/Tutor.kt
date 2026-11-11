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
import kotlinx.coroutines.flow.onCompletion
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

    /** One message in the main conversation: from the learner, or Gemma's answer. */
    data class Turn(val fromLearner: Boolean, val text: String)

    private var mode = Mode.Learning
    private val turns = mutableListOf<Turn>()

    /**
     * What has been said in the main conversation, in the language being learned only (no English
     * translations, corrections or sounds), so it can be picked up again later.
     */
    val history: List<Turn> get() = synchronized(turns) { turns.toList() }

    /**
     * Starts a new conversation. [earlier] (a saved opening, or a conversation being continued) is
     * given to Gemma as already said: as much of the most recent part as fits.
     */
    fun startConversation(language: Language, mode: Mode, scenario: Scenario, earlier: List<Turn> = emptyList()) {
        conversation?.close()
        conversation = null
        this.mode = mode
        val kept = fitting(earlier.map { if (it.fromLearner) it else Turn(false, compact(it.text)) })
        synchronized(turns) {
            turns.clear()
            turns += kept
        }
        val key = openingKey(language, mode, scenario) + "|" + kept.hashCode()
        val base = ready?.takeIf { it.first == key }?.second ?: run {
            ready?.second?.close()
            ready = null
            // Gemma reads the instructions (and what was said before) now, not on the first message.
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt(language, mode, scenario)),
                    initialMessages = kept.map { if (it.fromLearner) Message.user(it.text) else Message.model(it.text) },
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
                    prefillPrefaceOnInit = true,
                ),
            ).also { ready = key to it }
        }
        conversation = base.clone()
    }

    /** The most recent [turns] that fit Gemma's memory, starting with one of the learner's. */
    private fun fitting(turns: List<Turn>): List<Turn> {
        var total = 0
        val kept = turns.asReversed().takeWhile { total += it.text.length; total <= HISTORY_CHARS }.asReversed()
        return kept.dropWhile { !it.fromLearner }
    }

    /** Gemma's answer reduced to its reply in the language being learned. */
    private fun compact(raw: String): String = "REPLY: ${parse(raw).reply}"

    /**
     * The last conversation's start, with the instructions already read, kept so starting the same
     * choices again skips that step. Only one is kept: each holds memory.
     */
    private var ready: Pair<String, Conversation>? = null

    /**
     * Streams the tutor's raw answer to [userText] as it is generated (cumulative text). Once the
     * answer is complete, [remember] (what to keep of the message; [userText] by default) and the
     * reply are added to [history].
     */
    fun respond(userText: String, remember: String = userText): Flow<String> {
        val conv = checkNotNull(conversation) { "No conversation started" }
        val sb = StringBuilder()
        return conv.sendMessageAsync(Message.user(userText))
            .map { chunk ->
                sb.append(chunk.toString())
                sb.toString()
            }
            .onCompletion { error ->
                if (error == null) synchronized(turns) {
                    turns += Turn(true, remember)
                    turns += Turn(false, compact(sb.toString()))
                }
            }
    }

    /**
     * Translates what the learner wants to say, in its own short conversation so the main one only
     * ever holds the language being learned. [onText] gets the answer as it is written.
     */
    suspend fun translate(language: Language, english: String, onText: (String) -> Unit): String =
        ask(translatePrompt(language), english, onText)

    /**
     * Translates Gemma's [reply] into English, in its own short conversation: the English is written
     * fresh each time and never kept in the main conversation. See [parse] for the answer's parts.
     */
    suspend fun translateReply(language: Language, reply: String, onText: (String) -> Unit): String =
        ask(replyTranslatePrompt(language), reply, onText)

    /** Checks what the learner said (see [heardMessage]) for mistakes, in its own short conversation. */
    suspend fun checkSaid(language: Language, message: String, onText: (String) -> Unit): String =
        ask(correctionPrompt(language), message, onText)

    /** The one-off conversation in progress (a lookup or translation), so it can be stopped. */
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
    private suspend fun ask(instruction: String, question: String, onText: (String) -> Unit = {}): String {
        val conv = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(instruction),
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
            ),
        )
        lookupConversation = conv
        try {
            val answer = StringBuilder()
            conv.sendMessageAsync(Message.user(question)).collect {
                answer.append(it.toString())
                onText(answer.toString())
            }
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
        ready?.second?.close()
        engine.close()
    }

    companion object {
        /**
         * How much earlier conversation Gemma is given, in characters: roughly half of its 2,048
         * tokens, leaving room for the instructions and the next reply.
         */
        private const val HISTORY_CHARS = 2500

        /** The message that asks Gemma to open the conversation. */
        fun startMessage(mode: Mode) = when (mode) {
            Mode.Conversation -> "[START]"
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
            REPLY: <your reply in ${language.englishName} only. Keep it short and simple (one to three
            sentences) and usually end with a question to keep the conversation going.>
        """.trimIndent()

        private fun correctionPrompt(language: Language) = """
            You check what a learner of ${language.englishName} said by voice. You get two readings of the
            same recording:
            - Transcript: the words a speech recognizer heard, written exactly as spoken (lowercase, no
              punctuation, mistakes kept, sometimes misheard).
            - Sounds heard (IPA): the sounds a phonetic recognizer heard, with ▁ between words.
            Use both to work out what the learner said. Never correct capitalization or punctuation.
            Answer in exactly this format and nothing else:
            CORRECTION: <in English. If there were grammar or word-choice mistakes, give the corrected
            sentence in ${language.englishName} and a one-sentence explanation in English. If a word was
            clearly mispronounced, mention it briefly. If there were no mistakes, write: none>
        """.trimIndent()

        private fun replyTranslatePrompt(language: Language) = """
            You translate ${language.englishName} into natural, simple English.
            Answer in exactly this format and nothing else:
            ENGLISH: <the English translation only>
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
            The learner says something in English, gets it translated, and practises saying it. You
            receive two kinds of messages:
            - [START]: open the conversation in ${language.englishName} as described below.
            - [SAID] <phrase>: the learner has just said this phrase in ${language.englishName}. Reply to it
              naturally in ${language.englishName} to continue the conversation.

            Answer in exactly this format and nothing else:
            REPLY: <one or two short, simple sentences in ${language.englishName}>
        """.trimIndent()

        private fun translatePrompt(language: Language) = """
            You translate what an English speaker wants to say into natural, simple ${language.englishName}.
            The English may be a raw speech transcript without punctuation.
            Answer in exactly this format and nothing else:
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

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


/** One tutor answer, split into its labelled parts: the reply, its English, or a phrase to say. */
data class TutorTurn(
    val reply: String = "",
    val english: String? = null,
    val say: String? = null,
)

/** Gemma 4 (E4B or E2B, chosen in Settings): the tutor that talks with the learner, and translates and explains for them. */
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

    private val turns = mutableListOf<Turn>()

    /**
     * What has been said in the main conversation, in the language being learned only (no English
     * translations), so it can be picked up again later.
     */
    val history: List<Turn> get() = synchronized(turns) { turns.toList() }

    /**
     * Starts a new conversation. [earlier] (a saved opening, or a conversation being continued) is
     * given to Gemma as already said: as much of the most recent part as fits.
     */
    fun startConversation(language: Language, scenario: Scenario, earlier: List<Turn> = emptyList()) {
        conversation?.close()
        conversation = null
        val kept = fitting(earlier.map { if (it.fromLearner) it else Turn(false, compact(it.text)) })
        synchronized(turns) {
            turns.clear()
            turns += kept
        }
        val key = openingKey(language, scenario) + "|" + kept.hashCode()
        val base = ready?.takeIf { it.first == key }?.second ?: run {
            ready?.second?.close()
            ready = null
            // Gemma reads the instructions (and what was said before) now, not on the first message.
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt(language, scenario)),
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
    suspend fun translate(language: Language, register: Register, english: String, onText: (String) -> Unit): String =
        ask(Instructions.translate(language, register), english, onText)

    /**
     * Translates Gemma's [reply] into English, in its own short conversation: the English is written
     * fresh each time and never kept in the main conversation. See [parse] for the answer's parts.
     */
    suspend fun translateReply(language: Language, reply: String, onText: (String) -> Unit): String =
        ask(Instructions.replyTranslate(language), reply, onText)


    /** The one-off conversation in progress (a lookup or translation), so it can be stopped. */
    @Volatile private var lookupConversation: Conversation? = null

    /** Looks up one [word] as used in [sentence]. Returns the raw answer; see [parseLookup]. */
    suspend fun lookUp(language: Language, word: String, sentence: String): String =
        ask(Instructions.lookup(language), "Word: $word\nSentence: $sentence")

    /** Explains every word of [sentence] at once. Returns the raw answer; see [parseSentenceLookup]. */
    suspend fun lookUpSentence(language: Language, sentence: String): String =
        ask(Instructions.sentenceLookup(language), sentence)

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
        fun startMessage() = Instructions.start

        private fun systemPrompt(language: Language, scenario: Scenario) = Instructions.system(language, scenario)

        /**
         * Names the opening for these choices; it changes whenever the instructions change.
         * tools/build_openings.py names the openings that ship with the app the same way.
         */
        fun openingKey(language: Language, scenario: Scenario): String {
            val hash = (systemPrompt(language, scenario) + startMessage()).hashCode()
            // "Learning" stays in the name, as before Conversation mode was removed, so shipped and
            // saved openings still match.
            return "${language.code}-Learning-${scenario.name}-${Integer.toHexString(hash)}"
        }

        fun learningSaid(phrase: String, register: Register) = Instructions.said(phrase, register)










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

        private val LABELS = listOf("REPLY:", "ENGLISH:", "SAY:")

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
                reply = part("REPLY:") ?: "",
                english = part("ENGLISH:")?.ifEmpty { null },
                say = part("SAY:")?.ifEmpty { null },
            )
        }
    }
}

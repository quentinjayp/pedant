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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext
import java.io.File

/** One tutor answer, split into its labelled parts: the reply, its translation into the learner's own language, or a phrase to say. */
data class TutorTurn(
    val reply: String = "",
    val translation: String? = null,
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

    /** The learner's own language in the conversation under way: what translations and explanations are in. */
    private var own: Language = LANGUAGES.first { it.code == DEFAULT_OWN }

    /** The conversation's band, which its translations follow too. */
    private var band: Band = Band.Basic

    /**
     * What has been said in the main conversation, in the language being learned only (no English
     * translations), so it can be picked up again later.
     */
    val history: List<Turn> get() = synchronized(turns) { turns.toList() }

    /**
     * Starts a new conversation. [earlier] (a saved opening, or a conversation being continued) is
     * given to Gemma as already said: as much of the most recent part as fits.
     */
    fun startConversation(language: Language, scenario: Scenario, own: Language, band: Band, earlier: List<Turn> = emptyList()) {
        this.own = own
        this.band = band
        conversation?.close()
        conversation = null
        val kept = fitting(earlier.map { if (it.fromLearner) it else Turn(false, compact(it.text)) })
        synchronized(turns) {
            turns.clear()
            turns += kept
        }
        // The instructions don't depend on formal or casual (each message carries it), so neither does this.
        val key = "${language.code}|${own.code}|${scenario.name}|${band.name}|${kept.hashCode()}"
        val base = ready?.takeIf { it.first == key }?.second ?: run {
            ready?.second?.close()
            ready = null
            // Gemma reads the instructions (and what was said before) now, not on the first message.
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(Instructions.system(language, scenario, own, band)),
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
     * answer is complete, the message and the reply are added to [history].
     */
    fun respond(userText: String): Flow<String> {
        val conv = checkNotNull(conversation) { "No conversation started" }
        val sb = StringBuilder()
        return conv.sendMessageAsync(Message.user(userText))
            .map { chunk ->
                sb.append(chunk.toString())
                sb.toString()
            }
            .onCompletion { error ->
                if (error == null) synchronized(turns) {
                    turns += Turn(true, userText)
                    turns += Turn(false, compact(sb.toString()))
                }
            }
            // Gemma's work stays off the main thread; only the collector sees each step on it.
            .flowOn(Dispatchers.Default)
    }

    /**
     * Translates what the learner wants to say, in its own short conversation so the main one only
     * ever holds the language being learned. [onText] gets the answer as it is written.
     */
    suspend fun translate(language: Language, register: Register, said: String, onText: (String) -> Unit): String =
        ask(Instructions.translate(language, register, own, band), said, onText)

    /**
     * Translates Gemma's [reply] into the learner's own language, in its own short conversation: the
     * translation is written fresh each time and never kept in the main conversation. See
     * [parseTranslation].
     */
    suspend fun translateReply(language: Language, reply: String, onText: (String) -> Unit): String =
        ask(Instructions.replyTranslate(language, own), reply, onText)


    /** The one-off conversation in progress (a lookup or translation), so it can be stopped. */
    @Volatile private var lookupConversation: Conversation? = null

    /**
     * Looks up one [word] as used in [sentence], explained in [own] (the conversation's own language
     * unless given). Returns the raw answer; see [parseLookup].
     */
    suspend fun lookUp(language: Language, word: String, sentence: String, own: Language = this.own): String =
        ask(Instructions.lookup(language, own), "Word: $word\nSentence: $sentence")

    /** For the quiz: a word of [sentence] and wrong forms of it. Returns the raw answer; see [QuizMaker.parseForms]. */
    suspend fun quizForms(language: Language, own: Language, sentence: String): String =
        ask(Instructions.quizForms(language, own), sentence)

    /** For the quiz: words that look or sound like [word]. Returns the raw answer; see [QuizMaker.parseAlike]. */
    suspend fun quizAlike(language: Language, own: Language, word: String): String =
        ask(Instructions.quizAlike(language, own), word)

    /** For the quiz: the nouns of [sentence] and their articles. Returns the raw answer; see [QuizMaker.parseArticles]. */
    suspend fun quizArticles(language: Language, own: Language, sentence: String): String =
        ask(Instructions.quizArticles(language, own), sentence)

    /** Looks for a common expression in [phrase]. Returns the raw answer; see [parseExpression]. */
    suspend fun expression(language: Language, phrase: String): String =
        ask(Instructions.expression(language, own), phrase)

    /** Explains every word of [sentence] at once. Returns the raw answer; see [parseSentenceLookup]. */
    suspend fun lookUpSentence(language: Language, sentence: String): String =
        ask(Instructions.sentenceLookup(language, own), sentence)

    /**
     * Asks a one-off question in its own short conversation, so the main one is left untouched.
     * Stops early if the coroutine is cancelled.
     */
    private suspend fun ask(instruction: String, question: String, onText: (String) -> Unit = {}): String {
        // Setting up and closing a conversation reserves and frees memory, which can take seconds when
        // the phone is short of it, so neither is done on the main thread, where it would hold up the
        // screen and touches (the talk button). Not cancellable, so a conversation made is always closed.
        val conv = withContext(NonCancellable + Dispatchers.Default) {
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(instruction),
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                ),
            )
        }
        lookupConversation = conv
        try {
            currentCoroutineContext().ensureActive()
            val answer = StringBuilder()
            conv.sendMessageAsync(Message.user(question)).flowOn(Dispatchers.Default).collect {
                answer.append(it.toString())
                onText(answer.toString())
            }
            return answer.toString()
        } catch (e: CancellationException) {
            conv.cancelProcess()
            throw e
        } finally {
            lookupConversation = null
            withContext(NonCancellable + Dispatchers.Default) { conv.close() }
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

        /**
         * Names the opening for these choices; it changes whenever the instructions change, and
         * differs for each own language (the openings that ship with the app are for English).
         * tools/build_openings.py names the openings that ship with the app the same way.
         */
        fun openingKey(language: Language, scenario: Scenario, register: Register, own: Language, band: Band): String {
            val hash = (Instructions.system(language, scenario, own, band) + Instructions.start(register)).hashCode()
            return "${language.code}-${scenario.name}-${register.name}-${band.name}-${Integer.toHexString(hash)}"
        }

        /** Splits a sentence lookup into word (lower case) → meaning. */
        fun parseSentenceLookup(raw: String): Map<String, String> =
            raw.lines().mapNotNull { line ->
                val parts = line.split('|').map { it.trim().trim('*', '-', '•').trim() }
                if (parts.size < 2 || parts[0].isEmpty() || parts[1].isEmpty()) null
                else parts[0].lowercase() to parts[1]
            }.toMap()

        /**
         * The expression and its note in an answer to [expression], or null if there's none, or if
         * the expression it names isn't in [phrase] (Gemma made it up).
         */
        fun parseExpression(raw: String, phrase: String): TutorNote? {
            val expression = raw.substringAfter("EXPRESSION:", "").substringBefore("NOTE:").trim()
                .trim('"', '“', '”', '«', '»', '\'').trim()
            val note = raw.substringAfter("NOTE:", "").trim()
            if (expression.isEmpty() || note.isEmpty()) return null
            if (Matching.normalize(expression) !in Matching.normalize(phrase)) return null
            return TutorNote(expression, note)
        }

        /** The meaning from a single-word lookup answer. */
        fun parseLookup(raw: String): String? =
            raw.substringAfter("MEANING:", "").trim().lines().firstOrNull()?.trim()?.ifEmpty { null }

        private val LABELS = listOf("REPLY:", "TRANSLATION:", "SAY:")

        /** A short label at the start of an answer, however it's written ("САЙ:", "Spre:"). */
        private val LOOSE_LABEL = Regex("^[\\p{L}\\p{N}_]{1,10}\\s*:\\s*")

        /**
         * The phrase in a finished translation answer. Gemma sometimes writes the SAY: label another
         * way (E2B writes "САЙ:" in Russian and Ukrainian) or leaves it out. The answer is then taken
         * as the phrase, without a short label at its start. Only for finished answers: one still
         * being written would show the label's first letters as the phrase.
         */
        fun parseSay(raw: String): String = parse(raw).say ?: raw.trim().replace(LOOSE_LABEL, "").trim()

        /**
         * The translation in a finished answer to [translateReply]: the text after TRANSLATION:, or,
         * when the label is left out or written another way, the answer without a short label at
         * its start (E2B leaves it out often when the learner's own language isn't English).
         */
        fun parseTranslation(raw: String): String? =
            parse(raw).translation ?: raw.trim().replace(LOOSE_LABEL, "").trim().ifEmpty { null }

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
                translation = part("TRANSLATION:")?.ifEmpty { null },
                say = part("SAY:")?.ifEmpty { null },
            )
        }
    }
}

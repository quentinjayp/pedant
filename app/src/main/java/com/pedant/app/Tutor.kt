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

/** One tutor turn, split into the English correction and the reply in the target language. */
data class TutorTurn(val correction: String?, val reply: String)

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

    fun startConversation(language: Language) {
        conversation?.close()
        conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemPrompt(language)),
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
        const val GREETING_REQUEST =
            "[The learner has just started the conversation. Greet them and ask one simple question. " +
                "Write CORRECTION: none]"

        private fun systemPrompt(language: Language) = """
            You are a friendly conversation partner helping the user practise ${language.englishName}.
            The user is a language learner talking to you by voice.
            Their messages are raw speech-to-text transcripts: lowercase, without punctuation, and possibly
            with small transcription errors. Never correct capitalization or punctuation.

            Always answer in exactly this format and nothing else:
            CORRECTION: <in English. If the user's last message had grammar or word-choice mistakes, give the
            corrected sentence in ${language.englishName} and a one-sentence explanation in English. If there
            were no mistakes, write: none>
            REPLY: <your reply in ${language.englishName} only. Keep it short and simple (one to three
            sentences) and usually end with a question to keep the conversation going.>
        """.trimIndent()

        /** Splits the raw answer into its CORRECTION and REPLY parts. Works on partial text too. */
        fun parse(raw: String): TutorTurn {
            val replyIndex = raw.indexOf("REPLY:")
            val correctionPart = if (replyIndex >= 0) raw.substring(0, replyIndex) else raw
            val correction = correctionPart.substringAfter("CORRECTION:", "").trim()
                .takeUnless { it.isEmpty() || it.trimEnd('.').equals("none", ignoreCase = true) }
            val reply = if (replyIndex >= 0) raw.substring(replyIndex + "REPLY:".length).trim() else ""
            return TutorTurn(correction, reply)
        }
    }
}

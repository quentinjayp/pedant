package com.pedant.app

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Gemma's opening line for each language, mode and scenario, with Piper's audio of it, saved so
 * a new conversation can start without waiting for Gemma. The same choices always open the same way.
 */
class OpeningCache(parent: File) {

    private val dir = File(parent, "openings")

    /** Gemma's raw opening answer for [key], if saved. */
    fun text(key: String): String? = File(dir, "$key.txt").takeIf { it.exists() }?.readText()?.ifBlank { null }

    fun saveText(key: String, raw: String) = write(File(dir, "$key.txt"), raw.toByteArray())

    /** The opening's audio in [voice], if saved. */
    fun audio(key: String, voice: String): Audio? {
        val file = File(dir, "$key-$voice.pcm").takeIf { it.exists() } ?: return null
        val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val rate = buf.int
        val samples = FloatArray(buf.remaining() / 4).also { buf.asFloatBuffer().get(it) }
        return Audio(samples, rate)
    }

    fun saveAudio(key: String, voice: String, audio: Audio) {
        val buf = ByteBuffer.allocate(4 + audio.samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(audio.sampleRate)
        buf.asFloatBuffer().put(audio.samples)
        write(File(dir, "$key-$voice.pcm"), buf.array())
    }

    /** Writes to a temporary file first, so a crash never leaves half a file behind. */
    private fun write(file: File, bytes: ByteArray) {
        dir.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(file)
    }
}

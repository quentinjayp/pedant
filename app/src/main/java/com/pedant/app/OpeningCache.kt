package com.pedant.app

import android.content.res.AssetManager
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Gemma's opening line for each language, mode and scenario, with Piper's audio of it, so a new
 * conversation can start without waiting for Gemma. The same choices always open the same way.
 *
 * The openings for the built-in scenarios ship with the app (assets/openings.json, written by
 * tools/build_openings.py). Any other opening is written on the phone the first time and saved.
 */
class OpeningCache(parent: File, private val assets: AssetManager) {

    private val dir = File(parent, "openings")

    /** The openings that ship with the app, by key; see [Tutor.Companion.openingKey]. */
    private val bundled: JSONObject by lazy {
        runCatching {
            JSONObject(assets.open("openings.json").bufferedReader().use { it.readText() }).getJSONObject("openings")
        }.getOrDefault(JSONObject())
    }

    /** Gemma's raw opening answer for [key]: the one shipped with the app, or one saved on the phone. */
    fun text(key: String): String? =
        bundled.optString(key).ifBlank { null }
            ?: File(dir, "$key.txt").takeIf { it.exists() }?.readText()?.ifBlank { null }

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

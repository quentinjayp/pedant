package com.pedant.app

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val SAMPLE_RATE = 16_000

/** Records 16 kHz mono audio from the microphone while the talk button is held. */
class MicRecorder {
    @Volatile private var recording = false
    private var thread: Thread? = null
    private val buffer = ByteArrayOutputStream()

    @SuppressLint("MissingPermission")
    fun start() {
        if (recording) return
        buffer.reset()
        val minSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minSize * 4,
        )
        recording = true
        thread = Thread {
            try {
                val chunk = ByteArray(minSize)
                record.startRecording()
                while (recording) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n > 0) synchronized(buffer) { buffer.write(chunk, 0, n) }
                }
                record.stop()
            } catch (_: Exception) {
                // The microphone was unavailable; an empty recording is returned.
            } finally {
                record.release()
            }
        }.also { it.start() }
    }

    /** Stops recording and returns the audio as floats in [-1, 1]. */
    fun stop(): FloatArray {
        recording = false
        thread?.join()
        thread = null
        val bytes = synchronized(buffer) { buffer.toByteArray() }
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
    }
}

/** Speech-to-text with Omnilingual ASR. Writes down exactly what it hears, mistakes included. */
class SpeechToText(store: ModelStore) {
    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                omnilingual = OfflineOmnilingualAsrCtcModelConfig(model = store.asrModel.path),
                tokens = store.asrTokens.path,
                numThreads = 4,
            ),
        ),
    )

    fun transcribe(samples: FloatArray): String {
        if (samples.size < SAMPLE_RATE / 4) return ""
        val stream = recognizer.createStream()
        stream.acceptWaveform(samples, SAMPLE_RATE)
        recognizer.decode(stream)
        val text = recognizer.getResult(stream).text.trim()
        stream.release()
        return text
    }

    fun release() = recognizer.release()
}

/** Text-to-speech with a Piper voice (espeak-ng turns the text into sounds first). */
class TextToSpeech(store: ModelStore, voice: String) {
    private val tts = OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = store.voiceModel(voice).path,
                    tokens = store.voiceTokens(voice).path,
                    dataDir = store.espeakData.path,
                ),
                numThreads = 2,
            ),
        ),
    )

    /** Speaks [text] and returns once playback has finished. */
    fun speak(text: String) {
        if (text.isBlank()) return
        val audio = tts.generate(text, sid = 0, speed = 1.0f)
        if (audio.samples.isEmpty()) return
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(audio.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(audio.samples.size * 4)
            .build()
        track.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)
        track.play()
        val durationMs = audio.samples.size * 1000L / audio.sampleRate
        Thread.sleep(durationMs + 150)
        track.stop()
        track.release()
    }

    fun release() = tts.release()
}

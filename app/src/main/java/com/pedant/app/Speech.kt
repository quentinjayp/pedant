package com.pedant.app

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val SAMPLE_RATE = 16_000

/** Audio as floats in [-1, 1] at [sampleRate]. */
class Audio(val samples: FloatArray, val sampleRate: Int)

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

/** A sherpa-onnx recognizer for [model] using two threads. */
private fun recognizer(model: OfflineModelConfig) =
    OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = model.apply { numThreads = 2 }))

/** Runs [recognizer] on [samples] and returns its result. */
private fun OfflineRecognizer.recognize(samples: FloatArray, sampleRate: Int): OfflineRecognizerResult {
    val stream = createStream()
    try {
        stream.acceptWaveform(samples, sampleRate)
        decode(stream)
        return getResult(stream)
    } finally {
        stream.release()
    }
}

/** Speech-to-text with Omnilingual ASR. Writes down exactly what it hears, mistakes included. */
class SpeechToText(store: ModelStore) {
    private val recognizer = recognizer(
        OfflineModelConfig(
            omnilingual = OfflineOmnilingualAsrCtcModelConfig(model = store.asrModel.path),
            tokens = store.asrTokens.path,
        ),
    )

    fun transcribe(samples: FloatArray): String =
        if (samples.size < SAMPLE_RATE / 4) "" else recognizer.recognize(samples, SAMPLE_RATE).text.trim()

    fun release() = recognizer.release()
}

/**
 * ZIPA: hears the sounds (IPA phonemes) that were actually spoken, without knowing any words.
 * Returns ZIPA's tokens, with "▁" between words.
 */
class SoundRecognizer(store: ModelStore) {
    private val recognizer = recognizer(
        OfflineModelConfig(
            zipformerCtc = OfflineZipformerCtcModelConfig(model = store.zipaModel.path),
            tokens = store.zipaTokens.path,
        ),
    )

    fun sounds(audio: Audio): List<String> {
        if (audio.samples.isEmpty()) return emptyList()
        // ZIPA misses sounds right at the start of a clip with no lead-in (as in Piper's audio) and
        // fails on very short clips, so add silence before and after, and pad to at least a second.
        val margin = audio.sampleRate / 4
        val length = maxOf(audio.samples.size + 2 * margin, audio.sampleRate)
        val samples = FloatArray(length).also { audio.samples.copyInto(it, destinationOffset = margin) }
        return recognizer.recognize(samples, audio.sampleRate).tokens.map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun release() = recognizer.release()

    companion object {
        /**
         * Splits displayed IPA back into ZIPA symbols: each base character, with any combining
         * marks (such as the nasal ◌̃) kept as their own symbols, as ZIPA and Piper both use them.
         */
        fun symbols(text: String): List<String> =
            text.filter { !it.isWhitespace() && it != '/' }.map { it.toString() }

        /** Readable form of ZIPA's tokens, with spaces between words. */
        fun display(tokens: List<String>) =
            tokens.joinToString("") { if (it == "▁") " " else it }.trim().replace(SPACES, " ")

        private val SPACES = Regex("\\s+")
    }
}

/**
 * Text-to-speech with a Piper voice (espeak-ng turns the text into sounds first). [rewrites] fix
 * text that espeak-ng misreads in this language, applied before every synthesis.
 */
class TextToSpeech(
    store: ModelStore,
    voice: String,
    private val rewrites: List<Pair<Regex, String>>,
    private val workDir: File,
) {
    private val modelPath = store.voiceModel(voice).path
    private val tokensPath = store.voiceTokens(voice).path

    /** The sounds (IPA symbols) this voice can say. */
    private val voiceSymbols: Set<String> = File(tokensPath).readLines()
        .filter { it.isNotEmpty() }
        .map { line -> line.substringBeforeLast(' ').ifEmpty { " " } }
        .toSet()

    private val tts = engine(dataDir = store.espeakData.path)

    /** This voice, reading text through espeak-ng ([dataDir]) or through a [lexicon] instead. */
    private fun engine(dataDir: String = "", lexicon: String = "") = OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(model = modelPath, tokens = tokensPath, dataDir = dataDir, lexicon = lexicon),
                numThreads = 2,
            ),
        ),
    )

    /** Speaks [text] and returns once playback has finished. */
    fun speak(text: String) {
        if (text.isBlank()) return
        play(synthesize(text))
    }

    fun synthesize(text: String, speed: Float = 1.0f): Audio {
        val fixed = rewrites.fold(text) { t, (pattern, replacement) -> pattern.replace(t, replacement) }
        return tts.generate(fixed, sid = 0, speed = speed).toAudio()
    }

    private fun GeneratedAudio.toAudio() = Audio(samples, sampleRate)

    /** Plays [audio] and returns once playback has finished. */
    fun play(audio: Audio) {
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
        stopRequested = false
        track.play()
        // Wait for playback to finish, checking often so stopPlayback() takes effect quickly.
        val end = System.currentTimeMillis() + audio.samples.size * 1000L / audio.sampleRate + 150
        while (!stopRequested && System.currentTimeMillis() < end) Thread.sleep(50)
        track.stop()
        track.release()
    }

    @Volatile private var stopRequested = false

    /** Stops speech that is playing. */
    fun stopPlayback() {
        stopRequested = true
    }

    /**
     * Says raw sounds (ZIPA's IPA symbols) with this voice, so you hear exactly what was heard.
     * Piper normally gets its sounds from espeak-ng; here a one-word lexicon maps a made-up word to
     * the sounds, and a second copy of the voice is loaded without espeak-ng so it uses the lexicon.
     */
    fun speakSounds(sounds: List<String>) {
        val symbols = sounds.map { ZIPA_TO_PIPER[it] ?: it }.filter { it in voiceSymbols && it != " " }
        if (symbols.isEmpty()) return
        // Piper expects a start mark, a pad after every sound and an end mark.
        val ids = buildList {
            add("^"); add("_")
            symbols.forEach { add(it); add("_") }
            add("$")
        }
        val lexicon = File(workDir, "sounds-lexicon.txt")
        lexicon.writeText("zz ${ids.joinToString(" ")}\n")
        val raw = engine(lexicon = lexicon.path)
        try {
            play(raw.generate("zz", sid = 0, speed = 1.0f).toAudio())
        } finally {
            raw.release()
        }
    }

    fun release() = tts.release()

    companion object {
        /** ZIPA symbols written differently in Piper's IPA. */
        private val ZIPA_TO_PIPER = mapOf("g" to "ɡ")
    }
}

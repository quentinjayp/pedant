package com.pedant.app

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
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
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val SAMPLE_RATE = 16_000

/** Audio as floats in [-1, 1] at [sampleRate]. */
class Audio(val samples: FloatArray, val sampleRate: Int)

/**
 * Records 16 kHz mono audio from the microphone, from a tap on a talk button until a second tap or
 * [SILENCE_SECONDS] without speech. Silero VAD (silero_vad.onnx, in the app's assets) tells speech
 * from silence; if it can't be loaded, only the second tap stops the recording.
 */
class MicRecorder(private val assets: AssetManager) {
    @Volatile private var recording = false
    private var thread: Thread? = null
    private val buffer = ByteArrayOutputStream()

    /**
     * Starts recording on a thread of its own, where the microphone is set up. Once it's recording,
     * a short beep plays (and isn't kept in the recording), then [onListening] is called; [onSilence]
     * is called once, after [SILENCE_SECONDS] with no speech since the beep or since the last speech.
     * Both are called on the recording's thread.
     */
    @SuppressLint("MissingPermission")
    fun start(onListening: () -> Unit, onSilence: () -> Unit) {
        if (recording) return
        synchronized(buffer) { buffer.reset() }
        recording = true
        thread = Thread {
            var record: AudioRecord? = null
            var vad: Vad? = null
            var beep: ToneGenerator? = null
            try {
                val minSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                )
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minSize * 4,
                )
                vad = runCatching { speechDetector() }.getOrNull()
                val chunk = ByteArray(minSize)
                record.startRecording()
                beep = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, BEEP_VOLUME) }.getOrNull()
                beep?.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
                // The beep is left out of the recording.
                var skipped = 0
                while (recording && skipped < BEEP_SKIP_BYTES) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n > 0) skipped += n
                }
                if (!recording) return@Thread
                onListening()
                var lastSpeech = System.nanoTime()
                var silenceTold = false
                while (recording) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n <= 0) continue
                    synchronized(buffer) { buffer.write(chunk, 0, n) }
                    vad?.let { detector ->
                        detector.acceptWaveform(floatsOf(chunk, n))
                        if (detector.isSpeechDetected()) lastSpeech = System.nanoTime()
                        // Only whether there's speech now matters, not the pieces it finds.
                        while (!detector.empty()) detector.pop()
                        if (!silenceTold && System.nanoTime() - lastSpeech > SILENCE_SECONDS * 1_000_000_000L) {
                            silenceTold = true
                            onSilence()
                        }
                    }
                }
                record.stop()
            } catch (_: Exception) {
                // The microphone was unavailable; an empty recording is returned.
            } finally {
                beep?.release()
                vad?.release()
                record?.release()
            }
        }.also { it.start() }
    }

    private fun speechDetector() = Vad(
        assets,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = 0.25f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    /** Stops recording and returns the audio as floats in [-1, 1]. */
    fun stop(): FloatArray {
        recording = false
        thread?.join()
        thread = null
        val bytes = synchronized(buffer) { buffer.toByteArray() }
        return floatsOf(bytes, bytes.size)
    }

    private fun floatsOf(bytes: ByteArray, length: Int): FloatArray {
        val shorts = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
    }

    companion object {
        /** How long without speech stops the recording, in seconds. */
        const val SILENCE_SECONDS = 5

        private const val BEEP_MS = 120
        private const val BEEP_VOLUME = 60

        /** How much is left out of the recording at its start, for the beep: 0.2 s of 16-bit samples. */
        private const val BEEP_SKIP_BYTES = SAMPLE_RATE / 5 * 2
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

/**
 * Speech-to-text with Omnilingual ASR. Writes down exactly what it hears, mistakes included.
 *
 * Its model is only in memory while the learner is being heard: [warm] loads it as they start
 * talking, and it's released once they've been heard. The rest of the time Gemma has that memory
 * (about 500 MB, and more after a long recording). [onEvent] is told each time it loads.
 */
class SpeechToText(private val store: ModelStore, private val onEvent: (String) -> Unit = {}) {
    private var recognizer: OfflineRecognizer? = null

    private fun loaded(): OfflineRecognizer = recognizer ?: run {
        val start = System.currentTimeMillis()
        recognizer(
            OfflineModelConfig(
                omnilingual = OfflineOmnilingualAsrCtcModelConfig(model = store.asrModel.path),
                tokens = store.asrTokens.path,
            ),
        ).also {
            recognizer = it
            onEvent("Speech recognizer loaded in ${(System.currentTimeMillis() - start) / 1000.0} s")
        }
    }

    /** Whether speech is on its way: set as the learner starts talking, cleared once it's been heard. */
    @Volatile private var expecting = false

    /** The learner has started talking, so [warm] may load the model. */
    fun expectSpeech() {
        expecting = true
    }

    /**
     * Loads the model ahead of [transcribe], if speech is still on its way: by the time this runs
     * the learner may already have been heard, and the model must not be left in memory.
     */
    @Synchronized
    fun warm() {
        if (expecting) loaded()
    }

    /** What was said in [samples]. The model is released afterwards. */
    @Synchronized
    fun transcribe(samples: FloatArray): String = try {
        if (samples.size < SAMPLE_RATE / 4) "" else loaded().recognize(samples, SAMPLE_RATE).text.trim()
    } finally {
        release()
    }

    @Synchronized
    fun release() {
        expecting = false
        recognizer?.release()
        recognizer = null
    }
}

/**
 * ZIPA: hears the sounds (IPA phonemes) that were actually spoken, without knowing any words.
 * Returns ZIPA's tokens, with "▁" between words.
 *
 * Its working memory grows with the longest audio it has heard and is never given back, so after
 * long audio the model is released ([releaseIfGrown]) and loads again the next time it's needed,
 * or ahead of that with [warm]. [onEvent] is told when either happens.
 */
class SoundRecognizer(private val store: ModelStore, private val onEvent: (String) -> Unit = {}) {
    private var recognizer: OfflineRecognizer? = null

    /** The longest audio heard since the model was loaded, in seconds. */
    private var longest = 0.0

    init {
        // Loaded from the start, so a model that can't be loaded shows on the loading screen.
        loaded()
    }

    private fun loaded(): OfflineRecognizer = recognizer ?: recognizer(
        OfflineModelConfig(
            zipformerCtc = OfflineZipformerCtcModelConfig(model = store.zipaModel.path),
            tokens = store.zipaTokens.path,
        ),
    ).also { recognizer = it }

    /** Loads the model if it isn't in memory, ahead of [sounds]. */
    @Synchronized
    fun warm() {
        if (recognizer != null) return
        val start = System.currentTimeMillis()
        loaded()
        onEvent("ZIPA loaded again in ${(System.currentTimeMillis() - start) / 1000.0} s")
    }

    @Synchronized
    fun sounds(audio: Audio): List<String> {
        if (audio.samples.isEmpty()) return emptyList()
        // ZIPA misses sounds right at the start of a clip with no lead-in (as in Piper's audio) and
        // fails on very short clips, so add silence before and after, and pad to at least a second.
        val margin = audio.sampleRate / 4
        val length = maxOf(audio.samples.size + 2 * margin, audio.sampleRate)
        val samples = FloatArray(length).also { audio.samples.copyInto(it, destinationOffset = margin) }
        warm()
        longest = maxOf(longest, length.toDouble() / audio.sampleRate)
        return loaded().recognize(samples, audio.sampleRate).tokens.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Releases the model if it has heard audio longer than [LONG_SECONDS], to give back the memory that took. */
    @Synchronized
    fun releaseIfGrown() {
        if (recognizer == null || longest <= LONG_SECONDS) return
        onEvent("ZIPA released after hearing %.1f s of audio".format(longest))
        release()
    }

    @Synchronized
    fun release() {
        recognizer?.release()
        recognizer = null
        longest = 0.0
    }

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

        /**
         * Audio longer than this makes the model worth releasing afterwards: in a test, hearing 5 s
         * added about 40 MB to its memory, 10 s about 80 MB and 20 s about 160 MB.
         */
        private const val LONG_SECONDS = 10.0
    }
}

/**
 * Text-to-speech with a Piper voice (espeak-ng turns the text into sounds first). [espeakText]
 * fixes text that espeak-ng misreads in this language, applied before every synthesis.
 */
class TextToSpeech(
    store: ModelStore,
    voice: String,
    private val espeakText: (String) -> String,
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

    fun synthesize(text: String, speed: Float = 1.0f): Audio {
        return tts.generate(espeakText(text), sid = 0, speed = speed).toAudio()
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

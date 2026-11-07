package com.pedant.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

sealed interface Screen {
    data object Setup : Screen
    data object Loading : Screen
    data object Crashed : Screen
    data object ChooseBackend : Screen
    data object PickLanguage : Screen
    data object Conversation : Screen
}

enum class Busy(val label: String) {
    Idle(""),
    Listening("Listening…"),
    Transcribing("Working out what you said…"),
    Thinking("Thinking…"),
    Speaking("Speaking…"),
    Preparing("Preparing the phrase…"),
}

sealed interface ChatItem {
    /** What the learner said or typed; [sounds] is what ZIPA heard, when they spoke. */
    data class User(val text: String, val sounds: String? = null) : ChatItem
    data class Tutor(val correction: String?, val reply: String, val english: String? = null) : ChatItem
    /** Learning mode: the phrase to say, translated from what the learner wanted to say. */
    data class Say(val english: String, val phrase: String) : ChatItem
    /** Learning mode: one try at saying the phrase. */
    data class Attempt(val heard: String, val sounds: String, val result: AttemptResult) : ChatItem
    data class Note(val text: String) : ChatItem
}

/** Learning mode: the phrase being practised, its audio and its expected sounds per word. */
private class Drill(val phrase: String, val audio: Audio, val refWords: List<RefWord>)

data class Progress(val done: Long, val total: Long, val label: String)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ModelStore(app)
    private val diagnostics = Diagnostics(app)
    private val recorder = MicRecorder()
    private var tutor: Tutor? = null
    private var stt: SpeechToText? = null
    private var zipa: SoundRecognizer? = null
    private var drill: Drill? = null
    private var tts: TextToSpeech? = null
    private var ttsVoice: String? = null

    var screen by mutableStateOf<Screen>(Screen.Setup)
        private set
    var progress by mutableStateOf<Progress?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var crashReport by mutableStateOf<String?>(null)
        private set
    var language by mutableStateOf<Language?>(null)
        private set
    var busy by mutableStateOf(Busy.Idle)
        private set
    /** "GPU" or "CPU": where Gemma is running, once loaded. */
    var backend by mutableStateOf<String?>(null)
        private set
    var mode by mutableStateOf(Mode.Learning)
    /** Learning mode: the phrase to repeat, or null while waiting for the next English line. */
    var drillPhrase by mutableStateOf<String?>(null)
        private set
    var drillAttempts by mutableStateOf(0)
        private set
    val chat = mutableStateListOf<ChatItem>()

    /** What to do next, shown above the talk button. */
    val hint: String
        get() = when {
            busy != Busy.Idle -> busy.label
            mode == Mode.Conversation -> "Hold the button and speak ${language?.englishName ?: ""}"
            drillPhrase == null -> "Hold the button and say in English what you want to say, or type it"
            else -> "Hold the button and say the phrase"
        }

    /** How the last attempt to load Gemma on each backend went, shown on the choice screen. */
    fun lastResult(gpu: Boolean): String? = diagnostics.lastLoadResult(gpu)

    /** Free memory right now, shown before choosing where Gemma runs. */
    fun memorySummary(): String = diagnostics.memorySummary()

    init {
        diagnostics.step?.let { unfinished ->
            if (unfinished.startsWith(GPU_STEP)) diagnostics.recordLoad(gpu = true, "crashed while loading")
            if (unfinished.startsWith(CPU_STEP)) diagnostics.recordLoad(gpu = false, "crashed while loading")
        }
        crashReport = diagnostics.takeCrashReport()
        screen = when {
            crashReport != null -> Screen.Crashed
            store.coreReady() -> Screen.ChooseBackend
            else -> Screen.Setup
        }
    }

    fun dismissCrash() {
        crashReport = null
        screen = if (store.coreReady()) Screen.ChooseBackend else Screen.Setup
    }

    fun downloadCore() {
        error = null
        viewModelScope.launch {
            try {
                withProgress { report -> store.downloadCore(report) }
                screen = Screen.ChooseBackend
            } catch (e: Exception) {
                progress = null
                error = "Download failed: ${e.message}"
            }
        }
    }

    /**
     * Runs a download, showing its progress. The download reports from a background thread, so
     * the latest value is copied to the screen on the main thread a few times a second.
     */
    private suspend fun <T> withProgress(download: suspend ((Long, Long, String) -> Unit) -> T): T {
        val latest = AtomicReference(Progress(0, 0, "Starting…"))
        progress = latest.get()
        val ticker = viewModelScope.launch {
            while (true) {
                progress = latest.get()
                delay(250)
            }
        }
        try {
            return download { done, total, label -> latest.set(Progress(done, total, label)) }
        } finally {
            ticker.cancel()
            progress = null
        }
    }

    /** Loads the speech recognizer and Gemma, with Gemma on the GPU or the CPU. */
    fun loadModels(useGpu: Boolean) {
        error = null
        screen = Screen.Loading
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    // Gemma first: it needs the most memory, so it loads while the most is free.
                    if (tutor == null) tutor = loadTutor(useGpu)
                    if (stt == null) {
                        diagnostics.step = "Loading the speech recognizer (${diagnostics.memorySummary()})"
                        stt = SpeechToText(store)
                    }
                    if (zipa == null) {
                        diagnostics.step = "Loading ZIPA (${diagnostics.memorySummary()})"
                        zipa = SoundRecognizer(store)
                    }
                    diagnostics.step = null
                }
                screen = Screen.PickLanguage
            } catch (e: Throwable) {
                diagnostics.step = null
                error = "Could not load the models: ${e.message}"
                screen = Screen.ChooseBackend
            }
        }
    }

    private fun loadTutor(useGpu: Boolean): Tutor {
        val model = checkNotNull(store.gemmaModel) { "Gemma model file is missing" }
        diagnostics.step = "${if (useGpu) GPU_STEP else CPU_STEP} (${diagnostics.memorySummary()})"
        val start = System.currentTimeMillis()
        try {
            val loaded = Tutor(getApplication(), model, useGpu)
            val seconds = (System.currentTimeMillis() - start) / 1000
            diagnostics.recordLoad(useGpu, "worked, loaded in $seconds s")
            backend = if (useGpu) "GPU" else "CPU"
            return loaded
        } catch (e: Throwable) {
            diagnostics.recordLoad(useGpu, "failed: ${e.message}")
            throw e
        }
    }

    fun pickLanguage(lang: Language) {
        error = null
        viewModelScope.launch {
            try {
                val voice = store.installedVoice(lang)
                    ?: withProgress { report -> store.downloadVoice(lang, report) }
                withContext(Dispatchers.Default) {
                    if (ttsVoice != voice) {
                        diagnostics.step = "Loading the $voice voice"
                        tts?.release()
                        tts = TextToSpeech(store, voice)
                        ttsVoice = voice
                    }
                    diagnostics.step = "Starting a ${lang.englishName} ${mode.title} session"
                    checkNotNull(tutor).startConversation(lang, mode)
                    diagnostics.step = null
                }
                language = lang
                chat.clear()
                setDrill(null)
                screen = Screen.Conversation
                runTurn(if (mode == Mode.Learning) Tutor.LEARNING_START else Tutor.CONVERSATION_START)
            } catch (e: Throwable) {
                diagnostics.step = null
                progress = null
                error = "Could not start: ${e.message}"
            }
        }
    }

    fun newConversation() {
        if (busy != Busy.Idle) return
        screen = Screen.PickLanguage
    }

    fun startTalking() {
        if (busy != Busy.Idle) return
        busy = Busy.Listening
        recorder.start()
    }

    fun stopTalking() {
        if (busy != Busy.Listening) return
        busy = Busy.Transcribing
        viewModelScope.launch {
            val audio = withContext(Dispatchers.Default) { Audio(recorder.stop(), SAMPLE_RATE) }
            try {
                when {
                    mode == Mode.Conversation -> conversationTurn(audio)
                    drill == null -> {
                        // Learning mode, waiting for English: only the words matter here.
                        val english = withContext(Dispatchers.Default) { checkNotNull(stt).transcribe(audio.samples) }
                        if (english.isNotBlank()) translate(english)
                    }
                    else -> checkAttempt(checkNotNull(drill), audio)
                }
            } catch (e: Throwable) {
                chat += ChatItem.Note("Something went wrong: ${e.message}")
            } finally {
                diagnostics.step = null
                busy = Busy.Idle
            }
        }
    }

    /** Learning mode: the learner typed what they want to say. */
    fun submitEnglish(text: String) {
        if (busy != Busy.Idle || mode != Mode.Learning || drill != null || text.isBlank()) return
        viewModelScope.launch {
            try {
                translate(text.trim())
            } catch (e: Throwable) {
                chat += ChatItem.Note("Something went wrong: ${e.message}")
            } finally {
                diagnostics.step = null
                busy = Busy.Idle
            }
        }
    }

    /** Learning mode: plays the phrase again. */
    fun hearAgain() {
        val d = drill ?: return
        if (busy != Busy.Idle) return
        busy = Busy.Speaking
        viewModelScope.launch {
            withContext(Dispatchers.Default) { tts?.play(d.audio) }
            busy = Busy.Idle
        }
    }

    /** Learning mode: moves on without getting the phrase right (offered after a few tries). */
    fun skip() {
        val d = drill ?: return
        if (busy != Busy.Idle) return
        chat += ChatItem.Note("Skipped")
        setDrill(null)
        viewModelScope.launch { runTurn(Tutor.learningSaid(d.phrase)) }
    }

    /** Hears the recording with both Omnilingual (words) and ZIPA (sounds), side by side. */
    private suspend fun hear(audio: Audio): Pair<String, List<String>> = withContext(Dispatchers.Default) {
        diagnostics.step = "Hearing ${audio.samples.size / audio.sampleRate.toDouble()} s of speech"
        coroutineScope {
            val words = async { checkNotNull(stt).transcribe(audio.samples) }
            val sounds = async { checkNotNull(zipa).sounds(audio) }
            words.await() to sounds.await()
        }
    }

    private suspend fun conversationTurn(audio: Audio) {
        val (transcript, sounds) = hear(audio)
        if (transcript.isBlank() && sounds.isEmpty()) return
        val soundText = SoundRecognizer.display(sounds)
        chat += ChatItem.User(transcript, soundText)
        runTurn(Tutor.heardMessage(transcript, soundText))
    }

    /** Learning mode: translates what the learner wants to say and starts drilling it. */
    private suspend fun translate(english: String) {
        chat += ChatItem.User(english)
        busy = Busy.Thinking
        val index = chat.size
        chat += ChatItem.Say(english, "")
        diagnostics.step = "Translating with Gemma"
        var phrase = ""
        checkNotNull(tutor).respond(Tutor.learningTranslate(english)).collect { raw ->
            phrase = Tutor.parse(raw).say ?: ""
            chat[index] = ChatItem.Say(english, phrase)
        }
        if (phrase.isBlank()) {
            chat[index] = ChatItem.Note("Couldn't translate that. Try saying it another way.")
            return
        }
        busy = Busy.Preparing
        diagnostics.step = "Preparing the phrase with Piper and ZIPA"
        val voice = checkNotNull(tts)
        val audio = withContext(Dispatchers.Default) { voice.synthesize(phrase) }
        busy = Busy.Speaking
        withContext(Dispatchers.Default) { voice.play(audio) }
        busy = Busy.Preparing
        // Expected sounds, word by word: Piper says each word and ZIPA listens to it.
        val refWords = withContext(Dispatchers.Default) {
            Matching.words(phrase).map { w -> RefWord(w, checkNotNull(zipa).sounds(voice.synthesize(w))) }
        }
        setDrill(Drill(phrase, audio, refWords))
    }

    private suspend fun checkAttempt(d: Drill, audio: Audio) {
        val (transcript, sounds) = hear(audio)
        val result = Matching.check(d.phrase, transcript, d.refWords, sounds)
        chat += ChatItem.Attempt(transcript, SoundRecognizer.display(sounds), result)
        if (result.passed) {
            setDrill(null)
            runTurn(Tutor.learningSaid(d.phrase))
        } else {
            drillAttempts++
            busy = Busy.Speaking
            withContext(Dispatchers.Default) { tts?.play(d.audio) }
        }
    }

    private fun setDrill(d: Drill?) {
        drill = d
        drillPhrase = d?.phrase
        drillAttempts = 0
    }

    /** Sends [message] to the tutor, shows the answer as it streams in and speaks the reply. */
    private suspend fun runTurn(message: String) {
        busy = Busy.Thinking
        val index = chat.size
        chat += ChatItem.Tutor(null, "")
        try {
            var latest = TutorTurn()
            diagnostics.step = "Generating a reply with Gemma"
            checkNotNull(tutor).respond(message).collect { raw ->
                latest = Tutor.parse(raw)
                chat[index] = ChatItem.Tutor(latest.correction, latest.reply, latest.english)
            }
            busy = Busy.Speaking
            diagnostics.step = "Speaking the reply with Piper"
            withContext(Dispatchers.Default) { tts?.speak(latest.reply) }
        } catch (e: Throwable) {
            chat[index] = ChatItem.Tutor(null, "(Something went wrong: ${e.message})")
        } finally {
            diagnostics.step = null
            busy = Busy.Idle
        }
    }

    companion object {
        private const val GPU_STEP = "Loading Gemma on the GPU"
        private const val CPU_STEP = "Loading Gemma on the CPU"
    }

    override fun onCleared() {
        tutor?.close()
        stt?.release()
        zipa?.release()
        tts?.release()
    }
}

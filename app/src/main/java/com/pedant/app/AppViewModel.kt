package com.pedant.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
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
    Idle("Hold the button and speak"),
    Listening("Listening…"),
    Transcribing("Writing down what you said…"),
    Thinking("Thinking…"),
    Speaking("Speaking…"),
}

sealed interface ChatItem {
    data class User(val text: String) : ChatItem
    data class Tutor(val correction: String?, val reply: String) : ChatItem
}

data class Progress(val done: Long, val total: Long, val label: String)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ModelStore(app)
    private val diagnostics = Diagnostics(app)
    private val recorder = MicRecorder()
    private var tutor: Tutor? = null
    private var stt: SpeechToText? = null
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
    val chat = mutableStateListOf<ChatItem>()

    /** How the last attempt to load Gemma on each backend went, shown on the choice screen. */
    fun lastResult(gpu: Boolean): String? = diagnostics.lastLoadResult(gpu)

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
                    if (stt == null) {
                        diagnostics.step = "Loading the speech recognizer"
                        stt = SpeechToText(store)
                    }
                    if (tutor == null) tutor = loadTutor(useGpu)
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
                    diagnostics.step = "Starting a ${lang.englishName} conversation"
                    checkNotNull(tutor).startConversation(lang)
                    diagnostics.step = null
                }
                language = lang
                chat.clear()
                screen = Screen.Conversation
                runTurn(userText = null)
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
            val text = try {
                withContext(Dispatchers.Default) {
                    val samples = recorder.stop()
                    diagnostics.step = "Transcribing ${samples.size / 16_000.0} s of speech"
                    checkNotNull(stt).transcribe(samples).also { diagnostics.step = null }
                }
            } catch (e: Throwable) {
                diagnostics.step = null
                chat += ChatItem.Tutor(null, "(Could not understand the recording: ${e.message})")
                ""
            }
            if (text.isBlank()) {
                busy = Busy.Idle
                return@launch
            }
            chat += ChatItem.User(text)
            runTurn(text)
        }
    }

    /** Asks the tutor to respond (or to greet, when [userText] is null) and speaks the reply. */
    private suspend fun runTurn(userText: String?) {
        busy = Busy.Thinking
        val index = chat.size
        chat += ChatItem.Tutor(null, "")
        try {
            var latest = TutorTurn(null, "")
            diagnostics.step = "Generating a reply with Gemma"
            checkNotNull(tutor).respond(userText ?: Tutor.GREETING_REQUEST).collect { raw ->
                latest = Tutor.parse(raw)
                chat[index] = ChatItem.Tutor(latest.correction, latest.reply)
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
        tts?.release()
    }
}

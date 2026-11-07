package com.pedant.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Setup : Screen
    data object Loading : Screen
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
    private val recorder = MicRecorder()
    private var tutor: Tutor? = null
    private var stt: SpeechToText? = null
    private var tts: TextToSpeech? = null
    private var ttsVoice: String? = null

    var screen by mutableStateOf<Screen>(if (store.coreReady()) Screen.Loading else Screen.Setup)
        private set
    var progress by mutableStateOf<Progress?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var language by mutableStateOf<Language?>(null)
        private set
    var busy by mutableStateOf(Busy.Idle)
        private set
    val chat = mutableStateListOf<ChatItem>()

    init {
        if (store.coreReady()) loadModels()
    }

    fun downloadCore() {
        error = null
        viewModelScope.launch {
            try {
                store.downloadCore { done, total, label -> progress = Progress(done, total, label) }
                progress = null
                screen = Screen.Loading
                loadModels()
            } catch (e: Exception) {
                progress = null
                error = "Download failed: ${e.message}"
            }
        }
    }

    private fun loadModels() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    if (stt == null) stt = SpeechToText(store)
                    if (tutor == null) tutor = Tutor(getApplication(), checkNotNull(store.gemmaModel))
                }
                screen = Screen.PickLanguage
            } catch (e: Throwable) {
                error = "Could not load the models: ${e.message}"
                screen = Screen.Setup
            }
        }
    }

    fun pickLanguage(lang: Language) {
        error = null
        viewModelScope.launch {
            try {
                if (!store.voiceReady(lang.piperVoice)) {
                    store.downloadVoice(lang) { done, total, label -> progress = Progress(done, total, label) }
                    progress = null
                }
                withContext(Dispatchers.Default) {
                    if (ttsVoice != lang.piperVoice) {
                        tts?.release()
                        tts = TextToSpeech(store, lang.piperVoice)
                        ttsVoice = lang.piperVoice
                    }
                    checkNotNull(tutor).startConversation(lang)
                }
                language = lang
                chat.clear()
                screen = Screen.Conversation
                runTurn(userText = null)
            } catch (e: Throwable) {
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
            val text = withContext(Dispatchers.Default) {
                val samples = recorder.stop()
                checkNotNull(stt).transcribe(samples)
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
            checkNotNull(tutor).respond(userText ?: Tutor.GREETING_REQUEST).collect { raw ->
                latest = Tutor.parse(raw)
                chat[index] = ChatItem.Tutor(latest.correction, latest.reply)
            }
            busy = Busy.Speaking
            withContext(Dispatchers.Default) { tts?.speak(latest.reply) }
        } catch (e: Throwable) {
            chat[index] = ChatItem.Tutor(null, "(Something went wrong: ${e.message})")
        } finally {
            busy = Busy.Idle
        }
    }

    override fun onCleared() {
        tutor?.close()
        stt?.release()
        tts?.release()
    }
}

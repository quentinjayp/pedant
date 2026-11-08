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
    LookingUp("Looking up the word…"),
}

/** A tapped word: its meaning in context and how to say it. Null fields are still loading. */
data class WordLookup(
    val word: String,
    val sentence: String,
    val meaning: String? = null,
    val soundsLike: String? = null,
    val sounds: String? = null,
    val error: String? = null,
    val loading: Boolean = true,
)

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
private class Drill(val phrase: String, val audio: Audio, val refWords: List<RefWord>) {
    /** The phrase said slowly, made the first time it's asked for. */
    var slowAudio: Audio? = null
}

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
    var mode by mutableStateOf(Mode.Learning)
    /** Learning mode: the phrase to repeat, or null while waiting for the next English line. */
    var drillPhrase by mutableStateOf<String?>(null)
        private set
    var drillAttempts by mutableStateOf(0)
        private set
    val chat = mutableStateListOf<ChatItem>()
    /**
     * Learning mode: the chat index of the English line whose phrase is being practised. It can be
     * edited (long press) until the phrase is said or skipped.
     */
    var editableIndex by mutableStateOf<Int?>(null)
        private set

    /** The word bubble being shown, if any. */
    var lookup by mutableStateOf<WordLookup?>(null)
        private set
    private val lookupCache = mutableMapOf<Pair<String, String>, WordLookup>()

    /** What to do next, shown above the talk button. */
    val hint: String
        get() = when {
            busy != Busy.Idle -> busy.label
            mode == Mode.Conversation -> "Hold the button and speak ${language?.englishName ?: ""}"
            drillPhrase == null -> "Hold the button and say in English what you want to say, or type it"
            else -> "Hold the button and say the phrase"
        }

    init {
        crashReport = diagnostics.takeCrashReport()
        when {
            crashReport != null -> screen = Screen.Crashed
            store.coreReady() -> loadModels()
            else -> screen = Screen.Setup
        }
    }

    fun dismissCrash() {
        crashReport = null
        if (store.coreReady()) loadModels() else screen = Screen.Setup
    }

    fun downloadCore() {
        error = null
        viewModelScope.launch {
            try {
                withProgress { report -> store.downloadCore(report) }
                loadModels()
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

    /** Loads Gemma (on the CPU), the speech recognizer and ZIPA. */
    private fun loadModels() {
        error = null
        screen = Screen.Loading
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    // Gemma first: it needs the most memory, so it loads while the most is free.
                    if (tutor == null) {
                        diagnostics.step = "Loading Gemma (${diagnostics.memorySummary()})"
                        tutor = Tutor(getApplication(), checkNotNull(store.gemmaModel) { "Gemma model file is missing" })
                    }
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
                screen = Screen.Setup
            }
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
                        tts = TextToSpeech(store, voice, getApplication<Application>().cacheDir)
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
    fun hearAgain(slow: Boolean) {
        val d = drill ?: return
        if (busy != Busy.Idle) return
        busy = Busy.Speaking
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val voice = tts ?: return@withContext
                    val audio = if (!slow) d.audio else d.slowAudio ?: voice.synthesize(d.phrase, SLOW_SPEED).also { d.slowAudio = it }
                    voice.play(audio)
                }
            } finally {
                busy = Busy.Idle
            }
        }
    }

    /**
     * Opens the bubble for a tapped [word] from [sentence]: its meaning in that sentence and a
     * pronunciation guide from Gemma, and its sounds from ZIPA listening to Piper say it.
     */
    fun lookUp(word: String, sentence: String) {
        val lang = language ?: return
        val key = word.lowercase() to sentence
        lookupCache[key]?.let { lookup = it; return }
        lookup = WordLookup(word, sentence)
        if (busy != Busy.Idle) {
            lookup = WordLookup(word, sentence, error = "Wait until the app is done speaking or thinking.", loading = false)
            return
        }
        busy = Busy.LookingUp
        viewModelScope.launch {
            var result = WordLookup(word, sentence)
            try {
                val sounds = withContext(Dispatchers.Default) {
                    SoundRecognizer.display(checkNotNull(zipa).sounds(checkNotNull(tts).synthesize(word)))
                }
                result = result.copy(sounds = sounds)
                lookup = result
                val (meaning, soundsLike) = withContext(Dispatchers.Default) {
                    Tutor.parseLookup(checkNotNull(tutor).lookUp(lang, word, sentence))
                }
                result = result.copy(meaning = meaning, soundsLike = soundsLike, loading = false)
                lookupCache[key] = result
            } catch (e: Throwable) {
                result = result.copy(error = "Couldn't look this up: ${e.message}", loading = false)
            } finally {
                if (lookup?.word == word && lookup?.sentence == sentence) lookup = result
                busy = Busy.Idle
            }
        }
    }

    fun closeLookup() {
        lookup = null
    }

    /** Has Piper say [word], slowly if [slow]. */
    fun hearWord(word: String, slow: Boolean) {
        if (busy != Busy.Idle || word.isBlank()) return
        busy = Busy.Speaking
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val voice = tts ?: return@withContext
                    voice.play(voice.synthesize(word, if (slow) SLOW_SPEED else 1.0f))
                }
            } finally {
                busy = Busy.Idle
            }
        }
    }

    /** Has Piper say [text], e.g. a correct word or the word that was heard instead. */
    fun sayWord(text: String) {
        if (busy != Busy.Idle || text.isBlank()) return
        busy = Busy.Speaking
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) { tts?.speak(text) }
            } finally {
                busy = Busy.Idle
            }
        }
    }

    /** Has Piper say raw sounds, e.g. the sounds ZIPA heard. [sounds] is the displayed IPA text. */
    fun saySounds(sounds: String) {
        if (busy != Busy.Idle || sounds.isBlank()) return
        busy = Busy.Speaking
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) { tts?.speakSounds(SoundRecognizer.symbols(sounds)) }
            } catch (e: Throwable) {
                chat += ChatItem.Note("Couldn't play those sounds: ${e.message}")
            } finally {
                busy = Busy.Idle
            }
        }
    }

    /**
     * Learning mode: replaces the English line at [index] with [text] (e.g. to fix a typo) and
     * translates it again, replacing the phrase being practised.
     */
    fun editEnglish(index: Int, text: String) {
        val english = text.trim()
        if (busy != Busy.Idle || index != editableIndex || english.isEmpty()) return
        while (chat.size > index + 1) chat.removeAt(chat.lastIndex)
        chat[index] = ChatItem.User(english)
        setDrill(null)
        viewModelScope.launch {
            try {
                translate(english, addLine = false)
            } catch (e: Throwable) {
                chat += ChatItem.Note("Something went wrong: ${e.message}")
            } finally {
                diagnostics.step = null
                busy = Busy.Idle
            }
        }
    }

    /** Learning mode: removes the English line at [index] and its phrase, to say something else. */
    fun deleteEnglish(index: Int) {
        if (busy != Busy.Idle || index != editableIndex) return
        while (chat.size > index) chat.removeAt(chat.lastIndex)
        setDrill(null)
        editableIndex = null
    }

    /** Learning mode: moves on without getting the phrase right (offered after a few tries). */
    fun skip() {
        val d = drill ?: return
        if (busy != Busy.Idle) return
        chat += ChatItem.Note("Skipped")
        setDrill(null)
        editableIndex = null
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
    private suspend fun translate(english: String, addLine: Boolean = true) {
        if (addLine) {
            chat += ChatItem.User(english)
            editableIndex = chat.lastIndex
        }
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
        // Expected sounds: ZIPA listens to Piper saying the whole phrase (the audio you hear). Each
        // word said on its own only guides where the word boundaries fall.
        val refWords = withContext(Dispatchers.Default) {
            val recognizer = checkNotNull(zipa)
            val isolated = Matching.words(phrase).map { w -> RefWord(w, recognizer.sounds(voice.synthesize(w))) }
            Matching.segmentReference(recognizer.sounds(audio), isolated)
        }
        setDrill(Drill(phrase, audio, refWords))
    }

    private suspend fun checkAttempt(d: Drill, audio: Audio) {
        val (transcript, sounds) = hear(audio)
        var result = Matching.check(d.phrase, transcript, d.refWords, sounds)
        if (!result.passed && transcript.isNotBlank()) {
            // Sound-alike check: Piper says what Omnilingual heard and ZIPA listens, so a word
            // spelled differently but pronounced the same (like "sava" for "Ça va") still counts.
            val transcriptSounds = withContext(Dispatchers.Default) {
                checkNotNull(zipa).sounds(checkNotNull(tts).synthesize(transcript))
            }
            result = Matching.check(d.phrase, transcript, d.refWords, sounds, transcriptSounds)
        }
        chat += ChatItem.Attempt(transcript, SoundRecognizer.display(sounds), result)
        if (result.passed) {
            setDrill(null)
            editableIndex = null
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
        /** Speaking rate for "Slow": a bit over half normal speed, still natural-sounding. */
        private const val SLOW_SPEED = 0.6f
    }

    override fun onCleared() {
        tutor?.close()
        stt?.release()
        zipa?.release()
        tts?.release()
    }
}

package com.pedant.app

import android.app.Application
import android.content.Context
import android.system.Os
import android.system.OsConstants
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

sealed interface Screen {
    data object Setup : Screen
    data object Loading : Screen
    data object Crashed : Screen
    data object Home : Screen
    data object PickLanguage : Screen
    data object Conversation : Screen
    data object Settings : Screen
}

enum class Busy(val label: String) {
    Idle(""),
    Listening("Listening…"),
    Transcribing("Working out what you said…"),
    Thinking("Thinking…"),
    Speaking("Speaking…"),
    Preparing("Preparing the phrase…"),
    Starting("Getting ready…"),
    Translating("Translating the reply…"),
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
    /** The meaning comes from the downloaded dictionary, not Gemma. */
    val fromDictionary: Boolean = false,
    /** The dictionary's meanings, a group per part of speech, to lay out; [meaning] is the same as text. */
    val groups: List<Dictionary.Group>? = null,
)

sealed interface ChatItem {
    /** What the learner said or typed; [sounds] is what ZIPA heard, when they spoke. */
    data class User(val text: String, val sounds: String? = null) : ChatItem
    data class Tutor(val reply: String, val english: String? = null) : ChatItem
    /** Learning mode: the phrase to say, translated from what the learner wanted to say. */
    data class Say(val english: String, val phrase: String) : ChatItem
    /** Learning mode: one try at saying [phrase]. */
    data class Attempt(val heard: String, val sounds: String, val result: AttemptResult, val phrase: String) : ChatItem
    data class Note(val text: String) : ChatItem
}

/** Learning mode: the phrase being practised, its audio and its expected sounds per word. */
private class Drill(val phrase: String, val audio: Audio, val refWords: List<RefWord>) {
    /** The phrase said slowly, made the first time it's asked for. */
    var slowAudio: Audio? = null
}

data class Progress(val done: Long, val total: Long, val label: String)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    init {
        Instructions.load(app)
    }

    private val store = ModelStore(app)
    private val diagnostics = Diagnostics(app)
    private val openings = OpeningCache(app.filesDir, app.assets)
    private val recorder = MicRecorder()
    private var tutor: Tutor? = null
    private var stt: SpeechToText? = null
    private var zipa: SoundRecognizer? = null
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
    var scenario by mutableStateOf(Scenario.Default)
    /** Formal or casual: what Gemma is told with each phrase and translation, so a change applies to the next one. */
    var register by mutableStateOf(Register.Formal)

    private val uiPrefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)

    /** Whether the conversation's footer is folded away, leaving only Hold to talk. Remembered. */
    var dockCollapsed by mutableStateOf(uiPrefs.getBoolean("dockCollapsed", false))
        private set

    fun toggleDock() {
        dockCollapsed = !dockCollapsed
        uiPrefs.edit().putBoolean("dockCollapsed", dockCollapsed).apply()
    }
    private var drill by mutableStateOf<Drill?>(null)
    /** Learning mode: the phrase to repeat, or null while waiting for the next English line. */
    val drillPhrase: String? get() = drill?.phrase
    var drillAttempts by mutableStateOf(0)
        private set
    val chat = mutableStateListOf<ChatItem>()

    /**
     * Everything a conversation does (replies, speech, checks) runs in this scope, so starting a new
     * conversation can stop it all, and wait for it, before anything is released.
     */
    private val conversationJobs = SupervisorJob(viewModelScope.coroutineContext[Job])
    private val conversationScope = CoroutineScope(viewModelScope.coroutineContext + conversationJobs)
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

    /** The current language's downloaded dictionary, the first place word lookups look. */
    @Volatile private var dictionary: Dictionary? = null

    /** A newer dictionary for the current language, offered to the learner. */
    var dictionaryOffer by mutableStateOf<DictionaryInfo?>(null)
        private set

    /** Progress (0 to 1) of a dictionary download in progress, or null. */
    var dictionaryProgress by mutableStateOf<Float?>(null)
        private set

    /** What to do next, shown above the talk button. */
    val hint: String
        get() = when {
            busy != Busy.Idle -> busy.label
            drillPhrase == null -> "Hold the button and say in English what you want to say, or type it"
            else -> "Hold the button and say the phrase"
        }

    fun dismissCrash() {
        crashReport = null
        if (store.coreReady()) loadModels() else screen = Screen.Setup
    }

    fun downloadCore() {
        error = null
        viewModelScope.launch {
            try {
                withProgress("Downloading Pedant's models") { report -> store.downloadCore(report) }
                loadModels()
            } catch (e: Exception) {
                progress = null
                error = "Download failed: ${e.message}"
            }
        }
    }

    /**
     * Runs a download, showing its progress on screen and, titled [title], in the download
     * notification. The download reports from a background thread, so the latest value is copied
     * to the screen on the main thread a few times a second.
     */
    private suspend fun <T> withProgress(
        title: String,
        show: (Progress?) -> Unit = { progress = it },
        download: suspend ((Long, Long, String) -> Unit) -> T,
    ): T = keepDownloading(title) {
        val latest = AtomicReference(Progress(0, 0, "Starting…"))
        show(latest.get())
        val ticker = viewModelScope.launch {
            while (true) {
                show(latest.get())
                delay(250)
            }
        }
        try {
            download { done, total, label ->
                latest.set(Progress(done, total, label))
                DownloadService.update(getApplication(), title, done, total)
            }
        } finally {
            ticker.cancel()
            show(null)
        }
    }

    /** Downloads under way. While there are any, [DownloadService] keeps them going if the app is left. */
    private var downloads = 0

    private suspend fun <T> keepDownloading(title: String, download: suspend () -> T): T {
        if (downloads++ == 0) DownloadService.start(getApplication(), title)
        try {
            return download()
        } finally {
            if (--downloads == 0) DownloadService.stop(getApplication())
        }
    }

    /** The model loading right now, shown on the loading screen. */
    var loadingStep by mutableStateOf<String?>(null)
        private set

    /**
     * Rough loading progress, 0 to 1, estimated from how much memory the app has taken on so far
     * compared with how much loading took last time (the model files' size the first time).
     */
    var loadProgress by mutableStateOf(0f)
        private set

    private val loadPrefs = app.getSharedPreferences("loading", Context.MODE_PRIVATE)

    /** Memory the app is using right now (resident set size), in bytes. */
    private fun residentBytes(): Long = runCatching {
        File("/proc/self/statm").readText().trim().split(' ')[1].toLong() * Os.sysconf(OsConstants._SC_PAGESIZE)
    }.getOrDefault(0L)

    /** Loads one model, recording how long it took and the free memory before and after. */
    private fun <T> timedLoad(name: String, load: () -> T): T {
        val before = diagnostics.memorySummary()
        loadingStep = name
        diagnostics.step = "Loading $name ($before)"
        diagnostics.log("Loading $name: start, $before")
        val start = System.currentTimeMillis()
        val result = load()
        diagnostics.log("Loading $name: done in ${(System.currentTimeMillis() - start) / 1000.0} s, ${diagnostics.memorySummary()}")
        return result
    }

    /** Loads Gemma (on the CPU), the speech recognizer and ZIPA, whichever aren't loaded, then opens [then]. */
    private fun loadModels(then: Screen = Screen.Home) {
        error = null
        screen = Screen.Loading
        viewModelScope.launch {
            // Only a full load is a fair guide for next time; otherwise the files' size is.
            val loadingAll = tutor == null && stt == null && zipa == null
            val growthKey = "growth-${store.gemma.name}"
            val startBytes = residentBytes()
            val files = listOfNotNull(
                store.gemmaFile().takeIf { tutor == null },
                store.asrModel.takeIf { stt == null },
                store.zipaModel.takeIf { zipa == null },
            )
            val expected = loadPrefs.getLong(growthKey, 0L).takeIf { it > 0 && loadingAll }
                ?: files.sumOf { it.length() }.coerceAtLeast(1L)
            loadProgress = 0f
            val ticker = launch(Dispatchers.Default) {
                while (true) {
                    val grown = (residentBytes() - startBytes).coerceAtLeast(0L)
                    // Never past 99% until loading is done, and never backwards.
                    loadProgress = maxOf(loadProgress, (grown.toFloat() / expected).coerceAtMost(0.99f))
                    delay(250)
                }
            }
            try {
                withContext(Dispatchers.Default) {
                    // Gemma first: it needs the most memory, so it loads while the most is free.
                    if (tutor == null) {
                        tutor = timedLoad("Gemma") {
                            Tutor(getApplication(), checkNotNull(store.gemmaFile()) { "Gemma model file is missing" })
                        }
                    }
                    if (stt == null) stt = timedLoad("Omnilingual ASR") { SpeechToText(store) }
                    if (zipa == null) zipa = timedLoad("ZIPA") { SoundRecognizer(store) }
                    diagnostics.step = null
                    loadingStep = null
                }
                ticker.cancel()
                (residentBytes() - startBytes).takeIf { loadingAll && it > 0 }?.let { loadPrefs.edit().putLong(growthKey, it).apply() }
                loadProgress = 1f
                // Let the bar fill to 100% before moving on.
                delay(LOADING_BAR_FILL_MS + 100L)
                screen = then
            } catch (e: Throwable) {
                ticker.cancel()
                diagnostics.step = null
                error = "Could not load the models: ${e.message}"
                screen = Screen.Setup
            }
        }
    }

    /** Starts a new conversation in [lang]; the saved one is replaced once this one is under way. */
    fun pickLanguage(lang: Language) = startSession(lang, resume = null)

    /** Whether there is a saved conversation to continue. */
    var canContinue by mutableStateOf(SavedConversation.load(app.filesDir) != null)
        private set

    /** Continues the saved conversation exactly where it was left. */
    fun continueConversation() {
        val saved = SavedConversation.load(getApplication<Application>().filesDir)
        val lang = saved?.let { s -> LANGUAGES.firstOrNull { it.code == s.languageCode } }
        if (saved == null || lang == null) {
            canContinue = false
            return
        }
        scenario = saved.scenario
        register = saved.register
        startSession(lang, resume = saved)
    }

    /** Saves the conversation on screen, replacing the saved one. */
    private fun saveConversation() {
        val lang = language ?: return
        if (screen != Screen.Conversation) return
        val d = drill
        val saved = SavedConversation(
            languageCode = lang.code,
            scenario = scenario,
            register = register,
            chat = chat.toList(),
            history = tutor?.history.orEmpty(),
            drillPhrase = d?.phrase,
            drillWords = d?.refWords.orEmpty(),
            drillAttempts = drillAttempts,
            editableIndex = editableIndex,
        )
        val dir = getApplication<Application>().filesDir
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { saved.save(dir) }.onFailure { diagnostics.log("Couldn't save the conversation: ${it.message}") }
        }
        canContinue = true
    }

    /** From the home page: choose the scenario and language for a new conversation. */
    fun startNewConversation() {
        register = Register.Formal
        screen = Screen.PickLanguage
    }

    /** Goes back to the home page, stopping whatever the conversation is doing. */
    fun goHome() {
        viewModelScope.launch {
            stopConversationWork()
            lookup = null
            screen = Screen.Home
        }
    }

    /**
     * Opens a conversation in [lang]: a new one, or [resume] (the saved one). The page opens straight
     * away; Gemma and the voice get ready behind it, and talking waits until they are.
     */
    private fun startSession(lang: Language, resume: SavedConversation?) {
        error = null
        viewModelScope.launch {
            // Stop anything left from the last conversation before its voice is released.
            stopConversationWork()
            conversationScope.launch {
                val voice = try {
                    // The language's first-choice voice, downloading it if need be (a language's voice
                    // can change, as Ukrainian's did); an older one already here is used only if that
                    // download fails, such as when offline.
                    store.installedVoice(lang, firstChoiceOnly = true) ?: try {
                        withProgress("Downloading the ${lang.englishName} voice") { report -> store.downloadVoice(lang, report) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        store.installedVoice(lang) ?: throw e
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    progress = null
                    error = "Could not start: ${e.message}"
                    return@launch
                }
                val openingKey = Tutor.openingKey(lang, scenario, register)
                val opening = if (resume != null) null else {
                    withContext(Dispatchers.IO) { openings.text(openingKey) }?.let { it to Tutor.parse(it) }
                }

                // Open the conversation straight away (with the saved opening, or the conversation
                // being continued).
                openDictionary(lang)
                language = lang
                chat.clear()
                resume?.let { chat.addAll(it.chat) }
                practice = null
                lastRecording = if (resume != null) withContext(Dispatchers.IO) { loadRecording() } else null
                if (resume == null) withContext(Dispatchers.IO) { recordingFile.delete() }
                startDrill(null)
                editableIndex = resume?.editableIndex
                busy = Busy.Starting
                screen = Screen.Conversation
                opening?.let { (_, turn) -> chat += ChatItem.Tutor(turn.reply, turn.english) }
                conversationScope.launch { checkDictionary(lang) }

                try {
                    diagnostics.step = "Starting a ${lang.englishName} session and loading the $voice voice"
                    coroutineScope {
                        // Gemma reads its instructions while the voice loads and the opening plays.
                        val started = async(Dispatchers.Default) {
                            val earlier = resume?.history
                                ?: opening?.let { listOf(Tutor.Turn(true, Tutor.startMessage(register)), Tutor.Turn(false, it.first)) }
                                ?: emptyList()
                            checkNotNull(tutor).startConversation(lang, scenario, earlier)
                            diagnostics.log("Gemma ready for the ${lang.englishName} session")
                        }
                        withContext(Dispatchers.Default) {
                            if (ttsVoice != voice) {
                                tts?.release()
                                tts = TextToSpeech(store, voice, lang.ttsRewrites, getApplication<Application>().cacheDir)
                                ttsVoice = voice
                                wordSoundsCache.clear()
                                diagnostics.log("Loaded the $voice voice")
                            }
                        }
                        if (resume != null) {
                            // Back to the phrase being practised, with the tries made so far.
                            resume.drillPhrase?.let { phrase ->
                                val audio = withContext(Dispatchers.Default) { checkNotNull(tts).synthesize(phrase) }
                                drill = Drill(phrase, audio, resume.drillWords)
                                drillAttempts = resume.drillAttempts
                            }
                            started.await()
                            diagnostics.step = null
                        } else if (opening != null) {
                            playOpening(openingKey, voice, opening.second.reply)
                            busy = Busy.Starting
                            started.await()
                            diagnostics.step = null
                        } else {
                            started.await()
                            diagnostics.step = null
                            runTurn(Tutor.startMessage(register), saveOpening = openingKey to voice)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    chat += ChatItem.Note("Something went wrong: ${e.message}")
                } finally {
                    diagnostics.step = null
                    busy = Busy.Idle
                }
            }
        }
    }

    // ---- Settings: which Gemma model the tutor runs ---------------------------------------------

    /** The Gemma model in use. */
    var gemma by mutableStateOf(store.gemma)
        private set

    /** The Gemma models on the phone. */
    var downloadedGemmas by mutableStateOf(Gemma.entries.filter(store::gemmaDownloaded).toSet())
        private set

    /** The Gemma model downloading, and how far along it is. */
    var gemmaDownloading by mutableStateOf<Gemma?>(null)
        private set
    var gemmaProgress by mutableStateOf<Progress?>(null)
        private set
    var settingsError by mutableStateOf<String?>(null)
        private set
    private var gemmaDownloadJob: Job? = null

    /** [model]'s size on the phone, if it's downloaded. */
    fun gemmaBytes(model: Gemma): Long? = store.gemmaFile(model)?.length()?.takeIf { model in downloadedGemmas }

    fun openSettings() {
        settingsError = null
        screen = Screen.Settings
    }

    fun closeSettings() {
        screen = Screen.Home
    }

    /** Switches the tutor to [model] (already downloaded), loading it in place of the one in use. */
    fun selectGemma(model: Gemma) {
        if (model == gemma || model !in downloadedGemmas) return
        viewModelScope.launch {
            stopConversationWork()
            tutor?.close()
            tutor = null
            store.gemma = model
            gemma = model
            loadModels(then = Screen.Settings)
        }
    }

    /** Downloads [model] (one not in use) so it can be selected. */
    fun downloadGemma(model: Gemma) {
        if (gemmaDownloading != null || model in downloadedGemmas) return
        settingsError = null
        gemmaDownloading = model
        gemmaDownloadJob = viewModelScope.launch {
            try {
                withProgress("Downloading ${model.title}", { gemmaProgress = it }) { report -> store.downloadGemma(model, report) }
                downloadedGemmas = downloadedGemmas + model
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                settingsError = "Download failed: ${e.message}"
            } finally {
                gemmaDownloading = null
            }
        }
    }

    /** Stops the Gemma download; what's downloaded so far is kept, so it picks up from there. */
    fun cancelGemmaDownload() {
        gemmaDownloadJob?.cancel()
        gemmaDownloadJob = null
    }

    /** Deletes [model] (one not in use) from the phone. */
    fun deleteGemma(model: Gemma) {
        if (model == gemma || model == gemmaDownloading || model !in downloadedGemmas) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.deleteGemma(model) }
            downloadedGemmas = downloadedGemmas - model
        }
    }

    /** Stops a voice download in progress, staying on the language list. */
    fun cancelVoiceDownload() {
        viewModelScope.launch {
            stopConversationWork()
            progress = null
        }
    }

    /** Goes back to the language list at any time, stopping whatever the conversation is doing. */
    fun newConversation() {
        viewModelScope.launch {
            stopConversationWork()
            startDrill(null)
            editableIndex = null
            lookup = null
            register = Register.Formal
            screen = Screen.PickLanguage
        }
    }

    /**
     * Stops the current conversation's work: Gemma's reply, speech playback, recording, background
     * lookups and any download, and waits until it has all actually stopped.
     */
    private suspend fun stopConversationWork() {
        practice = null
        practiceTarget = null
        tutor?.cancelGeneration()
        tts?.stopPlayback()
        if (busy == Busy.Listening) withContext(Dispatchers.Default) { recorder.stop() }
        conversationJobs.children.toList().also { jobs -> jobs.forEach { it.cancel() } }.joinAll()
        pauseBackground()
        // Clear the lookup queue too, or the background lookups would restart once the app is idle.
        pendingSentences.clear()
        pendingWord = null
        busy = Busy.Idle
        progress = null
    }

    fun startTalking() {
        if (busy != Busy.Idle) return
        busy = Busy.Listening
        recorder.start()
        viewModelScope.launch { pauseBackground() }
    }

    /**
     * Runs a conversation action: marks the app as [state], pauses the background lookups, reports
     * any error as a note, and goes back to idle when done. Leaving the conversation cancels it.
     */
    private fun launchAction(state: Busy, action: suspend () -> Unit) {
        busy = state
        conversationScope.launch {
            try {
                pauseBackground()
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                chat += ChatItem.Note("Something went wrong: ${e.message}")
            } finally {
                diagnostics.step = null
                busy = Busy.Idle
            }
        }
    }

    fun stopTalking() {
        if (busy != Busy.Listening) return
        launchAction(Busy.Transcribing) {
            val audio = withContext(Dispatchers.Default) { Audio(recorder.stop(), SAMPLE_RATE) }
            val d = drill
            val target = practiceTarget
            practiceTarget = null
            when {
                target != null -> practiceTry(target, audio)
                d == null -> {
                    // Learning mode, waiting for English: only the words matter here.
                    val english = withContext(Dispatchers.Default) { checkNotNull(stt).transcribe(audio.samples) }
                    if (english.isNotBlank()) translate(english)
                }
                else -> checkAttempt(d, audio)
            }
        }
    }

    /** Learning mode: the learner typed what they want to say. */
    fun submitEnglish(text: String) {
        if (busy != Busy.Idle || drill != null || text.isBlank()) return
        launchAction(Busy.Thinking) { translate(text.trim()) }
    }

    /** Learning mode: plays the phrase again. */
    fun hearAgain(slow: Boolean) {
        val d = drill ?: return
        if (busy != Busy.Idle) return
        launchAction(Busy.Speaking) {
            withContext(Dispatchers.Default) {
                val voice = checkNotNull(tts)
                val audio = if (!slow) d.audio else d.slowAudio ?: voice.synthesize(d.phrase, SLOW_SPEED).also { d.slowAudio = it }
                voice.play(audio)
            }
        }
    }

    /** What ZIPA hears when Piper says [text]: the sounds the app expects for it. */
    private fun soundsOf(text: String): List<String> = checkNotNull(zipa).sounds(checkNotNull(tts).synthesize(text))

    /** [soundsOf] for single words, remembered for the current voice (drills and lookups share them). */
    private val wordSoundsCache = ConcurrentHashMap<String, List<String>>()
    private fun wordSounds(word: String): List<String> = wordSoundsCache.getOrPut(word) { soundsOf(word) }

    // Word meanings are worked out in the background, a whole sentence at a time, as soon as a
    // phrase or reply appears. The background work only runs while the app is idle: it stops when
    // the learner starts talking or the app needs Gemma or Piper, and picks up again afterwards.
    private val pendingSentences = ArrayDeque<String>()
    private val doneSentences = mutableSetOf<String>()
    /** A tapped word the sentence lookup didn't cover, to look up on its own. */
    private var pendingWord: Pair<String, String>? = null
    private val triedAlone = mutableSetOf<Pair<String, String>>()
    private var backgroundJob: Job? = null

    private fun openDictionary(lang: Language) {
        dictionary?.close()
        dictionary = store.dictionaryFile(lang)?.let { file ->
            runCatching { Dictionary(file) }.onFailure { diagnostics.log("Dictionary: couldn't open (${it.message})") }.getOrNull()
        }
        dictionaryOffer = null
        lookupCache.clear()
        doneSentences.clear()
        triedAlone.clear()
    }

    /**
     * Looks for a newer dictionary for [lang]: downloads it straight away the first time, and
     * offers it once one is installed. Without a connection (or a published dictionary), does nothing.
     */
    private suspend fun checkDictionary(lang: Language) {
        val latest = try {
            store.latestDictionary(lang)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            diagnostics.log("Dictionary: couldn't check for one (${e.message})")
            null
        } ?: return
        when (store.dictionaryVersion(lang)) {
            latest.version -> Unit
            null -> installDictionary(lang, latest)
            else -> dictionaryOffer = latest
        }
    }

    /** Downloads the newer dictionary on offer. */
    fun updateDictionary() {
        val offer = dictionaryOffer ?: return
        val lang = language ?: return
        dictionaryOffer = null
        conversationScope.launch { installDictionary(lang, offer) }
    }

    private suspend fun installDictionary(lang: Language, info: DictionaryInfo) {
        dictionaryProgress = 0f
        try {
            diagnostics.log("Dictionary: downloading ${lang.englishName} ${info.version}")
            val title = "Downloading the ${lang.englishName} dictionary"
            keepDownloading(title) {
                store.downloadDictionary(lang, info) { done, total ->
                    dictionaryProgress = done.toFloat() / total
                    DownloadService.update(getApplication(), title, done, total)
                }
            }
            if (language == lang) {
                pauseBackground()
                openDictionary(lang)
                startBackground()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            chat += ChatItem.Note("Couldn't download the ${lang.englishName} dictionary: ${e.message}")
        } finally {
            dictionaryProgress = null
        }
    }

    /** The dictionary's description of [word], or null if it isn't there (or there is no dictionary). */
    private fun fromDictionary(word: String): Dictionary.Description? =
        runCatching { dictionary?.describe(word) }.getOrNull()

    private fun dictionaryLookup(word: String, sentence: String, d: Dictionary.Description, zipaSounds: String? = null): WordLookup {
        val sounds = d.ipa ?: zipaSounds
        return WordLookup(
            word, sentence,
            meaning = d.text,
            groups = d.groups,
            soundsLike = sounds?.let(Respell::fromIpa)?.ifEmpty { null },
            sounds = sounds,
            loading = false,
            fromDictionary = true,
        )
    }

    /** Queues [sentence]'s words to be explained in the background; [first] puts it at the front. */
    private fun queueLookups(sentence: String, first: Boolean = false) {
        if (sentence.isBlank() || sentence in doneSentences) return
        pendingSentences.remove(sentence)
        if (first) pendingSentences.addFirst(sentence) else pendingSentences.addLast(sentence)
        startBackground()
    }

    /** Whether the app is on screen. Background lookups only run while it is. */
    private var appVisible = true

    /** The app went off screen: stop the background lookups so a turn in progress gets the phone. */
    fun appHidden() {
        appVisible = false
        viewModelScope.launch { pauseBackground() }
    }

    /** The app is back on screen: the turn no longer needs keeping alive, and lookups can resume. */
    fun appShown() {
        appVisible = true
        KeepWorkingService.stop(getApplication())
        startBackground()
    }

    private fun startBackground() {
        if (backgroundJob?.isActive == true || busy != Busy.Idle || !appVisible) return
        if (pendingSentences.isEmpty() && pendingWord == null) return
        val lang = language ?: return
        diagnostics.log("Background lookups: start (${diagnostics.memorySummary()})")
        backgroundJob = viewModelScope.launch {
            while (true) {
                val word = pendingWord
                if (word != null) {
                    lookUpAlone(lang, word.first, word.second)
                    pendingWord = null
                    continue
                }
                val sentence = pendingSentences.firstOrNull() ?: break
                lookUpSentence(lang, sentence)
                pendingSentences.remove(sentence)
                doneSentences += sentence
            }
        }
    }

    /** Stops the background lookups; they resume the next time the app is idle. */
    private suspend fun pauseBackground() {
        val job = backgroundJob ?: return
        tutor?.cancelLookup()
        job.cancelAndJoin()
        backgroundJob = null
        diagnostics.log("Background lookups: paused")
    }

    /** Works out the meaning and sounds of every word in [sentence] and stores them. */
    private suspend fun lookUpSentence(lang: Language, sentence: String) {
        val words = tappableWords(sentence).distinctBy { it.lowercase() }
        // The dictionary first; espeak-ng only for words it has no IPA for, Gemma only for words it lacks.
        val known = words.associateWith { fromDictionary(it) }
        val sounds = withContext(Dispatchers.Default) {
            words.filter { known[it]?.ipa == null }.associateWith { w -> wordIpa(lang, w) }
        }
        val meanings = if (words.all { known[it] != null }) emptyMap() else try {
            Tutor.parseSentenceLookup(checkNotNull(tutor).lookUpSentence(lang, sentence))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyMap()
        }
        for (w in words) {
            val d = known[w]
            val wordSounds = sounds[w]
            lookupCache[w.lowercase() to sentence] = if (d != null) dictionaryLookup(w, sentence, d, wordSounds) else WordLookup(
                w, sentence,
                meaning = meanings[w.lowercase()],
                soundsLike = wordSounds?.let(Respell::fromIpa)?.ifEmpty { null },
                sounds = wordSounds,
                loading = false,
            )
        }
        refreshOpenLookup()
    }

    /**
     * [word]'s IPA for the word bubble when the dictionary has none: espeak-ng's, as the voice is given
     * it (ZIPA's hearing of the voice mishears it: "mår" came out /voːl/); ZIPA's only if espeak-ng
     * can't be used.
     */
    private fun wordIpa(lang: Language, word: String): String {
        val fixed = lang.ttsRewrites.fold(word) { t, (pattern, replacement) -> pattern.replace(t, replacement) }
        return runCatching { Phonemizer.ipa(store.espeakData, lang.espeakVoice, fixed) }.getOrNull()
            ?: SoundRecognizer.display(wordSounds(word))
    }

    /** Looks up one word on its own, for a tapped word the sentence lookup missed. */
    private suspend fun lookUpAlone(lang: Language, word: String, sentence: String) {
        val key = word.lowercase() to sentence
        triedAlone += key
        val sounds = lookupCache[key]?.sounds ?: withContext(Dispatchers.Default) { wordIpa(lang, word) }
        val result = try {
            val meaning = Tutor.parseLookup(checkNotNull(tutor).lookUp(lang, word, sentence))
            WordLookup(word, sentence, meaning, Respell.fromIpa(sounds).ifEmpty { null }, sounds, loading = false)
        } catch (e: CancellationException) {
            triedAlone -= key
            throw e
        } catch (e: Throwable) {
            WordLookup(
                word, sentence,
                soundsLike = Respell.fromIpa(sounds).ifEmpty { null },
                sounds = sounds,
                error = "Couldn't look this up: ${e.message}",
                loading = false,
            )
        }
        lookupCache[key] = result
        refreshOpenLookup()
    }

    /** Updates the open bubble once its word has been looked up. */
    private fun refreshOpenLookup() {
        val open = lookup ?: return
        val key = open.word.lowercase() to open.sentence
        val found = lookupCache[key] ?: return
        if (found.meaning == null && found.error == null && key !in triedAlone) {
            lookup = found.copy(loading = true)
            pendingWord = open.word to open.sentence
        } else {
            lookup = found
        }
    }

    /**
     * Opens the bubble for a tapped [word] from [sentence]: its meaning in that sentence from Gemma,
     * and its sounds from ZIPA listening to Piper say it, with an English-style respelling of them.
     */
    fun lookUp(word: String, sentence: String) {
        val d = fromDictionary(word)
        if (d != null) {
            lookup = lookupCache[word.lowercase() to sentence] ?: dictionaryLookup(word, sentence, d)
            // Everything is from the dictionary already; only missing IPA needs the background work.
            if (d.ipa != null) return
        } else {
            lookup = WordLookup(word, sentence)
        }
        if (sentence in doneSentences) {
            refreshOpenLookup()
            startBackground()
        } else {
            queueLookups(sentence, first = true)
        }
    }

    /** A word tapped in the conversation: opens its bubble and says it at normal speed. */
    fun openWord(word: String, sentence: String) {
        lookUp(word, sentence)
        hearWord(word, slow = false)
    }

    fun closeLookup() {
        lookup = null
    }

    /** Has Piper say [word] (or any text), slowly if [slow]. */
    fun hearWord(word: String, slow: Boolean) {
        if (busy != Busy.Idle || word.isBlank()) return
        launchAction(Busy.Speaking) {
            withContext(Dispatchers.Default) {
                val voice = checkNotNull(tts)
                voice.play(voice.synthesize(word, if (slow) SLOW_SPEED else 1.0f))
            }
        }
    }

    /** Has Piper say raw sounds, e.g. the sounds ZIPA heard. [sounds] is the displayed IPA text. */
    fun saySounds(sounds: String) {
        if (busy != Busy.Idle || sounds.isBlank()) return
        launchAction(Busy.Speaking) {
            withContext(Dispatchers.Default) { checkNotNull(tts).speakSounds(SoundRecognizer.symbols(sounds)) }
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
        startDrill(null)
        launchAction(Busy.Thinking) { translate(english, addLine = false) }
    }

    /** Learning mode: removes the English line at [index] and its phrase, to say something else. */
    fun deleteEnglish(index: Int) {
        if (busy != Busy.Idle || index != editableIndex) return
        while (chat.size > index) chat.removeAt(chat.lastIndex)
        startDrill(null)
        editableIndex = null
    }

    /** Learning mode: moves on without getting the phrase right (offered after [SKIP_AFTER_FAILS] failed tries). */
    fun skip() {
        val d = drill ?: return
        if (busy != Busy.Idle) return
        chat += ChatItem.Note("Skipped")
        startDrill(null)
        editableIndex = null
        launchAction(Busy.Thinking) { runTurn(Tutor.learningSaid(d.phrase, register)) }
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

    /** Grades an attempt at [phrase]: what was heard, the sounds heard, the result and the recording. */
    private suspend fun grade(phrase: String, refWords: List<RefWord>, audio: Audio): Graded {
        val (transcript, sounds) = hear(audio)
        var result = Matching.check(phrase, transcript, refWords, sounds)
        if (!result.passed && transcript.isNotBlank()) {
            // Sound-alike check: Piper says what Omnilingual heard and ZIPA listens, so a word
            // spelled differently but pronounced the same (like "sava" for "Ça va") still counts.
            val transcriptSounds = withContext(Dispatchers.Default) { soundsOf(transcript) }
            result = Matching.check(phrase, transcript, refWords, sounds, transcriptSounds)
        }
        // The heard sounds, split into words by how they lined up with the phrase's words.
        val heardText = result.words.map { it.heard }.filter { it.isNotEmpty() }.joinToString(" ")
            .ifEmpty { SoundRecognizer.display(sounds) }
        return Graded(transcript, heardText, result, Recording(audio))
    }

    private class Graded(val transcript: String, val heardText: String, val result: AttemptResult, val recording: Recording)

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
        val raw = checkNotNull(tutor).translate(checkNotNull(language), register, english) { partial ->
            chat[index] = ChatItem.Say(english, Tutor.parse(partial).say ?: "")
        }
        val phrase = Tutor.parse(raw).say ?: ""
        chat[index] = ChatItem.Say(english, phrase)
        if (phrase.isBlank()) {
            chat[index] = ChatItem.Note("Couldn't translate that. Try saying it another way.")
            return
        }
        busy = Busy.Preparing
        diagnostics.step = "Preparing the phrase with Piper and ZIPA"
        val voice = checkNotNull(tts)
        val audio = withContext(Dispatchers.Default) { voice.synthesize(phrase) }
        val refWords = coroutineScope {
            // Expected sounds: ZIPA listens to Piper saying the whole phrase (the audio you hear).
            // Each word said on its own only guides where the word boundaries fall. The sounds shown
            // are the phonemes Piper was given (espeak-ng's). This runs while the phrase plays.
            val expected = async(Dispatchers.Default) {
                val isolated = Matching.words(phrase).map { w -> RefWord(w, wordSounds(w)) }
                withShownSounds(phrase, Matching.segmentReference(checkNotNull(zipa).sounds(audio), isolated))
            }
            busy = Busy.Speaking
            withContext(Dispatchers.Default) { voice.play(audio) }
            busy = Busy.Preparing
            expected.await()
        }
        startDrill(Drill(phrase, audio, refWords))
        queueLookups(phrase)
    }

    /**
     * [refWords] with the sounds to show: espeak-ng's phonemes for each word of [phrase], as Piper is
     * given them (after the language's fixes for text espeak-ng misreads). Unchanged if espeak-ng
     * can't be used.
     */
    private fun withShownSounds(phrase: String, refWords: List<RefWord>): List<RefWord> {
        val lang = language ?: return refWords
        fun fixed(text: String) = lang.ttsRewrites.fold(text) { t, (pattern, replacement) -> pattern.replace(t, replacement) }
        val shown = runCatching {
            Phonemizer.words(store.espeakData, lang.espeakVoice, fixed(phrase), refWords.map { fixed(it.word) })
        }.onFailure { diagnostics.log("espeak-ng: ${it.message}") }.getOrNull()
        if (shown == null || shown.size != refWords.size) return refWords
        return refWords.mapIndexed { i, r -> r.copy(shown = shown[i].ifEmpty { r.sounds }) }
    }

    private suspend fun checkAttempt(d: Drill, audio: Audio) {
        val graded = grade(d.phrase, d.refWords, audio)
        val result = graded.result
        chat += ChatItem.Attempt(graded.transcript, graded.heardText, result, d.phrase)
        keepRecording(chat.lastIndex, graded.recording)
        if (result.passed) {
            startDrill(null)
            editableIndex = null
            runTurn(Tutor.learningSaid(d.phrase, register))
        } else {
            drillAttempts++
            busy = Busy.Speaking
            withContext(Dispatchers.Default) { tts?.play(d.audio) }
        }
    }

    private fun startDrill(d: Drill?) {
        drill = d
        drillAttempts = 0
    }

    /** Plays the saved opening [reply] for [key] (its saved audio, or Piper's if there is none yet). */
    private suspend fun playOpening(key: String, voice: String, reply: String) {
        busy = Busy.Speaking
        diagnostics.step = "Speaking the saved opening with Piper"
        withContext(Dispatchers.Default) {
            val engine = checkNotNull(tts)
            val audio = openings.audio(key, voice) ?: engine.synthesize(reply).also { openings.saveAudio(key, voice, it) }
            engine.play(audio)
        }
        queueLookups(reply)
    }

    /**
     * Sends [message] to the tutor, shows the answer as it streams in and speaks the reply.
     * Then, as its own step written while the reply plays, the English translation of the reply,
     * which isn't kept in Gemma's conversation memory.
     * [saveOpening] (key, voice) saves the answer and its audio as the opening for those choices.
     */
    private suspend fun runTurn(
        message: String,
        saveOpening: Pair<String, String>? = null,
        remember: String = message,
    ) {
        busy = Busy.Thinking
        val index = chat.size
        chat += ChatItem.Tutor("")
        fun update(change: (ChatItem.Tutor) -> ChatItem.Tutor) {
            (chat.getOrNull(index) as? ChatItem.Tutor)?.let { chat[index] = change(it) }
        }
        try {
            val lang = checkNotNull(language)
            var reply = ""
            diagnostics.step = "Generating a reply with Gemma"
            checkNotNull(tutor).respond(message, remember).collect { raw ->
                reply = Tutor.parse(raw).reply
                update { it.copy(reply = reply) }
            }
            diagnostics.step = "Speaking the reply with Piper"
            val voice = checkNotNull(tts)
            val audio = withContext(Dispatchers.Default) { voice.synthesize(reply) }
            var english: String? = null
            coroutineScope {
                val extra = async {
                    try {
                        if (reply.isNotBlank()) {
                            val raw = checkNotNull(tutor).translateReply(lang, reply) { partial ->
                                update { it.copy(english = Tutor.parse(partial).english) }
                            }
                            english = Tutor.parse(raw).english
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        diagnostics.log("Couldn't add the English: ${e.message}")
                    }
                }
                busy = Busy.Speaking
                withContext(Dispatchers.Default) { voice.play(audio) }
                if (extra.isActive) busy = Busy.Translating
                extra.await()
            }
            if (saveOpening != null && reply.isNotBlank()) {
                openings.saveText(saveOpening.first, "REPLY: $reply" + english?.let { "\nENGLISH: $it" }.orEmpty())
                openings.saveAudio(saveOpening.first, saveOpening.second, audio)
            }
            queueLookups(reply)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            chat[index] = ChatItem.Tutor("(Something went wrong: ${e.message})")
        } finally {
            diagnostics.step = null
            busy = Busy.Idle
        }
    }

    companion object {
        /** Failed tries at a phrase before Skip is offered. */
        const val SKIP_AFTER_FAILS = 2

        /** Speaking rate for "Slow": 40% of normal speed. */
        private const val SLOW_SPEED = 0.4f

        /** How long the loading bar takes to move to a new amount. */
        const val LOADING_BAR_FILL_MS = 500
    }

    // ---- Practice window: an attempt word by word, with retries ---------------------------------

    /** A recording of an attempt, to play back. */
    class Recording(val audio: Audio)

    /**
     * The latest recording only: its attempt's place in [chat], the result it was graded as (so it
     * isn't shown with a different result) and the recording. Kept on the phone for Continue.
     */
    private var lastRecording: Triple<Int, Int, Recording>? = null
    private val recordingFile get() = File(getApplication<Application>().filesDir, "last-attempt.bin")

    private fun keepRecording(chatIndex: Int, recording: Recording, result: AttemptResult = (chat[chatIndex] as ChatItem.Attempt).result) {
        lastRecording = Triple(chatIndex, result.hashCode(), recording)
        val file = recordingFile
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val a = recording.audio
                val buf = ByteBuffer.allocate(12 + a.samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                buf.putInt(chatIndex).putInt(result.hashCode()).putInt(a.sampleRate)
                buf.asFloatBuffer().put(a.samples)
                file.writeBytes(buf.array())
            }
        }
    }

    private fun loadRecording(): Triple<Int, Int, Recording>? = runCatching {
        val buf = ByteBuffer.wrap(recordingFile.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val index = buf.int
        val hash = buf.int
        val rate = buf.int
        val samples = FloatArray(buf.remaining() / 4).also { buf.asFloatBuffer().get(it) }
        Triple(index, hash, Recording(Audio(samples, rate)))
    }.getOrNull()

    /** A word practised on its own in the window: the latest and best scores (percent) and what was heard. */
    data class WordTry(val latest: Int, val best: Int, val heard: String)

    /**
     * The practice window for the attempt at [chatIndex]: the result shown (the attempt's, or a
     * retry's), the word picked, what happened on the last whole-phrase retry, and the words
     * practised on their own.
     */
    data class Practice(
        val chatIndex: Int,
        val phrase: String,
        val transcript: String,
        val result: AttemptResult,
        val position: Int,
        val hasRecording: Boolean,
        val note: String? = null,
        val wordTries: Map<Int, WordTry> = emptyMap(),
    )

    var practice by mutableStateOf<Practice?>(null)
        private set

    /** What the next recording is for, when it's made in the practice window. */
    enum class PracticeTarget { Phrase, Word }
    private var practiceTarget: PracticeTarget? = null

    /** The place in [chat] of the latest attempt: the only one whose recording is kept. */
    private fun latestAttemptIndex() = chat.indexOfLast { it is ChatItem.Attempt }

    private fun recordingFor(p: Practice): Recording? =
        lastRecording?.takeIf {
            it.first == p.chatIndex && it.first == latestAttemptIndex() && it.second == p.result.hashCode()
        }?.third

    /** Opens the practice window for the attempt at [index], on its first wrong word. */
    fun openPractice(index: Int) {
        val item = chat.getOrNull(index) as? ChatItem.Attempt ?: return
        if (item.result.words.isEmpty()) return
        val first = item.result.words.indexOfFirst { !it.passed }.coerceAtLeast(0)
        val p = Practice(index, item.phrase, item.heard, item.result, first, hasRecording = false)
        practice = p.copy(hasRecording = recordingFor(p) != null)
    }

    fun closePractice() {
        practice = null
    }

    fun practiceSelect(position: Int) {
        val p = practice ?: return
        practice = p.copy(position = Math.floorMod(position, p.result.words.size.coerceAtLeast(1)))
    }

    /** Plays back the learner's whole try. */
    fun playMyRecording() {
        val p = practice ?: return
        val clip = recordingFor(p)?.audio ?: return
        if (busy != Busy.Idle) return
        launchAction(Busy.Speaking) { withContext(Dispatchers.Default) { checkNotNull(tts).play(clip) } }
    }

    /** Starts recording in the practice window: the whole phrase again, or just the picked word. */
    fun startPracticeTalking(target: PracticeTarget) {
        if (practice == null || busy != Busy.Idle) return
        practiceTarget = target
        startTalking()
    }

    /** Grades a try made in the practice window. Nothing is said back, and the conversation doesn't move on. */
    private suspend fun practiceTry(target: PracticeTarget, audio: Audio) {
        val p = practice ?: return
        val refWords = p.result.words.map { RefWord(it.word, it.reference, it.expected) }
        when (target) {
            PracticeTarget.Phrase -> {
                val graded = grade(p.phrase, refWords, audio)
                val r = graded.result
                val old = chat.getOrNull(p.chatIndex) as? ChatItem.Attempt
                // A try that gets the phrase right turns a wrong attempt into a right one; otherwise a
                // try replaces the attempt only if it scores better, and never turns a right one wrong.
                val better = old != null && when {
                    r.passed != old.result.passed -> r.passed
                    else -> r.wordScore + r.soundScore > old.result.wordScore + old.result.soundScore
                }
                if (better && old != null) chat[p.chatIndex] = old.copy(heard = graded.transcript, sounds = graded.heardText, result = r)
                // Only the latest attempt keeps a recording, so retries of older ones aren't kept.
                val latest = p.chatIndex == latestAttemptIndex()
                if (latest) keepRecording(p.chatIndex, graded.recording, r)
                // Getting the phrase being practised right here counts as getting it right: the
                // window closes and the conversation moves on, as from the main button.
                val d = drill
                val current = d != null && d.phrase == p.phrase &&
                    chat.drop(p.chatIndex + 1).none { it is ChatItem.Say }
                if (current && r.passed && old?.result?.passed == false) {
                    practice = null
                    startDrill(null)
                    editableIndex = null
                    runTurn(Tutor.learningSaid(p.phrase, register))
                    return
                }
                val scores = "Words ${(r.wordScore * 100).toInt()}% · Sounds ${(r.soundScore * 100).toInt()}%"
                practice = p.copy(
                    transcript = graded.transcript,
                    result = r,
                    hasRecording = latest,
                    note = when {
                        better && r.passed && old?.result?.passed == false -> "Correct! $scores. The conversation now shows it as correct."
                        better -> "New best: $scores. Saved to the conversation."
                        else -> "This try: $scores. Your best stays in the conversation."
                    },
                )
            }
            PracticeTarget.Word -> {
                val ref = refWords.getOrNull(p.position) ?: return
                val sounds = withContext(Dispatchers.Default) { checkNotNull(zipa).sounds(audio) }
                val (score, heard) = Matching.checkWord(ref, sounds)
                val percent = (score * 100).roundToInt()
                val before = p.wordTries[p.position]
                practice = p.copy(wordTries = p.wordTries + (p.position to WordTry(percent, maxOf(percent, before?.best ?: 0), heard)))
            }
        }
    }

    // Startup comes last, after every field: viewModelScope runs coroutines straight away, so
    // anything started here during construction must not find a field still uninitialized.
    init {
        // Whenever the app becomes idle: resume background lookups, and save the conversation so
        // it can be continued later.
        viewModelScope.launch {
            snapshotFlow { busy }.collect {
                if (it == Busy.Idle) {
                    // A turn finished while the learner was away: nothing more to keep alive.
                    KeepWorkingService.stop(getApplication())
                    startBackground()
                    saveConversation()
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) { runCatching { store.deleteUnusedVoices() } }
        crashReport = diagnostics.takeCrashReport()
        when {
            crashReport != null -> screen = Screen.Crashed
            store.coreReady() -> loadModels()
            else -> screen = Screen.Setup
        }
    }

    override fun onCleared() {
        dictionary?.close()
        tutor?.close()
        stt?.release()
        zipa?.release()
        tts?.release()
    }
}

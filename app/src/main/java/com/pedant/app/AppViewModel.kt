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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
    data object Crashed : Screen
    data object Home : Screen
    data object PickLanguage : Screen
    data object Conversation : Screen
    data object Settings : Screen
    /** The quiz on the last conversation, a page of its own. */
    data object Quiz : Screen
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
    val sounds: String? = null,
    val error: String? = null,
    val loading: Boolean = true,
    /** The IPA isn't the dictionary's but generated (espeak-ng's), and is labelled so. */
    val soundsGenerated: Boolean = true,
    /** The dictionary's meanings, a group per part of speech, to lay out; [meaning] is the same as text. */
    val groups: List<Dictionary.Group>? = null,
) {
    /** [sounds] respelled the English way. */
    val soundsLike: String? get() = sounds?.let(Respell::fromIpa)?.ifEmpty { null }

    /** The meaning comes from the downloaded dictionary, not Gemma (whose meanings are labelled generated). */
    val fromDictionary: Boolean get() = groups != null
}

sealed interface ChatItem {
    /** What the learner typed, in English. */
    data class User(val text: String) : ChatItem
    /** What the learner said in the language, as the speech recognizer heard it (Free talk). */
    data class Spoken(val text: String) : ChatItem
    data class Tutor(val reply: String, val english: String? = null) : ChatItem
    /** The phrase to say, translated from what the learner wanted to say. */
    data class Say(val english: String, val phrase: String, val note: TutorNote? = null) : ChatItem
    /** One try at saying [phrase]. */
    data class Attempt(val heard: String, val sounds: String, val result: AttemptResult, val phrase: String) : ChatItem
    data class Note(val text: String) : ChatItem
}

/** The phrase being practised, its audio and its expected sounds per word. */
private class Drill(val phrase: String, val audio: Audio, val refWords: Deferred<List<RefWord>>) {
    /** The phrase said slowly, made the first time it's asked for. */
    var slowAudio: Audio? = null
}

data class Progress(val done: Long, val total: Long, val label: String)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    init {
        Instructions.load(app)
        loadLanguages(app)
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

    /**
     * Basic or Independent: the conversation's band. Chosen in New conversation and remembered for
     * the next one; a continued conversation keeps its own.
     */
    var band by mutableStateOf(Band.entries.firstOrNull { it.name == uiPrefs.getString("band", null) } ?: Band.Basic)
        private set

    /** Chooses the band for a new conversation. */
    fun chooseBand(chosen: Band) {
        band = chosen
        uiPrefs.edit().putString("band", chosen.name).apply()
    }

    /**
     * The learner's own language, chosen in Settings (English unless chosen otherwise): what they
     * say before it's translated, and what translations, meanings from Gemma and notes are in.
     * A new conversation takes it; one under way keeps the own language it started with ([own]).
     */
    var ownSetting by mutableStateOf(
        LANGUAGES.firstOrNull { it.code == uiPrefs.getString("ownLanguage", DEFAULT_OWN) } ?: LANGUAGES.first { it.code == DEFAULT_OWN },
    )
        private set

    fun chooseOwnLanguage(lang: Language) {
        ownSetting = lang
        uiPrefs.edit().putString("ownLanguage", lang.code).apply()
    }

    /** The learner's own language in the conversation on screen. */
    var own by mutableStateOf(ownSetting)
        private set

    /** Beginner or Intermediate: how much help there is with each phrase. Remembered. */
    var mode by mutableStateOf(Mode.entries.firstOrNull { it.name == uiPrefs.getString("mode", null) } ?: Mode.Intermediate)
        private set

    /** Changes the mode. A phrase already under way carries on as it was; the next one follows it. */
    fun chooseMode(chosen: Mode) {
        mode = chosen
        uiPrefs.edit().putString("mode", chosen.name).apply()
    }

    /**
     * Beginner's word-by-word step: the phrase's words, the one being said ([position]), how many
     * tries at it have failed, the sounds heard in the last try and a line about what just happened.
     */
    data class WordWalk(
        val phrase: String,
        val words: List<RefWord>,
        val position: Int,
        val fails: Int = 0,
        val heard: String = "",
        val note: String? = null,
        /** The word's meaning, IPA and respelling, as the word bubble has them; null until found. */
        val info: WordLookup? = null,
    ) {
        val word: RefWord get() = words[position]
    }

    /**
     * The word-by-word step under way, shown as a page of its own in place of the conversation;
     * null when there is none. It can't be closed: only each word said right (or skipped after
     * [SKIP_AFTER_FAILS] failed tries) moves it on. Leaving the conversation keeps the word
     * reached, and continuing comes back to it.
     */
    var walk by mutableStateOf<WordWalk?>(null)
        private set

    /** Whether the talking now under way is a try at the word-by-word step's word. */
    private var walkTalking = false

    /** How the phrase to say is given: by voice, as text, or both. Remembered. */
    var phraseGuide by mutableStateOf(
        PhraseGuide.entries.firstOrNull { it.name == uiPrefs.getString("phraseGuide", null) } ?: PhraseGuide.VoiceAndText,
    )
        private set

    fun choosePhraseGuide(guide: PhraseGuide) {
        phraseGuide = guide
        uiPrefs.edit().putString("phraseGuide", guide.name).apply()
        // A phrase already hidden shows again; a change to Voice only starts with the next phrase.
        if (guide != PhraseGuide.VoiceOnly) hiddenPhraseIndex = null
    }

    /**
     * The chat index of the phrase whose text is hidden (Voice only): from when it's translated
     * until the first try at it, or until it's skipped or dropped.
     */
    var hiddenPhraseIndex by mutableStateOf<Int?>(null)
        private set

    /** Whether the voice says the phrase: after it's translated, after a failed try, and on request. */
    val phraseSpoken: Boolean get() = phraseGuide != PhraseGuide.TextOnly

    /** Whether the conversation's footer is folded away, leaving only Hold to talk. Remembered. */
    var dockCollapsed by mutableStateOf(uiPrefs.getBoolean("dockCollapsed", false))
        private set

    fun toggleDock() {
        dockCollapsed = !dockCollapsed
        uiPrefs.edit().putBoolean("dockCollapsed", dockCollapsed).apply()
    }
    private var drill by mutableStateOf<Drill?>(null)
    /** The phrase to repeat, or null while waiting for the next English line. */
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
     * The chat index of the English line whose phrase is being practised. It can be
     * edited (from its menu) until the phrase is said or skipped.
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
            drillPhrase == null -> "Hold the button and say in ${own.englishName} what you want to say, or type it"
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

    /** Loads one model, recording how long it took (the log adds the memory before and after). */
    private fun <T> timedLoad(name: String, load: () -> T): T {
        loadingStep = name
        diagnostics.step = "Loading $name"
        val start = System.currentTimeMillis()
        val result = load()
        diagnostics.log("Loading $name: done in ${(System.currentTimeMillis() - start) / 1000.0} s")
        return result
    }

    /**
     * Opens [then] straight away and loads Gemma (on the CPU) and ZIPA behind it, whichever aren't
     * loaded; what needs them waits with [modelsLoaded]. The speech recognizer isn't loaded here: it
     * loads each time the learner starts talking (see [SpeechToText]).
     */
    private fun loadModels(then: Screen = Screen.Home) {
        error = null
        screen = then
        val earlier = modelsLoading
        modelsLoading = viewModelScope.launch {
            earlier?.join()
            // Only a full load is a fair guide for next time; otherwise the files' size is.
            val loadingAll = tutor == null && zipa == null
            val growthKey = "loading-${store.gemma.name}"
            val startBytes = residentBytes()
            val files = listOfNotNull(
                store.gemmaFile().takeIf { tutor == null },
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
                    if (stt == null) stt = SpeechToText(store, diagnostics::log)
                    if (zipa == null) zipa = timedLoad("ZIPA") { SoundRecognizer(store, diagnostics::log) }
                    diagnostics.step = null
                    loadingStep = null
                }
                ticker.cancel()
                (residentBytes() - startBytes).takeIf { loadingAll && it > 0 }?.let { loadPrefs.edit().putLong(growthKey, it).apply() }
                loadProgress = 1f
            } catch (e: Throwable) {
                ticker.cancel()
                diagnostics.step = null
                loadingStep = null
                error = "Could not load the models: ${e.message}"
                screen = Screen.Setup
            }
        }
    }

    /** Gemma and ZIPA being loaded, if they are or have been. */
    private var modelsLoading: Job? = null

    /** Waits until Gemma and ZIPA are loaded. */
    private suspend fun modelsLoaded() {
        modelsLoading?.join()
        check(tutor != null && zipa != null) { "the models couldn't be loaded" }
    }

    /** Starts a new conversation in [lang]; the saved one is replaced once this one is under way. */
    fun pickLanguage(lang: Language) = startSession(lang, resume = null)

    /**
     * What the saved conversation is, for the home page ("French · Coffee with a friend · Casual"),
     * or null if there's none to continue.
     */
    var continueSummary by mutableStateOf(SavedConversation.load(app.filesDir)?.let(::summary))
        private set

    /** Whether there is a saved conversation to continue. */
    val canContinue: Boolean get() = continueSummary != null

    private fun summary(lang: Language?, scenario: Scenario, register: Register): String? = lang?.let {
        listOfNotNull(it.englishName, scenario.title.takeIf { scenario != Scenario.Default }, register.title).joinToString(" · ")
    }

    /** [saved]'s summary; "Quiz: …" while its quiz is under way, since continuing goes back to the quiz. */
    private fun summary(saved: SavedConversation): String? =
        summary(LANGUAGES.firstOrNull { it.code == saved.languageCode }, saved.scenario, saved.register)
            ?.let { if (saved.quiz?.finished == false) "Quiz: $it" else it }

    /** Continues the saved conversation exactly where it was left. */
    fun continueConversation() {
        val saved = SavedConversation.load(getApplication<Application>().filesDir)
        val lang = saved?.let { s -> LANGUAGES.firstOrNull { it.code == s.languageCode } }
        if (saved == null || lang == null) {
            continueSummary = null
            return
        }
        // A quiz left part way is picked up where it was left.
        if (saved.quiz?.finished == false) {
            openQuiz(saved)
            return
        }
        scenario = saved.scenario
        register = saved.register
        band = saved.band
        startSession(lang, resume = saved)
    }

    /** Saves the conversation on screen, replacing the saved one. */
    private fun saveConversation() {
        if (screen != Screen.Conversation) return
        currentConversation()?.let(::writeSaved)
    }

    /** One write at a time, in order, so a later save never lands before an earlier one. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val savingDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Writes [saved] in place of the saved conversation. */
    private fun writeSaved(saved: SavedConversation) {
        val dir = getApplication<Application>().filesDir
        viewModelScope.launch(savingDispatcher) {
            runCatching { saved.save(dir) }.onFailure { diagnostics.log("Couldn't save the conversation: ${it.message}") }
        }
        continueSummary = summary(saved)
    }

    /** The conversation on screen as it would be saved, or null if there's none. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun currentConversation(): SavedConversation? {
        val lang = language ?: return null
        val d = drill
        return SavedConversation(
            languageCode = lang.code,
            scenario = scenario,
            register = register,
            band = band,
            chat = chat.toList(),
            history = tutor?.history.orEmpty(),
            drillPhrase = d?.phrase,
            // Left out while they're still being worked out; they're worked out again on continuing.
            drillWords = d?.refWords?.takeIf { it.isCompleted }?.let { runCatching { it.getCompleted() }.getOrNull() }.orEmpty(),
            drillAttempts = drillAttempts,
            editableIndex = editableIndex,
            walkPosition = walk?.position,
            walkFails = walk?.fails ?: 0,
            ownCode = own.code,
            quizOfferedAt = quizOfferedAt,
        )
    }

    /**
     * From the home page: choose the scenario and language for a new conversation. If the saved
     * conversation has a quiz due, it's offered first.
     */
    fun startNewConversation() {
        val saved = SavedConversation.load(getApplication<Application>().filesDir)
        if (saved != null && quizDue(saved)) {
            quizOffer = QuizOffer(saved, fromConversation = false)
            return
        }
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

    /** How a conversation starts. */
    private sealed interface Start {
        /** The saved conversation, continued where it was left. */
        class Continue(val saved: SavedConversation) : Start

        /** A new one that opens with a line written earlier: shipped with the app, or saved on the phone. */
        class Opened(val key: String, val opening: Opening) : Start

        /** A new one that Gemma opens now; its line is saved under [key] for next time. */
        class Fresh(val key: String) : Start
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
                val voice = voiceFor(lang) ?: return@launch
                // A continued conversation keeps the own language it started with; a new one takes the setting.
                own = resume?.let { r -> LANGUAGES.firstOrNull { it.code == r.ownCode } } ?: ownSetting
                quizOfferedAt = resume?.quizOfferedAt ?: 0
                val start = when {
                    resume != null -> Start.Continue(resume)
                    else -> {
                        val key = Tutor.openingKey(lang, scenario, register, own, band)
                        withContext(Dispatchers.IO) { openings.get(key) }?.let { Start.Opened(key, it) } ?: Start.Fresh(key)
                    }
                }
                showConversation(lang, start)
                try {
                    diagnostics.step = "Starting a ${lang.englishName} session and loading the $voice voice"
                    coroutineScope {
                        // Gemma reads its instructions while the voice loads and the opening plays.
                        val gemmaReady = async(Dispatchers.Default) {
                            // Still loading, if the conversation was opened soon after the app.
                            modelsLoaded()
                            val earlier = when (start) {
                                is Start.Continue -> start.saved.history
                                is Start.Opened -> listOf(
                                    Tutor.Turn(true, Instructions.start(register)),
                                    Tutor.Turn(false, "REPLY: ${start.opening.reply}"),
                                )
                                is Start.Fresh -> emptyList()
                            }
                            checkNotNull(tutor).startConversation(lang, scenario, own, band, earlier)
                            diagnostics.log("Gemma ready for the ${lang.englishName} session")
                        }
                        loadVoice(lang, voice)
                        when (start) {
                            is Start.Continue -> {
                                // Back to the phrase being practised, with the tries made so far.
                                modelsLoaded()
                                start.saved.drillPhrase?.let { phrase ->
                                    drill = withContext(Dispatchers.Default) {
                                        val audio = checkNotNull(tts).synthesize(phrase)
                                        // Words that weren't saved, or were saved before they were split as
                                        // they are now (Mandarin's, by character), are worked out again.
                                        val saved = start.saved.drillWords
                                        val words = if (saved.map { it.word } == wordsOf(phrase)) saved else expectedSounds(phrase, audio)
                                        Drill(phrase, audio, CompletableDeferred(words))
                                    }
                                    drillAttempts = start.saved.drillAttempts
                                    // Back to the word reached in the word-by-word step, if one was under way.
                                    val words = checkNotNull(drill).refWords.await()
                                    start.saved.walkPosition?.takeIf { it in words.indices }?.let { at ->
                                        walk = WordWalk(phrase, words, at, fails = start.saved.walkFails)
                                        refreshWalkInfo()
                                    }
                                }
                                gemmaReady.await()
                            }
                            is Start.Opened -> {
                                playOpening(start.key, voice, start.opening.reply)
                                busy = Busy.Starting
                                gemmaReady.await()
                            }
                            is Start.Fresh -> {
                                gemmaReady.await()
                                runTurn(Instructions.start(register), saveOpening = start.key to voice)
                            }
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

    /** [lang]'s voice, downloaded first if it isn't here; null (with [error] set) if that fails. */
    private suspend fun voiceFor(lang: Language): String? = try {
        store.installedVoice(lang)
            ?: withProgress("Downloading the ${lang.englishName} voice") { report -> store.downloadVoice(lang, report) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        error = "Could not start: ${e.message}"
        null
    }

    /** Shows the conversation page straight away, with what there is to show before Gemma is ready. */
    private suspend fun showConversation(lang: Language, start: Start) {
        val saved = (start as? Start.Continue)?.saved
        openDictionary(lang)
        language = lang
        chat.clear()
        saved?.let { chat.addAll(it.chat) }
        practice = null
        lastRecording = withContext(Dispatchers.IO) {
            if (saved != null) {
                loadRecording()
            } else {
                recordingFile.delete()
                null
            }
        }
        startDrill(null)
        editableIndex = saved?.editableIndex
        busy = Busy.Starting
        screen = Screen.Conversation
        if (start is Start.Opened) chat += ChatItem.Tutor(start.opening.reply, start.opening.english)
        conversationScope.launch { checkDictionary(lang) }
    }

    /** Loads [voice] for speaking [lang], unless it's the one already loaded. */
    private suspend fun loadVoice(lang: Language, voice: String) = withContext(Dispatchers.Default) {
        if (ttsVoice != voice) {
            tts?.release()
            tts = TextToSpeech(store, voice, lang::espeakText, getApplication<Application>().cacheDir)
            ttsVoice = voice
            wordSoundsCache.clear()
            synchronized(wordAudioKept) { wordAudioKept.clear() }
            diagnostics.log("Loaded the $voice voice")
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
    fun gemmaBytes(model: Gemma): Long? = if (model in downloadedGemmas) store.gemmaFile(model)?.length() else null

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
            // A load still under way finishes before its model is swapped.
            modelsLoading?.join()
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
        viewModelScope.launch { stopConversationWork() }
    }

    /**
     * Goes back to the language list at any time, stopping whatever the conversation is doing. If
     * the conversation has a quiz due, it's offered first.
     */
    fun newConversation() {
        val saved = currentConversation()
        if (saved != null && quizDue(saved)) {
            quizOffer = QuizOffer(saved, fromConversation = true)
            return
        }
        leaveForNewConversation()
    }

    /** Leaves the conversation on screen, saved, for the language list. */
    private fun leaveForNewConversation() {
        viewModelScope.launch {
            stopConversationWork()
            currentConversation()?.let(::writeSaved)
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
        // The word-by-word step stays, so leaving the conversation saves the word reached.
        walkTalking = false
        quizTalking = false
        tutor?.cancelGeneration()
        // A question for the quiz may be being made.
        tutor?.cancelLookup()
        tts?.stopPlayback()
        if (busy == Busy.Listening) {
            withContext(Dispatchers.Default) {
                recorder.stop()
                // Loaded when the talking started, and now not needed.
                stt?.release()
            }
        }
        conversationJobs.children.toList().also { jobs -> jobs.forEach { it.cancel() } }.joinAll()
        pauseBackground()
        // Clear the lookup queue too, or the background lookups would restart once the app is idle.
        pendingSentences.clear()
        pendingWord = null
        pendingNote = null
        busy = Busy.Idle
        progress = null
    }

    fun startTalking() {
        if (busy != Busy.Idle) return
        busy = Busy.Listening
        recorder.start()
        // The speech recognizer loads while the learner talks, ready for when they've finished.
        stt?.expectSpeech()
        // So does ZIPA, if it was released after long audio.
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { stt?.warm() }
            runCatching { zipa?.warm() }
        }
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
                val message = "Something went wrong: ${e.message}"
                if (screen == Screen.Quiz) quizError = message else chat += ChatItem.Note(message)
            } finally {
                diagnostics.step = null
                busy = Busy.Idle
            }
        }
    }

    fun stopTalking() {
        if (busy != Busy.Listening) return
        launchAction(Busy.Transcribing) {
            try {
                val audio = withContext(Dispatchers.Default) { Audio(recorder.stop(), SAMPLE_RATE) }
                val d = drill
                val target = practiceTarget
                practiceTarget = null
                val walking = walkTalking
                walkTalking = false
                val quizzing = quizTalking
                quizTalking = false
                when {
                    target != null -> practiceTry(target, audio)
                    quizzing -> quizTry(audio)
                    walking -> walkTry(audio)
                    d == null && mode == Mode.FreeTalk -> {
                        // Free talk: what was heard goes straight to the tutor, nothing to repeat.
                        val heard = withContext(Dispatchers.Default) { checkNotNull(stt).transcribe(audio.samples) }
                        if (heard.isNotBlank()) freeTalk(heard)
                    }
                    d == null -> {
                        // Waiting for English: only the words matter here.
                        val english = withContext(Dispatchers.Default) { checkNotNull(stt).transcribe(audio.samples) }
                        if (english.isNotBlank()) translate(english)
                    }
                    else -> checkAttempt(d, audio)
                }
            } finally {
                // The speech recognizer is released as soon as it has heard the learner; this covers
                // a try it never hears (a word on its own, which only ZIPA hears) or one that's stopped.
                viewModelScope.launch(Dispatchers.Default) { stt?.release() }
            }
        }
    }

    /** The learner typed what they want to say. */
    fun submitEnglish(text: String) {
        if (busy != Busy.Idle || drill != null || mode == Mode.FreeTalk || text.isBlank()) return
        launchAction(Busy.Thinking) { translate(text.trim()) }
    }

    /** Free talk: the learner said [heard] in the language, and the tutor answers it. */
    private suspend fun freeTalk(heard: String) {
        chat += ChatItem.Spoken(heard)
        queueLookups(heard)
        runTurn(Instructions.said(heard, register))
    }

    /** Plays the phrase again. */
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

    /**
     * Piper's audio of single words (at normal speed, or slow), for the current voice: the most
     * recent [WORD_AUDIO_KEPT], so a word that has been said once plays at once the next time.
     */
    private val wordAudioKept = object : LinkedHashMap<Pair<String, Boolean>, Audio>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Boolean>, Audio>) = size > WORD_AUDIO_KEPT
    }

    private fun wordAudio(word: String, slow: Boolean = false): Audio =
        synchronized(wordAudioKept) { wordAudioKept[word to slow] }
            ?: checkNotNull(tts).synthesize(word, if (slow) SLOW_SPEED else 1.0f)
                .also { synchronized(wordAudioKept) { wordAudioKept[word to slow] = it } }

    /**
     * [soundsOf] for single words, remembered for the current voice (drills and lookups share them).
     * The audio made for it is kept too, so the word can be played without making it again.
     */
    private val wordSoundsCache = ConcurrentHashMap<String, List<String>>()
    private fun wordSounds(word: String): List<String> =
        wordSoundsCache.getOrPut(word) { checkNotNull(zipa).sounds(wordAudio(word)) }

    // Word meanings are worked out in the background, a whole sentence at a time, as soon as a
    // phrase or reply appears. The background work only runs while the app is idle: it stops when
    // the learner starts talking or the app needs Gemma or Piper, and picks up again afterwards.
    private val pendingSentences = ArrayDeque<String>()
    private val doneSentences = mutableSetOf<String>()
    /** A phrase to look for a common expression in, for a Tutor's note (see [noteCountdown]). */
    private var pendingNote: String? = null

    /**
     * Translations of the learner's lines left until the next one is checked for a common expression,
     * so a Tutor's note comes every 5 to 8 phrases, at random. Remembered between conversations. At 0
     * each translation is checked until one has an expression.
     */
    private var noteCountdown = uiPrefs.getInt("noteCountdown", NOTE_EVERY.random())
        set(value) {
            field = value
            uiPrefs.edit().putInt("noteCountdown", value).apply()
        }

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

    private fun dictionaryLookup(word: String, sentence: String, d: Dictionary.Description, generatedSounds: String? = null): WordLookup {
        val sounds = d.ipa ?: generatedSounds
        return WordLookup(
            word, sentence,
            meaning = d.text,
            groups = d.groups,
            sounds = sounds,
            loading = false,
            soundsGenerated = d.ipa == null,
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
        if (pendingSentences.isEmpty() && pendingWord == null && pendingNote == null) return
        val lang = language ?: return
        diagnostics.log("Background lookups: start")
        backgroundJob = viewModelScope.launch {
            while (true) {
                val word = pendingWord
                if (word != null) {
                    lookUpAlone(lang, word.first, word.second)
                    pendingWord = null
                    continue
                }
                val notePhrase = pendingNote
                if (notePhrase != null) {
                    lookForExpression(lang, notePhrase)
                    pendingNote = null
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
        val words = wordsOf(sentence).distinctBy { it.lowercase() }
        // The dictionary first; espeak-ng only for words it has no IPA for, Gemma only for words it lacks.
        val known = withContext(Dispatchers.IO) { words.associateWith { fromDictionary(it) } }
        val sounds = withContext(Dispatchers.Default) {
            words.filter { known[it]?.ipa == null }.associateWith { w -> wordIpa(lang, w) }
        }
        // Gemma explains only the words the dictionary lacks. When they're few it's asked about each
        // one on its own, since for a whole sentence it writes a line for every word; when most are
        // missing (as with no dictionary), the whole sentence at once is less work.
        val missing = words.filter { known[it] == null }
        val meanings: Map<String, String> = try {
            when {
                missing.isEmpty() -> emptyMap()
                missing.size * 2 < words.size -> missing.mapNotNull { w ->
                    val key = w.lowercase() to sentence
                    // Kept as each one is done, so being paused part-way doesn't ask again.
                    val meaning = lookupCache[key]?.meaning ?: try {
                        Tutor.parseLookup(checkNotNull(tutor).lookUp(lang, w, sentence))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        null
                    }
                    triedAlone += key
                    lookupCache[key] = WordLookup(w, sentence, meaning, sounds[w], loading = false)
                    refreshOpenLookup()
                    meaning?.let { w.lowercase() to it }
                }.toMap()
                else -> Tutor.parseSentenceLookup(checkNotNull(tutor).lookUpSentence(lang, sentence))
            }
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
                sounds = wordSounds,
                loading = false,
            )
        }
        refreshOpenLookup()
    }

    /**
     * Asks Gemma for a common expression in [phrase]; if there's one, the phrase's card gets a
     * Tutor's note about it, shown above the card, and the countdown to the next note starts again.
     * If there's none, the next phrase is checked instead.
     */
    private suspend fun lookForExpression(lang: Language, phrase: String) {
        val note = try {
            Tutor.parseExpression(checkNotNull(tutor).expression(lang, phrase), phrase)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            diagnostics.log("Couldn't look for an expression: ${e.message}")
            null
        }
        val index = chat.indexOfLast { it is ChatItem.Say && it.phrase == phrase }
        if (note == null || index < 0) return
        chat[index] = (chat[index] as ChatItem.Say).copy(note = note)
        noteCountdown = NOTE_EVERY.random()
        diagnostics.log("Tutor's note on “${note.expression}”")
    }

    /**
     * [word]'s IPA for the word bubble when the dictionary has none: espeak-ng's, as the voice is given
     * it (ZIPA's hearing of the voice mishears it: "mår" came out /voːl/); ZIPA's only if espeak-ng
     * can't be used.
     */
    private fun wordIpa(lang: Language, word: String): String {
        return runCatching { Phonemizer.ipa(store.espeakData, lang.espeakVoice, lang.espeakText(word)) }.getOrNull()
            ?: SoundRecognizer.display(wordSounds(word))
    }

    /** Looks up one word on its own, for a tapped word the sentence lookup missed. */
    private suspend fun lookUpAlone(lang: Language, word: String, sentence: String) {
        val key = word.lowercase() to sentence
        triedAlone += key
        val sounds = lookupCache[key]?.sounds ?: withContext(Dispatchers.Default) { wordIpa(lang, word) }
        val result = try {
            val meaning = Tutor.parseLookup(checkNotNull(tutor).lookUp(lang, word, sentence))
            WordLookup(word, sentence, meaning, sounds, loading = false)
        } catch (e: CancellationException) {
            triedAlone -= key
            throw e
        } catch (e: Throwable) {
            WordLookup(
                word, sentence,
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
        // The word-by-word step's word may have just been looked up too.
        walk?.let { w -> if (w.info?.loading != false) refreshWalkInfo() }
        practice?.let { p -> if (p.info?.loading != false) refreshPracticeInfo() }
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
     * Opens the bubble for a tapped [word] from [sentence]: its meaning (the dictionary's, or Gemma's
     * in that sentence) and its IPA (the dictionary's, or espeak-ng's), with an English-style respelling.
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
            withContext(Dispatchers.Default) { checkNotNull(tts).play(wordAudio(word, slow)) }
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
     * Replaces the English line at [index] with [text] (e.g. to fix a typo) and
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

    /** Removes the English line at [index] and its phrase, to say something else. */
    fun deleteEnglish(index: Int) {
        if (busy != Busy.Idle || index != editableIndex) return
        while (chat.size > index) chat.removeAt(chat.lastIndex)
        startDrill(null)
        editableIndex = null
    }

    /** Moves on without getting the phrase right (offered after [SKIP_AFTER_FAILS] failed tries). */
    fun skip() {
        val d = drill ?: return
        if (busy != Busy.Idle) return
        chat += ChatItem.Note("Skipped")
        launchAction(Busy.Thinking) { phraseDone(d.phrase) }
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
        // ZIPA has heard all it will of this try; after a long one it gives its memory back before
        // Gemma answers.
        withContext(Dispatchers.Default) { zipa?.releaseIfGrown() }
        return Graded(transcript, heardText, result, Recording(audio))
    }

    private class Graded(val transcript: String, val heardText: String, val result: AttemptResult, val recording: Recording)

    /** Translates what the learner wants to say and starts drilling it. */
    private suspend fun translate(english: String, addLine: Boolean = true) {
        if (addLine) {
            chat += ChatItem.User(english)
            editableIndex = chat.lastIndex
        }
        busy = Busy.Thinking
        val index = chat.size
        chat += ChatItem.Say(english, "")
        // In Beginner the words are gone through one by one, so they aren't hidden.
        hiddenPhraseIndex = index.takeIf { phraseGuide == PhraseGuide.VoiceOnly && mode != Mode.Beginner }
        diagnostics.step = "Translating with Gemma"
        val raw = checkNotNull(tutor).translate(checkNotNull(language), register, english) { partial ->
            chat[index] = ChatItem.Say(english, Tutor.parse(partial).say ?: "")
        }
        val phrase = Tutor.parseSay(raw)
        chat[index] = ChatItem.Say(english, phrase)
        if (phrase.isBlank()) {
            chat[index] = ChatItem.Note("Couldn't translate that. Try saying it another way.")
            hiddenPhraseIndex = null
            return
        }
        // Every few phrases, a Tutor's note on a common expression in it, looked for in the background.
        noteCountdown = (noteCountdown - 1).coerceAtLeast(0)
        if (noteCountdown == 0) pendingNote = phrase
        busy = Busy.Preparing
        diagnostics.step = "Preparing the phrase with Piper and ZIPA"
        val voice = checkNotNull(tts)
        val audio = withContext(Dispatchers.Default) { voice.synthesize(phrase) }
        // The expected sounds are worked out while the phrase plays, and go on being worked out after
        // it: the learner can start their try as soon as the voice stops, and the try waits for them.
        val expected = conversationScope.async(Dispatchers.Default) { expectedSounds(phrase, audio) }
        startDrill(Drill(phrase, audio, expected))
        if (phraseSpoken) {
            busy = Busy.Speaking
            withContext(Dispatchers.Default) { voice.play(audio) }
        }
        queueLookups(phrase)
        if (mode == Mode.Beginner) {
            // Word by word first: it needs each word's expected sounds, so it waits for them.
            busy = Busy.Preparing
            val words = expected.await()
            if (words.isNotEmpty()) {
                walk = WordWalk(phrase, words, position = 0)
                refreshWalkInfo()
                sayWalkWord()
            }
        }
    }

    /** Says the word-by-word step's word, unless the voice is turned off for the phrase. */
    private suspend fun sayWalkWord() {
        val word = walk?.word?.word ?: return
        if (!phraseSpoken) return
        busy = Busy.Speaking
        withContext(Dispatchers.Default) { checkNotNull(tts).play(wordAudio(word)) }
    }

    /** Starts recording a try at the word-by-word step's word. */
    fun startWalkTalking() {
        if (walk == null || busy != Busy.Idle) return
        walkTalking = true
        startTalking()
    }

    /** Grades a try at the word-by-word step's word: on to the next word if it's right. */
    private suspend fun walkTry(audio: Audio) {
        val w = walk ?: return
        val (transcript, sounds) = hear(audio)
        val result = Matching.checkWordAlone(w.word, transcript, sounds)
        if (result.passed) {
            walkOn(w, "“${w.word.word}” was right.")
        } else {
            walk = w.copy(
                fails = w.fails + 1,
                heard = result.heard,
                note = "Not quite (sounds ${(result.score * 100).roundToInt()}%). Listen and try again.",
            )
            sayWalkWord()
        }
    }

    /** Moves the word-by-word step to its next word; after the last, it's the whole phrase's turn. */
    private suspend fun walkOn(w: WordWalk, note: String) {
        if (w.position + 1 < w.words.size) {
            walk = w.copy(position = w.position + 1, fails = 0, heard = "", note = note, info = null)
            refreshWalkInfo()
            sayWalkWord()
        } else {
            walk = null
            val d = drill
            if (d != null && phraseSpoken) {
                busy = Busy.Speaking
                withContext(Dispatchers.Default) { tts?.play(d.audio) }
            }
        }
    }

    /** Moves on without getting the word right (offered after [SKIP_AFTER_FAILS] failed tries). */
    fun skipWalkWord() {
        val w = walk ?: return
        if (busy != Busy.Idle) return
        launchAction(Busy.Speaking) { walkOn(w, "Skipped “${w.word.word}”.") }
    }

    /** Says the word-by-word step's word again. */
    fun hearWalkWord() {
        walk?.let { hearWord(it.word.word, slow = false) }
    }

    /**
     * [word]'s details as the word bubble shows them (meaning, IPA and respelling), for a page or
     * window that shows a word of [sentence] without opening the bubble. [show] gets them from the
     * lookups already made (fromLookups = true), or else from the dictionary and espeak-ng now, with
     * Gemma's meaning to follow from the background lookups for a word the dictionary lacks; it's
     * called again (through [refreshOpenLookup]) as those finish.
     */
    private fun fetchWordInfo(word: String, sentence: String, show: (info: WordLookup, fromLookups: Boolean) -> Unit) {
        val lang = language ?: return
        val key = word.lowercase() to sentence
        lookupCache[key]?.let { found ->
            if (found.meaning == null && found.error == null && key !in triedAlone) {
                // The sentence lookup missed it: ask about it on its own.
                show(found.copy(loading = true), true)
                pendingWord = word to sentence
                startBackground()
            } else {
                show(found, true)
            }
            return
        }
        viewModelScope.launch {
            val d = withContext(Dispatchers.IO) { fromDictionary(word) }
            val sounds = if (d?.ipa != null) null else withContext(Dispatchers.Default) { wordIpa(lang, word) }
            show(if (d != null) dictionaryLookup(word, sentence, d, sounds) else WordLookup(word, sentence, sounds = sounds), false)
            if (d == null) queueLookups(sentence, first = true)
        }
    }

    /** Fills in the word-by-word step's word details. */
    private fun refreshWalkInfo() {
        val w = walk ?: return
        fetchWordInfo(w.word.word, w.phrase) { info, fromLookups ->
            // Only if the step is still on this word, and never over what the lookups found.
            walk?.takeIf { it.phrase == w.phrase && it.position == w.position && (fromLookups || it.info == null) }
                ?.let { walk = it.copy(info = info) }
        }
    }

    /** Fills in the practice window's picked word details. */
    private fun refreshPracticeInfo() {
        val p = practice ?: return
        val word = p.result.words.getOrNull(p.position)?.word ?: return
        fetchWordInfo(word, p.phrase) { info, fromLookups ->
            practice?.takeIf { it.chatIndex == p.chatIndex && it.position == p.position && (fromLookups || it.info == null) }
                ?.let { practice = it.copy(info = info) }
        }
    }

    /**
     * The expected sounds of [phrase], word by word: ZIPA listens to Piper saying the whole phrase
     * ([audio], what the learner hears), and each word said on its own only guides where the word
     * boundaries fall. The sounds shown are the phonemes Piper was given (espeak-ng's).
     */
    private fun expectedSounds(phrase: String, audio: Audio): List<RefWord> {
        val isolated = wordsOf(phrase).map { w -> RefWord(w, wordSounds(w)) }
        val heard = checkNotNull(zipa).sounds(audio)
        // A long phrase grows ZIPA's memory as a long recording does.
        zipa?.releaseIfGrown()
        return withShownSounds(phrase, Matching.segmentReference(heard, isolated))
    }

    /**
     * [refWords] with the sounds to show: espeak-ng's phonemes for each word of [phrase], as Piper is
     * given them (after the language's fixes for text espeak-ng misreads). Unchanged if espeak-ng
     * can't be used.
     */
    private fun withShownSounds(phrase: String, refWords: List<RefWord>): List<RefWord> {
        val lang = language ?: return refWords
        val shown = runCatching {
            Phonemizer.words(store.espeakData, lang.espeakVoice, lang.espeakText(phrase), refWords.map { lang.espeakText(it.word) })
        }.onFailure { diagnostics.log("espeak-ng: ${it.message}") }.getOrNull()
        if (shown == null || shown.size != refWords.size) return refWords
        return refWords.mapIndexed { i, r -> r.copy(shown = shown[i].ifEmpty { r.sounds }) }
    }

    private suspend fun checkAttempt(d: Drill, audio: Audio) {
        val graded = grade(d.phrase, d.refWords.await(), audio)
        // The first try has been made by ear; the phrase's text shows from here on.
        hiddenPhraseIndex = null
        val result = graded.result
        val attempt = ChatItem.Attempt(graded.transcript, graded.heardText, result, d.phrase)
        // Only the latest try at a phrase is shown: it takes the place of the try before it, right
        // or wrong, so failed tries don't pile up.
        if ((chat.lastOrNull() as? ChatItem.Attempt)?.phrase == d.phrase) chat[chat.lastIndex] = attempt else chat += attempt
        keepRecording(chat.lastIndex, graded.recording)
        if (result.passed) {
            phraseDone(d.phrase)
        } else {
            drillAttempts++
            if (phraseSpoken) {
                busy = Busy.Speaking
                withContext(Dispatchers.Default) { tts?.play(d.audio) }
            }
        }
    }

    private fun startDrill(d: Drill?) {
        // A phrase dropped while its expected sounds are still being worked out doesn't need them.
        drill?.refWords?.cancel()
        if (d == null) hiddenPhraseIndex = null
        walk = null
        drill = d
        drillAttempts = 0
    }

    /** The phrase being practised is done with (said right, or skipped): the tutor answers it. */
    private suspend fun phraseDone(phrase: String) {
        startDrill(null)
        editableIndex = null
        runTurn(Instructions.said(phrase, register))
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
     * Sends [message] to the tutor, shows the answer as it streams in and speaks the reply: each
     * sentence as soon as Gemma has finished writing it, so the voice starts while the rest is
     * still being written. Then, as its own step written while the reply plays, the English
     * translation of the reply, which isn't kept in Gemma's conversation memory.
     * [saveOpening] (key, voice) saves the answer and its audio as the opening for those choices.
     */
    private suspend fun runTurn(
        message: String,
        saveOpening: Pair<String, String>? = null,
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
            val voice = checkNotNull(tts)
            val spoken = mutableListOf<Audio>()
            var english: String? = null
            coroutineScope {
                // The voice makes each finished sentence's audio while Gemma writes the next...
                val sentences = Channel<String>(Channel.UNLIMITED)
                val clips = Channel<Audio>(Channel.UNLIMITED)
                launch(Dispatchers.Default) {
                    for (sentence in sentences) {
                        ensureActive()
                        clips.send(voice.synthesize(sentence.trim()))
                    }
                    clips.close()
                }
                // ...and says them in order.
                val speaking = launch(Dispatchers.Default) {
                    for (clip in clips) {
                        ensureActive()
                        busy = Busy.Speaking
                        spoken += clip
                        voice.play(clip)
                    }
                }
                diagnostics.step = "Generating a reply with Gemma"
                // How much of the reply has been handed to the voice.
                var handed = 0
                try {
                    checkNotNull(tutor).respond(message).collect { raw ->
                        reply = Tutor.parse(raw).reply
                        update { it.copy(reply = reply) }
                        val end = finishedSentencesEnd(reply, handed)
                        if (end > handed) {
                            sentences.trySend(reply.substring(handed, end))
                            handed = end
                        }
                    }
                    // Whatever is left once Gemma has finished.
                    if (handed <= reply.length) {
                        reply.substring(handed).takeIf { rest -> rest.any { it.isLetterOrDigit() } }?.let { sentences.trySend(it) }
                    }
                } finally {
                    sentences.close()
                }
                diagnostics.step = "Speaking the reply with Piper"
                val extra = async {
                    try {
                        if (reply.isNotBlank()) {
                            val raw = checkNotNull(tutor).translateReply(lang, reply) { partial ->
                                update { it.copy(english = Tutor.parse(partial).translation) }
                            }
                            english = Tutor.parseTranslation(raw)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        diagnostics.log("Couldn't add the translation: ${e.message}")
                    }
                }
                speaking.join()
                if (extra.isActive) busy = Busy.Translating
                extra.await()
            }
            if (saveOpening != null && reply.isNotBlank() && spoken.isNotEmpty()) {
                openings.save(saveOpening.first, Opening(reply, english))
                // The sentences' audio, joined into one.
                val whole = FloatArray(spoken.sumOf { it.samples.size })
                var at = 0
                for (clip in spoken) {
                    clip.samples.copyInto(whole, at)
                    at += clip.samples.size
                }
                openings.saveAudio(saveOpening.first, saveOpening.second, Audio(whole, spoken.first().sampleRate))
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

    /**
     * Where the last finished sentence of [text] ends (the index after it), looking past [from]; or
     * [from] if none has finished. A sentence has finished once something follows its full stop,
     * question mark or exclamation mark; a piece shorter than [MIN_SPOKEN_CHARS] waits for the next
     * sentence, so an abbreviation's full stop ("M. Dupont") rarely cuts one in two.
     */
    private fun finishedSentencesEnd(text: String, from: Int): Int {
        for (i in text.length - 2 downTo from + MIN_SPOKEN_CHARS - 1) {
            val mark = text[i]
            // In writing without spaces between sentences (Chinese, Hindi's danda) the mark is enough.
            if (mark in "。！？।" || (mark in ".!?…" && text[i + 1].isWhitespace())) return i + 1
        }
        return from
    }

    companion object {
        /** The shortest piece of a reply the voice is given on its own, in characters. */
        private const val MIN_SPOKEN_CHARS = 20

        /** How many phrases apart the Tutor's notes on common expressions come, at random. */
        private val NOTE_EVERY = 5..8

        /** Failed tries at a phrase before Skip is offered. */
        const val SKIP_AFTER_FAILS = 2

        /** Speaking rate for "Slow": 40% of normal speed. */
        private const val SLOW_SPEED = 0.4f

        /** At about 50 KB a word, around 10 MB. */
        private const val WORD_AUDIO_KEPT = 200

        /** How long the loading bar takes to move to a new amount. */
        const val LOADING_BAR_FILL_MS = 500

        /** How many words a match-the-pairs question has. */
        private const val MATCH_PAIRS = 5

        /** How many words without a meaning the quiz passes over when looking for meanings. */
        private const val MEANING_MISSES = 4
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
        val result: AttemptResult,
        val position: Int,
        val hasRecording: Boolean,
        val note: String? = null,
        val wordTries: Map<Int, WordTry> = emptyMap(),
        /** The picked word's meaning, IPA and respelling, as the word bubble has them; null until found. */
        val info: WordLookup? = null,
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
    /**
     * Opens the practice window for the try at [index], on the word at [position] (the first wrong
     * word if none is given), and says that word at normal speed.
     */
    fun openPractice(index: Int, position: Int? = null) {
        val item = chat.getOrNull(index) as? ChatItem.Attempt ?: return
        val words = item.result.words
        if (words.isEmpty()) return
        val at = position?.takeIf { it in words.indices } ?: words.indexOfFirst { !it.passed }.coerceAtLeast(0)
        val p = Practice(index, item.phrase, item.result, at, hasRecording = false)
        practice = p.copy(hasRecording = recordingFor(p) != null)
        refreshPracticeInfo()
        hearWord(words[at].word, slow = false)
    }

    fun closePractice() {
        practice = null
    }

    /** Picks the word at [position] in the practice window (wrapping around) and says it at normal speed. */
    fun practiceSelect(position: Int) {
        val p = practice ?: return
        val at = Math.floorMod(position, p.result.words.size.coerceAtLeast(1))
        practice = p.copy(position = at, info = null)
        refreshPracticeInfo()
        p.result.words.getOrNull(at)?.let { hearWord(it.word, slow = false) }
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
                // Getting a failed phrase right here closes the window, so its card shows as correct.
                // If it's the phrase being practised, that counts as getting it right: the
                // conversation moves on, as from the main button.
                if (r.passed && old?.result?.passed == false) {
                    val d = drill
                    val current = d != null && d.phrase == p.phrase &&
                        chat.drop(p.chatIndex + 1).none { it is ChatItem.Say }
                    practice = null
                    if (current) phraseDone(p.phrase)
                    return
                }
                val scores = "Words ${(r.wordScore * 100).toInt()}% · Sounds ${(r.soundScore * 100).toInt()}%"
                practice = p.copy(
                    result = r,
                    hasRecording = latest,
                    note = when {
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

    // ---- The quiz on the last conversation -------------------------------------------------------

    /** The quiz offered before a new conversation: on [saved], from the conversation page or the home page. */
    class QuizOffer(val saved: SavedConversation, val fromConversation: Boolean) {
        /** How many questions it has (left, for one under way). */
        val questions: Int = saved.quiz?.let { it.plan.size - it.position } ?: Quiz.length(saved.chat)
        val summary: String? = LANGUAGES.firstOrNull { it.code == saved.languageCode }?.let { lang ->
            listOfNotNull(lang.englishName, saved.scenario.title.takeIf { saved.scenario != Scenario.Default }).joinToString(" · ")
        }
    }

    /** The quiz being offered, waiting for Quiz or Skip. */
    var quizOffer by mutableStateOf<QuizOffer?>(null)
        private set

    /** The quiz on screen. */
    var quiz by mutableStateOf<Quiz?>(null)
        private set

    /** How the question on screen was answered; null until it is. */
    var quizAnswer by mutableStateOf<QuizAnswer?>(null)
        private set

    /** Something that went wrong in the quiz, shown at its top. */
    var quizError by mutableStateOf<String?>(null)
        private set

    /** The saved conversation the quiz is on, kept up to date with the quiz as it goes. */
    private var quizBase: SavedConversation? = null

    /** How many phrases had been said when the quiz on the conversation on screen was last offered and taken or skipped. */
    private var quizOfferedAt = 0

    /** Whether the talking now under way is an answer to a say-it-from-memory question. */
    private var quizTalking = false

    /** The expected sounds of the say-it-from-memory question's phrase, worked out as it's shown. */
    private var quizRefWords: Pair<String, List<RefWord>>? = null

    /** Meanings found for the quiz, by word (its dictionary key); null for a word with none. */
    private val quizMeanings = mutableMapOf<String, String?>()

    /** Whether [saved] has a quiz to offer: one left part way, or one on phrases said since it was last offered. */
    private fun quizDue(saved: SavedConversation): Boolean =
        saved.quiz?.finished == false ||
            (Quiz.length(saved.chat) > 0 && Quiz.phrases(saved.chat).size > saved.quizOfferedAt)

    fun takeQuiz() {
        val offer = quizOffer ?: return
        quizOffer = null
        // From the conversation page it's the conversation as it is once its work has stopped.
        openQuiz(if (offer.fromConversation) null else offer.saved)
    }

    /** Skips the quiz (it isn't offered again for these phrases) and goes on to the new conversation. */
    fun skipQuiz() {
        val offer = quizOffer ?: return
        quizOffer = null
        val said = Quiz.phrases(offer.saved.chat).size
        if (offer.fromConversation) {
            quizOfferedAt = said
            leaveForNewConversation()
        } else {
            writeSaved(offer.saved.copy(quiz = null, quizOfferedAt = said))
            register = Register.Formal
            screen = Screen.PickLanguage
        }
    }

    /** Neither Quiz nor Skip: stays where it was. */
    fun dismissQuizOffer() {
        quizOffer = null
    }

    /**
     * Opens the quiz on [given] (the conversation on screen if null): the one left part way, or a new
     * one. The page opens straight away; the questions are made one at a time behind it, the first
     * from what's at hand so it's ready at once, the rest while the learner answers.
     */
    private fun openQuiz(given: SavedConversation?) {
        error = null
        quizError = null
        viewModelScope.launch {
            stopConversationWork()
            val saved = given ?: currentConversation() ?: return@launch
            val lang = LANGUAGES.firstOrNull { it.code == saved.languageCode } ?: return@launch
            quizBase = saved
            quiz = saved.quiz ?: Quiz.start(saved.chat, mode, saved.band)
            quizAnswer = null
            quizRefWords = null
            quizMeanings.clear()
            own = LANGUAGES.firstOrNull { it.code == saved.ownCode } ?: ownSetting
            scenario = saved.scenario
            register = saved.register
            if (language != lang) {
                openDictionary(lang)
                language = lang
            }
            lookup = null
            screen = Screen.Quiz
            saveQuiz()
            conversationScope.launch {
                busy = Busy.Starting
                try {
                    val voice = voiceFor(lang) ?: run {
                        quizError = error
                        return@launch
                    }
                    modelsLoaded()
                    loadVoice(lang, voice)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    quizError = "Couldn't start the quiz: ${e.message}"
                    return@launch
                } finally {
                    busy = Busy.Idle
                }
                showQuizQuestion()
                makeQuizQuestions()
            }
        }
    }

    /** Saves the quiz with its conversation; a question answered counts as done, so it's not asked again. */
    private fun saveQuiz() {
        val base = quizBase ?: return
        val q = quiz ?: return
        writeSaved(base.copy(quiz = q.copy(position = if (quizAnswer != null) q.position + 1 else q.position)))
    }

    /** Makes the quiz's questions that aren't made yet, in order. */
    private suspend fun makeQuizQuestions() {
        while (true) {
            val q = quiz ?: return
            val at = q.questions.size
            if (at >= q.plan.size) return
            // Not while the learner is talking or their answer is being heard: that needs the phone.
            snapshotFlow { busy }.first { it != Busy.Listening && it != Busy.Transcribing }
            val question = makeQuizQuestion(q, q.phrases[q.plan[at]])
            val now = quiz ?: return
            // A question that can't be made is dropped.
            quiz = if (question != null) now.copy(questions = now.questions + question)
            else now.copy(plan = now.plan.filterIndexed { i, _ -> i != at })
            saveQuiz()
            if (question != null && now.position == at) showQuizQuestion()
        }
    }

    /**
     * A question on [source], of the kind asked least so far (at random among those), or the next
     * least if that can't be made of it; null if none can.
     */
    private suspend fun makeQuizQuestion(q: Quiz, source: QuizSource): QuizQuestion? {
        val lang = language ?: return null
        val asked = q.questions.groupingBy { it.kind }.eachCount()
        val kinds = QuizKind.entries
            .filter { it != QuizKind.Mistake || q.mode == Mode.Intermediate }
            .filter { it != QuizKind.Article || lang.articles.isNotEmpty() }
            .filter { it != QuizKind.Finals || lang.finalConsonants }
            .filter { it != QuizKind.Match || asked[it] == null }
            .shuffled()
            .sortedBy { asked[it] ?: 0 }
            // The first question is one that needs no Gemma, so the quiz starts straight away.
            .let { kinds -> if (q.questions.isEmpty()) kinds.sortedBy { it.needsGemma } else kinds }
        for (kind in kinds) {
            val question = try {
                makeQuestion(kind, q, source, lang)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                diagnostics.log("Quiz: couldn't make a ${kind.name} question (${e.message})")
                null
            }
            if (question != null) return question
        }
        return null
    }

    /** A [kind] question on [source], or null if one can't be made of it. */
    private suspend fun makeQuestion(kind: QuizKind, q: Quiz, source: QuizSource, lang: Language): QuizQuestion? {
        val gemma = checkNotNull(tutor)
        val phrase = source.phrase
        return when (kind) {
            QuizKind.Order -> QuizMaker.order(source, q.wordLimit, lang.capitalNouns)
            QuizKind.Speak -> QuizMaker.speak(source, q.wordLimit)
            QuizKind.PhraseMeaning -> QuizMaker.phraseMeaning(source, q.phrases)
            QuizKind.FillGap, QuizKind.Mistake -> {
                if (wordsOf(phrase).size < 3) return null
                val (position, wrong) = QuizMaker.parseForms(gemma.quizForms(lang, own, phrase), phrase) ?: return null
                val right = wordsOf(phrase)[position]
                when {
                    kind == QuizKind.Mistake -> MistakeQuestion(QuizMaker.replaceWord(phrase, position, wrong.first()), position, right, source.english)
                    wrong.size < 2 -> null
                    else -> QuizMaker.choice(kind, QuizMaker.replaceWord(phrase, position, "___"), source.english, null, right, wrong.take(2))
                }
            }
            QuizKind.Article -> {
                val asked = q.questions.filter { it.kind == QuizKind.Article }.map { (it as ChoiceQuestion).shown }
                val (noun, article) = QuizMaker.parseArticles(gemma.quizArticles(lang, own, phrase), lang.articles)
                    .filter { (noun, _) -> asked.none { QuizMaker.same(it, noun) } && isWord(noun) }
                    .randomOrNull() ?: return null
                ChoiceQuestion(kind, noun, null, null, lang.articles, lang.articles.indexOf(article))
            }
            QuizKind.Heard -> {
                val word = quizWords(phrase).randomOrNull() ?: return null
                val alike = QuizMaker.parseAlike(gemma.quizAlike(lang, own, word), word).filter { isWord(it) }
                if (alike.size < 2) null else QuizMaker.choice(kind, "", null, word, word, alike.take(2))
            }
            QuizKind.WordMeaning -> {
                val (word, meaning) = meaningsFrom(lang, listOf(source), 1).firstOrNull() ?: return null
                val others = meaningsFrom(lang, q.phrases.shuffled(), 2, except = word to meaning).map { it.second }
                if (others.size < 2) null else QuizMaker.choice(kind, word, null, null, meaning, others)
            }
            QuizKind.Finals -> withContext(Dispatchers.Default) {
                val words = wordsOf(phrase)
                val inPhrase = Phonemizer.inPhrase(store.espeakData, lang.espeakVoice, lang.espeakText(phrase), words.size)
                    ?: return@withContext null
                QuizMaker.finals(source, inPhrase) { i -> Phonemizer.alone(store.espeakData, lang.espeakVoice, lang.espeakText(words[i])) }
            }
            QuizKind.Match -> {
                val pairs = meaningsFrom(lang, listOf(source) + q.phrases.filter { it != source }.shuffled(), MATCH_PAIRS)
                if (pairs.size < MATCH_PAIRS) return null
                val order = (1..20).map { pairs.indices.shuffled() }.firstOrNull { it != pairs.indices.toList() } ?: return null
                MatchQuestion(pairs.map { it.first }, pairs.map { it.second }, order)
            }
        }
    }

    /** Whether [word] is a real word: in the dictionary, if there is one (Gemma sometimes makes words up). */
    private suspend fun isWord(word: String): Boolean =
        dictionary == null || withContext(Dispatchers.IO) { fromDictionary(word) } != null

    /** The words of [phrase] worth asking about: those of three letters or more, or else all of them. */
    private fun quizWords(phrase: String): List<String> {
        val words = wordsOf(phrase).distinctBy { Dictionary.key(it) }
        return words.filter { w -> w.count { it.isLetter() } >= 3 }.ifEmpty { words }
    }

    /**
     * Up to [count] words from [sources] (in that order) with their meanings, each word and meaning
     * different from the others and from [except]. A few words without a meaning are passed over.
     */
    private suspend fun meaningsFrom(
        lang: Language, sources: List<QuizSource>, count: Int, except: Pair<String, String>? = null,
    ): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var tries = 0
        for ((word, sentence) in sources.flatMap { s -> quizWords(s.phrase).shuffled().map { it to s.phrase } }) {
            if (out.size >= count || tries >= count + MEANING_MISSES) break
            val taken = out + listOfNotNull(except)
            if (taken.any { QuizMaker.same(it.first, word) }) continue
            tries++
            val meaning = quizMeaning(lang, word, sentence) ?: continue
            if (taken.any { it.second.equals(meaning, ignoreCase = true) }) continue
            out += word to meaning
        }
        return out
    }

    /** [word]'s meaning for the quiz, kept short: the dictionary's first, else Gemma's in [sentence]. */
    private suspend fun quizMeaning(lang: Language, word: String, sentence: String): String? {
        val key = Dictionary.key(word)
        if (key in quizMeanings) return quizMeanings[key]
        val meaning = withContext(Dispatchers.IO) { fromDictionary(word) }?.groups?.firstOrNull { it.meanings.isNotEmpty() }?.meanings?.first()
            ?: lookupCache[word.lowercase() to sentence]?.meaning
            ?: Tutor.parseLookup(checkNotNull(tutor).lookUp(lang, word, sentence, own))
        return meaning?.let(QuizMaker::shortMeaning)?.ifEmpty { null }.also { quizMeanings[key] = it }
    }

    /** Gets the question on screen ready: says what's to be heard, or works out the sounds of the phrase to say. */
    private fun showQuizQuestion() {
        val question = quiz?.current ?: return
        if (busy != Busy.Idle || quizAnswer != null) return
        when (question) {
            is ChoiceQuestion -> question.spoken?.let { text ->
                launchAction(Busy.Speaking) { withContext(Dispatchers.Default) { checkNotNull(tts).play(wordAudio(text)) } }
            }
            is SpeakQuestion -> launchAction(Busy.Preparing) {
                val words = withContext(Dispatchers.Default) { expectedSounds(question.phrase, wordAudio(question.phrase)) }
                quizRefWords = question.phrase to words
            }
            else -> Unit
        }
    }

    /** Answers the question on screen; it can't be answered again. */
    fun answerQuiz(answer: QuizAnswer) {
        if (quiz?.current == null || quizAnswer != null) return
        quizAnswer = answer
        saveQuiz()
    }

    /** On to the next question; after the last, the quiz is done. */
    fun nextQuizQuestion() {
        val q = quiz ?: return
        if (busy != Busy.Idle) return
        val next = q.copy(position = q.position + 1)
        quizAnswer = null
        quizRefWords = null
        quizError = null
        if (next.finished) {
            finishQuiz()
            return
        }
        quiz = next
        saveQuiz()
        showQuizQuestion()
    }

    /** Ends the quiz (it isn't offered again for these phrases) and goes on to the new conversation. */
    fun finishQuiz() {
        val base = quizBase
        val q = quiz
        viewModelScope.launch {
            stopConversationWork()
            if (base != null && q != null) writeSaved(base.copy(quiz = null, quizOfferedAt = q.phrases.size))
            quizOfferedAt = q?.phrases?.size ?: quizOfferedAt
            quiz = null
            quizBase = null
            quizAnswer = null
            register = Register.Formal
            screen = Screen.PickLanguage
        }
    }

    /** For the screenshot tests: shows [quiz] on [lang], with the question on screen answered [answer], or [offer] offered. */
    @androidx.annotation.VisibleForTesting
    internal fun showQuizForTest(lang: Language, quiz: Quiz?, answer: QuizAnswer? = null, offer: QuizOffer? = null) {
        language = lang
        this.quiz = quiz
        quizAnswer = answer
        quizOffer = offer
        screen = Screen.Quiz
    }

    /** Starts recording the answer to a say-it-from-memory question. */
    fun startQuizTalking() {
        if (quiz?.current !is SpeakQuestion || quizAnswer != null || busy != Busy.Idle) return
        quizTalking = true
        startTalking()
    }

    /** Grades the answer to a say-it-from-memory question, as a try at a phrase is graded. */
    private suspend fun quizTry(audio: Audio) {
        val question = quiz?.current as? SpeakQuestion ?: return
        val refWords = quizRefWords?.takeIf { it.first == question.phrase }?.second
            ?: withContext(Dispatchers.Default) { expectedSounds(question.phrase, wordAudio(question.phrase)) }
        val graded = grade(question.phrase, refWords, audio)
        if (quiz?.current == question) answerQuiz(QuizAnswer.Spoken(graded.result))
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
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.deleteUnusedVoices() }
            runCatching { openings.deleteOld() }
        }
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

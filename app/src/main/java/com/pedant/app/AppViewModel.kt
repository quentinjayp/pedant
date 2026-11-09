package com.pedant.app

import android.app.Application
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
import java.util.concurrent.ConcurrentHashMap
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
    var scenario by mutableStateOf(Scenario.Default)
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

    /** What is loading right now and since when, shown on the loading screen. */
    var loadingStep by mutableStateOf<Pair<String, Long>?>(null)
        private set

    fun memorySummary(): String = diagnostics.memorySummary()

    /** Loads one model, recording how long it took and the free memory before and after. */
    private fun <T> timedLoad(name: String, load: () -> T): T {
        val before = diagnostics.memorySummary()
        loadingStep = name to System.currentTimeMillis()
        diagnostics.step = "Loading $name ($before)"
        diagnostics.log("Loading $name: start, $before")
        val start = System.currentTimeMillis()
        val result = load()
        diagnostics.log("Loading $name: done in ${(System.currentTimeMillis() - start) / 1000.0} s, ${diagnostics.memorySummary()}")
        return result
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
                        tutor = timedLoad("Gemma") {
                            Tutor(getApplication(), checkNotNull(store.gemmaModel) { "Gemma model file is missing" })
                        }
                    }
                    if (stt == null) stt = timedLoad("Omnilingual ASR") { SpeechToText(store) }
                    if (zipa == null) zipa = timedLoad("ZIPA") { SoundRecognizer(store) }
                    diagnostics.step = null
                    loadingStep = null
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
            // Stop anything left from the last conversation before its voice is released.
            stopConversationWork()
            conversationScope.launch {
                try {
                    val voice = store.installedVoice(lang)
                        ?: withProgress { report -> store.downloadVoice(lang, report) }
                    withContext(Dispatchers.Default) {
                        if (ttsVoice != voice) {
                            diagnostics.step = "Loading the $voice voice"
                            tts?.release()
                            tts = TextToSpeech(store, voice, lang.ttsRewrites, getApplication<Application>().cacheDir)
                            ttsVoice = voice
                            wordSoundsCache.clear()
                        }
                        diagnostics.step = "Starting a ${lang.englishName} ${mode.title} session"
                        checkNotNull(tutor).startConversation(lang, mode, scenario)
                        diagnostics.step = null
                    }
                    language = lang
                    chat.clear()
                    startDrill(null)
                    editableIndex = null
                    screen = Screen.Conversation
                    runTurn(if (mode == Mode.Learning) Tutor.LEARNING_START else Tutor.CONVERSATION_START)
                } catch (e: CancellationException) {
                    diagnostics.step = null
                    throw e
                } catch (e: Throwable) {
                    diagnostics.step = null
                    progress = null
                    error = "Could not start: ${e.message}"
                }
            }
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
            screen = Screen.PickLanguage
        }
    }

    /**
     * Stops the current conversation's work: Gemma's reply, speech playback, recording, background
     * lookups and any download, and waits until it has all actually stopped.
     */
    private suspend fun stopConversationWork() {
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
            when {
                mode == Mode.Conversation -> conversationTurn(audio)
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
        if (busy != Busy.Idle || mode != Mode.Learning || drill != null || text.isBlank()) return
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

    init {
        // Resume background lookups whenever the app becomes idle. This must come after the fields
        // above: viewModelScope runs it straight away, during construction.
        viewModelScope.launch { snapshotFlow { busy }.collect { if (it == Busy.Idle) startBackground() } }
    }

    /** Queues [sentence]'s words to be explained in the background; [first] puts it at the front. */
    private fun queueLookups(sentence: String, first: Boolean = false) {
        if (sentence.isBlank() || sentence in doneSentences) return
        pendingSentences.remove(sentence)
        if (first) pendingSentences.addFirst(sentence) else pendingSentences.addLast(sentence)
        startBackground()
    }

    private fun startBackground() {
        if (backgroundJob?.isActive == true || busy != Busy.Idle) return
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
        // Sounds first: quick, and they don't need Gemma.
        val sounds = withContext(Dispatchers.Default) {
            words.associateWith { w -> SoundRecognizer.display(wordSounds(w)) }
        }
        val meanings = try {
            Tutor.parseSentenceLookup(checkNotNull(tutor).lookUpSentence(lang, sentence))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyMap()
        }
        for (w in words) {
            val wordSounds = sounds[w]
            lookupCache[w.lowercase() to sentence] = WordLookup(
                w, sentence,
                meaning = meanings[w.lowercase()],
                soundsLike = wordSounds?.let(Respell::fromIpa)?.ifEmpty { null },
                sounds = wordSounds,
                loading = false,
            )
        }
        refreshOpenLookup()
    }

    /** Looks up one word on its own, for a tapped word the sentence lookup missed. */
    private suspend fun lookUpAlone(lang: Language, word: String, sentence: String) {
        val key = word.lowercase() to sentence
        triedAlone += key
        val sounds = lookupCache[key]?.sounds ?: withContext(Dispatchers.Default) {
            SoundRecognizer.display(wordSounds(word))
        }
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
        lookup = WordLookup(word, sentence)
        if (sentence in doneSentences) {
            refreshOpenLookup()
            startBackground()
        } else {
            queueLookups(sentence, first = true)
        }
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

    /** Learning mode: moves on without getting the phrase right (offered after a few tries). */
    fun skip() {
        val d = drill ?: return
        if (busy != Busy.Idle) return
        chat += ChatItem.Note("Skipped")
        startDrill(null)
        editableIndex = null
        launchAction(Busy.Thinking) { runTurn(Tutor.learningSaid(d.phrase)) }
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
        val refWords = coroutineScope {
            // Expected sounds: ZIPA listens to Piper saying the whole phrase (the audio you hear).
            // Each word said on its own only guides where the word boundaries fall. This runs
            // while the phrase plays.
            val expected = async(Dispatchers.Default) {
                val isolated = Matching.words(phrase).map { w -> RefWord(w, wordSounds(w)) }
                Matching.segmentReference(checkNotNull(zipa).sounds(audio), isolated)
            }
            busy = Busy.Speaking
            withContext(Dispatchers.Default) { voice.play(audio) }
            busy = Busy.Preparing
            expected.await()
        }
        startDrill(Drill(phrase, audio, refWords))
        queueLookups(phrase)
    }

    private suspend fun checkAttempt(d: Drill, audio: Audio) {
        val (transcript, sounds) = hear(audio)
        var result = Matching.check(d.phrase, transcript, d.refWords, sounds)
        if (!result.passed && transcript.isNotBlank()) {
            // Sound-alike check: Piper says what Omnilingual heard and ZIPA listens, so a word
            // spelled differently but pronounced the same (like "sava" for "Ça va") still counts.
            val transcriptSounds = withContext(Dispatchers.Default) { soundsOf(transcript) }
            result = Matching.check(d.phrase, transcript, d.refWords, sounds, transcriptSounds)
        }
        chat += ChatItem.Attempt(transcript, SoundRecognizer.display(sounds), result)
        if (result.passed) {
            startDrill(null)
            editableIndex = null
            runTurn(Tutor.learningSaid(d.phrase))
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
            queueLookups(latest.reply)
        } catch (e: CancellationException) {
            throw e
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

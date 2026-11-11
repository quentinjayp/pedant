package com.pedant.app

import java.text.Normalizer

/** How well an attempt matched the target phrase, by words (Omnilingual) and by sounds (ZIPA). */
data class AttemptResult(
    val wordScore: Double,
    val soundScore: Double,
    val passed: Boolean,
    /** The target words that went wrong, in phrase order. */
    val issues: List<WordIssue>,
    /** Every word of the phrase, right or wrong, in order. */
    val words: List<WordResult> = emptyList(),
)

/**
 * One word of an attempt. [expected] are its expected sounds; [heard] what ZIPA heard in its place
 * (the sounds that lined up with it); [score] how close those sounds were, 0 to 1; [heardWord] what
 * Omnilingual heard in its place, if anything.
 */
data class WordResult(
    val word: String,
    val expected: List<String>,
    val heard: String,
    val score: Double,
    val heardWord: String?,
    val passed: Boolean,
)

/**
 * A target word that went wrong. [heardWord] is what Omnilingual heard in its place (null if
 * nothing); the sounds are set when ZIPA heard the word's sounds differently.
 */
data class WordIssue(
    val word: String,
    val heardWord: String?,
    val expectedSounds: String? = null,
    val heardSounds: String? = null,
)

/** Per word: expected and heard sounds, and the share of the expected sounds that went wrong. */
private data class WordSounds(
    val expected: String,
    val heard: String,
    val ratio: Double,
)

private data class SoundComparison(val score: Double, val words: List<WordSounds>)

/** A word of the target phrase and its expected sounds. */
data class RefWord(val word: String, val sounds: List<String>)

/**
 * Scores an attempt at a phrase. Both recognizers make small mistakes even on perfect speech, so
 * near misses get partial credit: close spellings of a word, and sounds that are close to each
 * other (similar vowels, the various r sounds, voiced/unvoiced pairs, stress and length marks).
 */
object Matching {
    /**
     * A word counts as said right if Omnilingual heard it, or if ZIPA heard its sounds with at
     * most this error ratio. The phrase passes when every word is right. Set with
     * tools/calibrate_scoring.py: other Piper voices saying the phrases passed 93% of the time,
     * and phrases with a word swapped or dropped passed about 40% of the time.
     */
    const val WORD_SOUND_LIMIT = 0.5

    /** For languages written without spaces, where words can't be checked one by one. */
    const val PASS_WORDS = 0.7
    const val PASS_SOUNDS = 0.6

    /** A word counts as right if its spelling is at least this close (e.g. "toa" for "toi"). */
    private const val CLOSE_WORD = 0.66

    /**
     * [heardSounds] are the sounds ZIPA heard in the learner's recording. [transcriptSounds], if
     * given, are the sounds of Piper saying what Omnilingual heard: a word spelled differently but
     * pronounced the same (a sound-alike, like "sa" or "sava" for "Ça va") then still counts.
     */
    fun check(
        target: String,
        transcript: String,
        refWords: List<RefWord>,
        heardSounds: List<String>,
        transcriptSounds: List<String>? = null,
    ): AttemptResult {
        val targetWords = words(target)
        val heardWords = words(transcript)
        // Languages written without spaces (such as Chinese) are compared character by character.
        val spaced = targetWords.size > 1
        val targetUnits = if (spaced) targetWords.map(::normalize) else chars(target)
        val heardUnits = if (spaced) heardWords.map(::normalize) else chars(transcript)

        val wordSteps = align(targetUnits, heardUnits, ::wordCost, indel = { 1.0 })
        val wordScore = score(wordSteps, targetUnits.size.toDouble())

        // For each target word, what was heard in its place and whether it counts as wrong.
        val heardFor = arrayOfNulls<String>(targetUnits.size)
        val wordWrong = BooleanArray(targetUnits.size)
        for (step in wordSteps) {
            val r = step.ref ?: continue
            step.hyp?.let { h -> heardFor[r] = if (spaced) heardWords[h] else heardUnits[h] }
            if (step.cost > 0) wordWrong[r] = true
        }

        val sounds = compareSounds(refWords, heardSounds)
        if (!spaced) {
            val issues = targetUnits.indices.filter { wordWrong[it] }.map { WordIssue(targetUnits[it], heardFor[it]) } +
                sounds.words.filter { it.ratio > WORD_SOUND_LIMIT }
                    .map { WordIssue(target, transcript.ifEmpty { null }, it.expected, it.heard) }
            return AttemptResult(
                wordScore = wordScore,
                soundScore = sounds.score,
                passed = wordScore >= PASS_WORDS && sounds.score >= PASS_SOUNDS,
                issues = issues.take(6),
                words = wordResults(refWords, sounds) { i, ws -> null to (ws.ratio <= WORD_SOUND_LIMIT) },
            )
        }
        // Both recognizers slip now and then on correct speech, but rarely on the same word, so a
        // word only counts as wrong when neither heard it right, and what Omnilingual heard
        // doesn't sound like it either.
        val soundAlike = transcriptSounds?.let { compareSounds(refWords, it) }
        val wrong = targetWords.indices.filter { i ->
            val soundRatio = sounds.words.getOrNull(i)?.ratio ?: 1.0
            val alikeRatio = soundAlike?.words?.getOrNull(i)?.ratio ?: 1.0
            wordWrong[i] && soundRatio > WORD_SOUND_LIMIT && alikeRatio > WORD_SOUND_LIMIT
        }
        return AttemptResult(
            wordScore = wordScore,
            soundScore = sounds.score,
            passed = targetWords.isNotEmpty() && wrong.isEmpty(),
            issues = wrong.map { i ->
                val sound = sounds.words.getOrNull(i)
                WordIssue(targetWords[i], heardFor[i], sound?.expected, sound?.heard)
            }.take(6),
            words = wordResults(refWords, sounds) { i, _ -> heardFor.getOrNull(i) to (i !in wrong) },
        )
    }

    /** Each word's result; [judge] gives what Omnilingual heard in its place and whether it passed. */
    private fun wordResults(
        refWords: List<RefWord>,
        sounds: SoundComparison,
        judge: (Int, WordSounds) -> Pair<String?, Boolean>,
    ): List<WordResult> = refWords.mapIndexed { i, ref ->
        val ws = sounds.words.getOrNull(i) ?: WordSounds("", "", 1.0)
        val (heardWord, passed) = judge(i, ws)
        WordResult(
            word = ref.word,
            expected = ref.sounds,
            heard = ws.heard.replace("▁", ""),
            score = (1.0 - ws.ratio).coerceIn(0.0, 1.0),
            heardWord = heardWord,
            passed = passed,
        )
    }

    /**
     * Scores one word said on its own against its expected sounds: how close the sounds were (0 to
     * 1) and the sounds heard.
     */
    fun checkWord(ref: RefWord, heardSounds: List<String>): Pair<Double, String> {
        val ws = compareSounds(listOf(ref), heardSounds.filter { it != "▁" }).words.first()
        return (1.0 - ws.ratio).coerceIn(0.0, 1.0) to ws.heard
    }

    /**
     * Splits the sounds ZIPA heard in the whole phrase ([phraseSounds], the reference) into words.
     * Each word said on its own ([isolated]) is only a guide to where the word boundaries fall,
     * because single words are sometimes mispronounced when said out of context.
     */
    fun segmentReference(phraseSounds: List<String>, isolated: List<RefWord>): List<RefWord> {
        if (isolated.size <= 1) return listOf(RefWord(isolated.firstOrNull()?.word ?: "", phraseSounds))
        val guide = isolated.flatMap { it.sounds }
        val guideWord = isolated.flatMapIndexed { w, word -> List(word.sounds.size) { w } }
        val wordOf = IntArray(phraseSounds.size)
        var current = 0
        for (step in align(phraseSounds, guide, ::soundCost, ::soundIndel)) {
            step.hyp?.let { current = guideWord[it] }
            step.ref?.let { wordOf[it] = current }
        }
        return isolated.mapIndexed { w, word ->
            // If no sounds of the phrase landed on this word, use the word said on its own.
            val sounds = phraseSounds.filterIndexed { i, _ -> wordOf[i] == w }
            RefWord(word.word, sounds.ifEmpty { word.sounds })
        }
    }

    private val MARKS = Regex("\\p{Mn}+")
    private val PUNCTUATION = Regex("[\\p{P}\\p{S}]")
    private val SPACES = Regex("\\s+")

    /** Lower-cases and strips punctuation and accents so only the words themselves are compared. */
    fun normalize(text: String): String {
        val noAccents = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(MARKS, "")
        return noAccents.replace(PUNCTUATION, " ").replace(SPACES, " ").trim()
    }

    /** The words of [text] as written, without surrounding punctuation, for per-word reference sounds. */
    fun words(text: String): List<String> =
        text.split(SPACES).map { it.trim { c -> !c.isLetterOrDigit() } }.filter { it.isNotEmpty() }

    private fun chars(text: String): List<String> = normalize(text).filter { it != ' ' }.map { it.toString() }

    /** Overall sound score, and for each word its expected sounds, heard sounds and error ratio. */
    private fun compareSounds(refWords: List<RefWord>, heardSounds: List<String>): SoundComparison {
        val refPhones = refWords.flatMap { it.sounds }
        val refWordOf = refWords.flatMapIndexed { w, word -> List(word.sounds.size) { w } }
        if (refPhones.isEmpty()) return SoundComparison(0.0, refWords.map { WordSounds("", "", 1.0) })

        val steps = align(refPhones, heardSounds, ::soundCost, ::soundIndel)
        val score = score(steps, refPhones.sumOf { soundIndel(it) })

        // Extra sounds count against the word before them.
        val expected = Array(refWords.size) { StringBuilder() }
        val heard = Array(refWords.size) { StringBuilder() }
        val wordCost = DoubleArray(refWords.size)
        var lastWord = 0
        for (step in steps) {
            step.ref?.let { lastWord = refWordOf[it]; expected[lastWord].append(refPhones[it]) }
            step.hyp?.let { heard[lastWord].append(heardSounds[it]) }
            wordCost[lastWord] += step.cost
        }
        return SoundComparison(
            score,
            refWords.indices.map { w ->
                WordSounds(
                    expected = expected[w].toString(),
                    heard = heard[w].toString(),
                    ratio = wordCost[w] / refWords[w].sounds.size.coerceAtLeast(1),
                )
            },
        )
    }

    private fun score(steps: List<Step>, total: Double): Double =
        if (total <= 0) 0.0 else (1.0 - steps.sumOf { it.cost } / total).coerceIn(0.0, 1.0)


    /** Cost of hearing word [b] for word [a]: none if the spelling is close, otherwise up to 1. */
    private fun wordCost(a: String, b: String): Double {
        if (a == b) return 0.0
        val distance = align(a.map { it.toString() }, b.map { it.toString() }, { x, y -> if (x == y) 0.0 else 1.0 }, { 1.0 })
            .sumOf { it.cost }
        val similarity = 1.0 - distance / maxOf(a.length, b.length)
        return if (similarity >= CLOSE_WORD) 0.0 else 1.0 - similarity
    }

    /** Marks that change a sound only slightly: stress, length, nasal, rhotic, tie bars and the like. */
    private fun isMark(s: String) =
        s.isNotEmpty() && s.all { Character.getType(it) == Character.NON_SPACING_MARK.toInt() || it in "ˈˌːˑ˞ʰʲʷ" }

    private fun soundIndel(s: String) = if (isMark(s)) 0.3 else 1.0

    private val CLOSE_SOUNDS: List<Set<String>> = listOf(
        setOf("i", "ɪ", "y", "ʏ", "j"),
        setOf("e", "ɛ", "ə", "ɘ", "œ", "ø", "ɜ", "æ"),
        setOf("a", "ɑ", "æ", "ɐ", "ʌ"),
        setOf("o", "ɔ", "ɒ", "ʌ", "ɤ"),
        setOf("u", "ʊ", "ɯ", "w"),
        setOf("r", "ʁ", "ɹ", "ʀ", "ɾ", "ɻ", "χ"),
        setOf("g", "ɡ"),
        setOf("ʃ", "ʂ", "ɕ"),
        setOf("ʒ", "ʐ", "ʑ"),
        setOf("n", "ŋ", "ɲ"),
        setOf("l", "ɫ", "ʎ"),
    )
    private val VOICING_PAIRS = listOf("p" to "b", "t" to "d", "k" to "g", "k" to "ɡ", "s" to "z", "f" to "v", "ʃ" to "ʒ", "θ" to "ð")
        .flatMap { (a, b) -> listOf(a to b, b to a) }.toSet()

    /** Cost of hearing sound [b] for sound [a]: 0 if equal, small for close sounds, 1 otherwise. */
    private fun soundCost(a: String, b: String): Double = when {
        a == b -> 0.0
        isMark(a) || isMark(b) -> 0.3
        CLOSE_SOUNDS.any { a in it && b in it } -> 0.35
        (a to b) in VOICING_PAIRS -> 0.5
        else -> 1.0
    }

    /** One step of an alignment: a pair, a reference item alone, or a heard item alone. */
    private data class Step(val ref: Int?, val hyp: Int?, val cost: Double)

    /** Lines up two sequences at the lowest total cost (weighted edit distance) and lists the steps. */
    private fun align(
        ref: List<String>,
        hyp: List<String>,
        sub: (String, String) -> Double,
        indel: (String) -> Double,
    ): List<Step> {
        val d = Array(ref.size + 1) { DoubleArray(hyp.size + 1) }
        for (i in 1..ref.size) d[i][0] = d[i - 1][0] + indel(ref[i - 1])
        for (j in 1..hyp.size) d[0][j] = d[0][j - 1] + indel(hyp[j - 1])
        for (i in 1..ref.size) for (j in 1..hyp.size) {
            d[i][j] = minOf(
                d[i - 1][j - 1] + sub(ref[i - 1], hyp[j - 1]),
                d[i - 1][j] + indel(ref[i - 1]),
                d[i][j - 1] + indel(hyp[j - 1]),
            )
        }
        val steps = ArrayDeque<Step>()
        var i = ref.size
        var j = hyp.size
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0) {
                val c = sub(ref[i - 1], hyp[j - 1])
                if (d[i][j] == d[i - 1][j - 1] + c) {
                    steps.addFirst(Step(i - 1, j - 1, c)); i--; j--; continue
                }
            }
            if (i > 0 && d[i][j] == d[i - 1][j] + indel(ref[i - 1])) {
                steps.addFirst(Step(i - 1, null, indel(ref[i - 1]))); i--
            } else {
                steps.addFirst(Step(null, j - 1, indel(hyp[j - 1]))); j--
            }
        }
        return steps.toList()
    }
}

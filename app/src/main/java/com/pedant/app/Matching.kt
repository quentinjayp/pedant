package com.pedant.app

import java.text.Normalizer

/** How well an attempt matched the target phrase, by words (Omnilingual) and by sounds (ZIPA). */
data class AttemptResult(
    val wordScore: Double,
    val soundScore: Double,
    val passed: Boolean,
    /** The target words that went wrong, in phrase order. */
    val issues: List<WordIssue>,
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

private data class SoundIssue(val expected: String, val heard: String)

/** A word of the target phrase and the sounds ZIPA heard when Piper said it. */
data class RefWord(val word: String, val sounds: List<String>)

object Matching {
    const val PASS_WORDS = 0.8
    const val PASS_SOUNDS = 0.7

    fun check(target: String, transcript: String, refWords: List<RefWord>, heardSounds: List<String>): AttemptResult {
        val targetWords = words(target)
        val heardWords = words(transcript)
        // Languages written without spaces (such as Chinese) are compared character by character.
        val spaced = targetWords.size > 1
        val targetUnits = if (spaced) targetWords.map(::normalize) else chars(target)
        val heardUnits = if (spaced) heardWords.map(::normalize) else chars(transcript)

        val wordOps = align(targetUnits, heardUnits)
        val wordHits = wordOps.count { it is Op.Same }
        val wordScore = if (targetUnits.isEmpty()) 0.0 else wordHits.toDouble() / targetUnits.size

        // For each target word, what was heard in its place.
        val heardFor = arrayOfNulls<String>(targetUnits.size)
        val wordWrong = BooleanArray(targetUnits.size)
        for (op in wordOps) {
            when (op) {
                is Op.Same -> heardFor[op.ref] = if (spaced) heardWords[op.hyp] else heardUnits[op.hyp]
                is Op.Different -> {
                    heardFor[op.ref] = if (spaced) heardWords[op.hyp] else heardUnits[op.hyp]
                    wordWrong[op.ref] = true
                }
                is Op.RefOnly -> wordWrong[op.ref] = true
                is Op.HypOnly -> {}
            }
        }

        val (soundScore, soundIssues) = compareSounds(refWords, heardSounds)
        val issues = if (spaced) {
            targetWords.indices.mapNotNull { i ->
                val sound = soundIssues[i]
                if (!wordWrong[i] && sound == null) null
                else WordIssue(targetWords[i], heardFor[i], sound?.expected, sound?.heard)
            }
        } else {
            targetUnits.indices.filter { wordWrong[it] }.map { WordIssue(targetUnits[it], heardFor[it]) } +
                soundIssues.values.map { WordIssue(target, transcript.ifEmpty { null }, it.expected, it.heard) }
        }
        return AttemptResult(
            wordScore = wordScore,
            soundScore = soundScore,
            passed = wordScore >= PASS_WORDS && soundScore >= PASS_SOUNDS,
            issues = issues.take(6),
        )
    }

    /** Lower-cases and strips punctuation and accents so only the words themselves are compared. */
    fun normalize(text: String): String {
        val noAccents = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return noAccents.replace(Regex("[\\p{P}\\p{S}]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    /** The words of [text] as written, without surrounding punctuation, for per-word reference sounds. */
    fun words(text: String): List<String> =
        text.split(Regex("\\s+")).map { it.trim { c -> !c.isLetterOrDigit() } }.filter { it.isNotEmpty() }

    private fun chars(text: String): List<String> = normalize(text).filter { it != ' ' }.map { it.toString() }

    /** Overall sound score, and the words whose sounds differed noticeably, keyed by word index. */
    private fun compareSounds(refWords: List<RefWord>, heardSounds: List<String>): Pair<Double, Map<Int, SoundIssue>> {
        val refPhones = refWords.flatMap { it.sounds }
        val refWordOf = refWords.flatMapIndexed { w, word -> List(word.sounds.size) { w } }
        if (refPhones.isEmpty()) return 0.0 to emptyMap()

        val ops = align(refPhones, heardSounds)
        val errors = ops.count { it !is Op.Same }
        val score = (1.0 - errors.toDouble() / refPhones.size).coerceIn(0.0, 1.0)

        // Per word: expected sounds, heard sounds and error count. Extra sounds count against
        // the word before them.
        val expected = Array(refWords.size) { StringBuilder() }
        val heard = Array(refWords.size) { StringBuilder() }
        val wordErrors = IntArray(refWords.size)
        var lastWord = 0
        for (op in ops) {
            when (op) {
                is Op.Same -> {
                    lastWord = refWordOf[op.ref]
                    expected[lastWord].append(refPhones[op.ref]); heard[lastWord].append(heardSounds[op.hyp])
                }
                is Op.Different -> {
                    lastWord = refWordOf[op.ref]
                    expected[lastWord].append(refPhones[op.ref]); heard[lastWord].append(heardSounds[op.hyp])
                    wordErrors[lastWord]++
                }
                is Op.RefOnly -> {
                    lastWord = refWordOf[op.ref]
                    expected[lastWord].append(refPhones[op.ref])
                    wordErrors[lastWord]++
                }
                is Op.HypOnly -> {
                    heard[lastWord].append(heardSounds[op.hyp])
                    wordErrors[lastWord]++
                }
            }
        }
        val issues = refWords.indices
            .filter { w -> wordErrors[w].toDouble() / refWords[w].sounds.size.coerceAtLeast(1) > 0.3 }
            .associateWith { w -> SoundIssue(expected[w].toString(), heard[w].toString().ifEmpty { "—" }) }
        return score to issues
    }

    private sealed interface Op {
        data class Same(val ref: Int, val hyp: Int) : Op
        data class Different(val ref: Int, val hyp: Int) : Op
        data class RefOnly(val ref: Int) : Op
        data class HypOnly(val hyp: Int) : Op
    }

    /** Lines up two sequences with the fewest changes (edit distance) and lists the steps. */
    private fun align(ref: List<String>, hyp: List<String>): List<Op> {
        val d = Array(ref.size + 1) { IntArray(hyp.size + 1) }
        for (i in 0..ref.size) d[i][0] = i
        for (j in 0..hyp.size) d[0][j] = j
        for (i in 1..ref.size) for (j in 1..hyp.size) {
            val sub = d[i - 1][j - 1] + if (ref[i - 1] == hyp[j - 1]) 0 else 1
            d[i][j] = minOf(sub, d[i - 1][j] + 1, d[i][j - 1] + 1)
        }
        val ops = ArrayDeque<Op>()
        var i = ref.size
        var j = hyp.size
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + if (ref[i - 1] == hyp[j - 1]) 0 else 1 -> {
                    ops.addFirst(if (ref[i - 1] == hyp[j - 1]) Op.Same(i - 1, j - 1) else Op.Different(i - 1, j - 1))
                    i--; j--
                }
                i > 0 && d[i][j] == d[i - 1][j] + 1 -> { ops.addFirst(Op.RefOnly(i - 1)); i-- }
                else -> { ops.addFirst(Op.HypOnly(j - 1)); j-- }
            }
        }
        return ops.toList()
    }
}

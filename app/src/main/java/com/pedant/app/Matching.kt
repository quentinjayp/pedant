package com.pedant.app

import java.text.Normalizer

/** How well an attempt matched the target phrase, by words (Omnilingual) and by sounds (ZIPA). */
data class AttemptResult(
    val wordScore: Double,
    val soundScore: Double,
    val passed: Boolean,
    /** Target words that were missing or wrong in the transcript. */
    val missedWords: List<String>,
    /** The words whose sounds differed most from the expected sounds. */
    val soundIssues: List<SoundIssue>,
)

data class SoundIssue(val word: String, val expected: String, val heard: String)

/** A word of the target phrase and the sounds ZIPA heard when Piper said it. */
data class RefWord(val word: String, val sounds: List<String>)

object Matching {
    const val PASS_WORDS = 0.8
    const val PASS_SOUNDS = 0.7

    fun check(target: String, transcript: String, refWords: List<RefWord>, heardSounds: List<String>): AttemptResult {
        val targetUnits = units(target)
        val heardUnits = units(transcript)
        val wordOps = align(targetUnits, heardUnits)
        val wordHits = wordOps.count { it is Op.Same }
        val wordScore = if (targetUnits.isEmpty()) 0.0 else wordHits.toDouble() / targetUnits.size
        val missed = wordOps.filterIsInstance<Op.RefOnly>().map { targetUnits[it.ref] } +
            wordOps.filterIsInstance<Op.Different>().map { targetUnits[it.ref] }

        val (soundScore, issues) = compareSounds(refWords, heardSounds)
        return AttemptResult(
            wordScore = wordScore,
            soundScore = soundScore,
            passed = wordScore >= PASS_WORDS && soundScore >= PASS_SOUNDS,
            missedWords = missed,
            soundIssues = issues,
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

    /** Words, or characters for languages written without spaces (such as Chinese). */
    private fun units(text: String): List<String> {
        val n = normalize(text)
        if (n.isEmpty()) return emptyList()
        return if (' ' in n || n.length <= 3) n.split(' ') else n.map { it.toString() }
    }

    private fun compareSounds(refWords: List<RefWord>, heardSounds: List<String>): Pair<Double, List<SoundIssue>> {
        val refPhones = refWords.flatMap { it.sounds }
        val refWordOf = refWords.flatMapIndexed { w, word -> List(word.sounds.size) { w } }
        if (refPhones.isEmpty()) return 0.0 to emptyList()

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
            .sortedByDescending { wordErrors[it] }
            .take(3)
            .map { w -> SoundIssue(refWords[w].word, expected[w].toString(), heard[w].toString().ifEmpty { "—" }) }
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

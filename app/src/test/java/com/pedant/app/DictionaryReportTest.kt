package com.pedant.app

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

/**
 * Runs every word of the built-in openings, in every language, through the word bubble's own
 * lookup ([Dictionary.describe]) and reports what looks wrong, so problems are found by kind rather
 * than one word at a time. Writes build/dictionary-report/summary.txt (counts and examples per
 * kind) and words.tsv (every word and what the bubble would show).
 *
 * Needs the dictionaries, unzipped, in the folder named by DICT_DIR (<code>.db); skipped without it.
 * Run by the dictionary-report job in .github/workflows/android.yml.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DictionaryReportTest {

    private enum class Problem(val about: String) {
        NO_ENTRY("not in the dictionary (the bubble falls back to Gemma)"),
        NO_IPA("no IPA"),
        NAME_FIRST("a name shown before an ordinary word"),
        ONLY_NAME("only a name"),
        EMPTY_GROUP("a part of speech with no meanings"),
        FORM_AS_MEANING("a form's description shown as a meaning (its base word wasn't found)"),
        MANY_GROUPS("four or more parts of speech"),
    }

    private val formDescription = Regex(
        "(plural|singular|participle|person|genitive|dative|accusative|nominative|instrumental|locative|" +
            "vocative|form|tense|inflection|comparative|superlative|feminine|masculine|neuter)\\b.*\\bof\\s+\\S+$",
        RegexOption.IGNORE_CASE,
    )

    @Test
    fun report() {
        val dictDir = System.getenv("DICT_DIR")
        assumeTrue("DICT_DIR not set", !dictDir.isNullOrEmpty())
        val openings = JSONObject(File("src/main/assets/openings.json").readText()).getJSONObject("openings")
        val sentences = sortedMapOf<String, MutableList<String>>()
        for (key in openings.keys()) {
            val reply = Regex("REPLY:\\s*(.*?)(?:\\nENGLISH:|$)", RegexOption.DOT_MATCHES_ALL)
                .find(openings.getString(key))?.groupValues?.get(1)?.trim() ?: continue
            sentences.getOrPut(key.substringBefore('-')) { mutableListOf() } += reply
        }

        val out = File("build/dictionary-report").apply { mkdirs() }
        val summary = StringBuilder()
        val rows = StringBuilder("language\tword\tproblems\tipa\tshown\tsentence\n")
        val totals = Problem.entries.associateWith { 0 }.toMutableMap()
        var words = 0
        for ((code, list) in sentences) {
            val file = File(dictDir, "$code.db")
            if (!file.exists()) {
                summary.append("$code: no dictionary\n")
                continue
            }
            val found = Problem.entries.associateWith { mutableListOf<String>() }
            val seen = mutableSetOf<String>()
            Dictionary(file).use { dict ->
                for (sentence in list) {
                    for (word in tappableWords(sentence)) {
                        if (!seen.add(word.lowercase())) continue
                        words++
                        val d = dict.describe(word)
                        val problems = problems(d)
                        problems.forEach { found.getValue(it) += word }
                        val shown = d?.text?.replace("\n", " ⏎ ").orEmpty()
                        rows.append("$code\t$word\t${problems.joinToString(",")}\t${d?.ipa.orEmpty()}\t$shown\t$sentence\n")
                    }
                }
            }
            summary.append("== $code: ${seen.size} words\n")
            for ((problem, list2) in found) {
                if (list2.isEmpty()) continue
                totals[problem] = totals.getValue(problem) + list2.size
                summary.append("  ${problem.name} (${list2.size}): ${list2.take(12).joinToString(", ")}\n")
            }
        }
        val release = runCatching { JSONObject(File("../dictionaries/manifest.json").readText()).getString("release") }.getOrDefault("?")
        val header = StringBuilder("$words words checked, dictionaries $release\n")
        for ((problem, n) in totals) header.append("  ${problem.name}: $n — ${problem.about}\n")
        File(out, "summary.txt").writeText(header.append("\n").append(summary).toString())
        File(out, "words.tsv").writeText(rows.toString())
        println(header)
    }

    private fun problems(d: Dictionary.Description?): List<Problem> {
        if (d == null) return listOf(Problem.NO_ENTRY)
        val labels = d.groups.map { it.label.orEmpty() }
        val isName = labels.map { it.startsWith("name") }
        return buildList {
            if (d.ipa == null) add(Problem.NO_IPA)
            if (isName.first() && isName.any { !it }) add(Problem.NAME_FIRST)
            if (isName.all { it }) add(Problem.ONLY_NAME)
            if (d.groups.any { it.meanings.isEmpty() }) add(Problem.EMPTY_GROUP)
            if (d.groups.any { g -> g.meanings.any { formDescription.containsMatchIn(it) } }) add(Problem.FORM_AS_MEANING)
            if (d.groups.size >= 4) add(Problem.MANY_GROUPS)
        }
    }
}

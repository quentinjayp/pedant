package com.pedant.app

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** A phrase the learner said in the conversation, and the line in their own language it was translated from. */
data class QuizSource(val phrase: String, val english: String)

/** The kinds of question a quiz asks. [needsGemma] ones may ask Gemma, and take a few seconds to make. */
enum class QuizKind(val title: String, val needsGemma: Boolean) {
    FillGap("Fill the gap", true),
    /** Titled with the language's articles ("Le or la?"); see [articleTitle]. */
    Article("Which article?", true),
    Heard("What did you hear?", true),
    PhraseMeaning("What does it mean?", false),
    WordMeaning("What does this word mean?", true),
    Match("Match the pairs", true),
    Order("Put it in order", false),
    Speak("Say it from memory", false),
    /** Intermediate only. */
    Mistake("Spot the mistake", true),
}

/** "Le or la?", "Der, die or das?": the title of an article question in a language with [articles]. */
fun articleTitle(articles: List<String>): String {
    val names = if (articles.size <= 1) articles.joinToString("") else
        articles.dropLast(1).joinToString(", ") + " or " + articles.last()
    return names.replaceFirstChar { it.uppercase() } + "?"
}

sealed interface QuizQuestion {
    val kind: QuizKind
}

/**
 * Pick one of [options]; [answer] is the right one's index. [shown] is what's shown (the phrase
 * with a gap, the noun, the word), [hint] the learner's own line for it, and [spoken] what the
 * voice says (the word or phrase heard).
 */
data class ChoiceQuestion(
    override val kind: QuizKind,
    val shown: String,
    val hint: String?,
    val spoken: String?,
    val options: List<String>,
    val answer: Int,
) : QuizQuestion

/** Put [words] (a sentence's, in order) back in order; they start in the order [start] (indices into [words]). */
data class OrderQuestion(val words: List<String>, val start: List<Int>, val hint: String?) : QuizQuestion {
    override val kind get() = QuizKind.Order
}

/** Pair each of [words] with its meaning: [meanings] are in the words' order and shown in the order [shuffled]. */
data class MatchQuestion(val words: List<String>, val meanings: List<String>, val shuffled: List<Int>) : QuizQuestion {
    override val kind get() = QuizKind.Match
}

/** Say [phrase] from memory, given the learner's own line for it ([english]). */
data class SpeakQuestion(val english: String, val phrase: String) : QuizQuestion {
    override val kind get() = QuizKind.Speak
}

/** [sentence] has one word in the wrong form, at [wrong] among its words; [right] is what it should be. */
data class MistakeQuestion(val sentence: String, val wrong: Int, val right: String, val hint: String?) : QuizQuestion {
    override val kind get() = QuizKind.Mistake
}

/** How the learner answered the question on screen. */
sealed interface QuizAnswer {
    data class Chosen(val option: Int) : QuizAnswer
    data class Ordered(val order: List<Int>) : QuizAnswer
    data object Matched : QuizAnswer
    data class Spoken(val result: AttemptResult) : QuizAnswer
    data class Tapped(val position: Int) : QuizAnswer
}

/** Whether [answer] is right for [question]. */
fun isRight(question: QuizQuestion, answer: QuizAnswer): Boolean = when {
    question is ChoiceQuestion && answer is QuizAnswer.Chosen -> answer.option == question.answer
    // By the words, so two of the same word can go either way round.
    question is OrderQuestion && answer is QuizAnswer.Ordered -> answer.order.map { question.words[it] } == question.words
    question is MatchQuestion && answer is QuizAnswer.Matched -> true
    question is SpeakQuestion && answer is QuizAnswer.Spoken -> answer.result.passed
    question is MistakeQuestion && answer is QuizAnswer.Tapped -> answer.position == question.wrong
    else -> false
}

/**
 * The quiz on a conversation: [phrases] are the phrases the learner said in it, [plan] the phrase
 * each question is on (by index), [questions] those made so far (they're made one at a time, in
 * order, while the learner answers), and [position] the question on screen. A planned question
 * that can't be made is dropped from [plan].
 */
data class Quiz(
    val mode: Mode,
    val phrases: List<QuizSource>,
    val plan: List<Int>,
    val questions: List<QuizQuestion> = emptyList(),
    val position: Int = 0,
) {
    val current: QuizQuestion? get() = questions.getOrNull(position)
    val finished: Boolean get() = position >= plan.size

    /** The longest sentence put in order or said from memory, in words. */
    val wordLimit: Int get() = if (mode == Mode.Beginner) BEGINNER_WORDS else INTERMEDIATE_WORDS

    fun toJson(): JSONObject = JSONObject()
        .put("mode", mode.name)
        .put("phrases", JSONArray(phrases.map { JSONObject().put("phrase", it.phrase).put("english", it.english) }))
        .put("plan", JSONArray(plan))
        .put("questions", JSONArray(questions.map(::questionJson)))
        .put("position", position)

    companion object {
        const val BEGINNER_WORDS = 5
        const val INTERMEDIATE_WORDS = 8

        /** The most questions a quiz has: one for every [PHRASES_PER_QUESTION] phrases said, up to this. */
        const val MAX_QUESTIONS = 10
        const val PHRASES_PER_QUESTION = 2

        /** The phrases said in [chat]: each phrase translated for the learner that they then tried saying. */
        fun phrases(chat: List<ChatItem>): List<QuizSource> {
            val tried = chat.filterIsInstance<ChatItem.Attempt>().map { it.phrase }.toSet()
            return chat.filterIsInstance<ChatItem.Say>()
                .filter { it.phrase.isNotBlank() && it.phrase in tried }
                .distinctBy { it.phrase }
                .map { QuizSource(it.phrase, it.english) }
        }

        /** How many questions a quiz on [chat] has. */
        fun length(chat: List<ChatItem>): Int = minOf(phrases(chat).size / PHRASES_PER_QUESTION, MAX_QUESTIONS)

        /** A new quiz on [chat]: each question on a different phrase, picked at random. */
        fun start(chat: List<ChatItem>, mode: Mode, random: Random = Random.Default): Quiz {
            val phrases = phrases(chat)
            return Quiz(mode, phrases, phrases.indices.shuffled(random).take(length(chat)))
        }

        fun fromJson(o: JSONObject) = Quiz(
            mode = Mode.valueOf(o.getString("mode")),
            phrases = o.getJSONArray("phrases").let { a ->
                (0 until a.length()).map { a.getJSONObject(it).let { p -> QuizSource(p.getString("phrase"), p.getString("english")) } }
            },
            plan = o.getJSONArray("plan").ints(),
            questions = o.getJSONArray("questions").let { a -> (0 until a.length()).map { question(a.getJSONObject(it)) } },
            position = o.getInt("position"),
        )

        private fun JSONArray.ints() = (0 until length()).map { getInt(it) }
        private fun JSONArray.strings() = (0 until length()).map { getString(it) }
        private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name) || !has(name)) null else getString(name)

        private fun questionJson(q: QuizQuestion): JSONObject = when (q) {
            is ChoiceQuestion -> JSONObject().put("type", "choice").put("kind", q.kind.name).put("shown", q.shown)
                .put("hint", q.hint).put("spoken", q.spoken).put("options", JSONArray(q.options)).put("answer", q.answer)
            is OrderQuestion -> JSONObject().put("type", "order").put("words", JSONArray(q.words))
                .put("start", JSONArray(q.start)).put("hint", q.hint)
            is MatchQuestion -> JSONObject().put("type", "match").put("words", JSONArray(q.words))
                .put("meanings", JSONArray(q.meanings)).put("shuffled", JSONArray(q.shuffled))
            is SpeakQuestion -> JSONObject().put("type", "speak").put("english", q.english).put("phrase", q.phrase)
            is MistakeQuestion -> JSONObject().put("type", "mistake").put("sentence", q.sentence).put("wrong", q.wrong)
                .put("right", q.right).put("hint", q.hint)
        }

        private fun question(o: JSONObject): QuizQuestion = when (o.getString("type")) {
            "choice" -> ChoiceQuestion(
                QuizKind.valueOf(o.getString("kind")), o.getString("shown"), o.optStringOrNull("hint"),
                o.optStringOrNull("spoken"), o.getJSONArray("options").strings(), o.getInt("answer"),
            )
            "order" -> OrderQuestion(o.getJSONArray("words").strings(), o.getJSONArray("start").ints(), o.optStringOrNull("hint"))
            "match" -> MatchQuestion(o.getJSONArray("words").strings(), o.getJSONArray("meanings").strings(), o.getJSONArray("shuffled").ints())
            "speak" -> SpeakQuestion(o.getString("english"), o.getString("phrase"))
            else -> MistakeQuestion(o.getString("sentence"), o.getInt("wrong"), o.getString("right"), o.optStringOrNull("hint"))
        }
    }
}

/** Making questions from what was said, and checking what Gemma suggests for them. */
object QuizMaker {
    /** [text]'s sentences: split after a full stop, question mark or exclamation mark followed by a space. */
    fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?…])\\s+")).map { it.trim() }.filter { wordsOf(it).isNotEmpty() }

    /** Words the same but for case and curly apostrophes. */
    fun same(a: String, b: String) = Dictionary.key(a) == Dictionary.key(b)

    /** A put-it-in-order question on a sentence of [source] of 3 to [limit] words, or null if it has none. */
    fun order(source: QuizSource, limit: Int, random: Random = Random.Default): OrderQuestion? {
        val all = sentences(source.phrase)
        val sentence = all.filter { wordsOf(it).size in 3..limit }.randomOrNull(random) ?: return null
        val words = wordsOf(sentence)
        // A start that isn't already the sentence (by its words: two of the same word don't count).
        val start = (1..20).map { words.indices.shuffled(random) }.firstOrNull { o -> o.map { words[it] } != words } ?: return null
        return OrderQuestion(words, start, source.english.takeIf { all.size == 1 })
    }

    /** A say-it-from-memory question on [source], if it's 2 to [limit] words. */
    fun speak(source: QuizSource, limit: Int): SpeakQuestion? =
        source.takeIf { wordsOf(it.phrase).size in 2..limit }?.let { SpeakQuestion(it.english, it.phrase) }

    /** A what-does-it-mean question: [source] said by the voice, its own line among two others from [all]. */
    fun phraseMeaning(source: QuizSource, all: List<QuizSource>, random: Random = Random.Default): ChoiceQuestion? {
        val others = all.map { it.english }.filter { it.isNotBlank() && it.trim() != source.english.trim() }.distinct()
        if (others.size < 2) return null
        return choice(QuizKind.PhraseMeaning, "", null, source.phrase, source.english, others.shuffled(random).take(2), random)
    }

    /** A question with [right] and [wrong] as its options, in a random order. */
    fun choice(
        kind: QuizKind, shown: String, hint: String?, spoken: String?, right: String, wrong: List<String>,
        random: Random = Random.Default,
    ): ChoiceQuestion {
        val options = (wrong + right).shuffled(random)
        return ChoiceQuestion(kind, shown, hint, spoken, options, options.indexOf(right))
    }

    /** [text] with the word at [position] (among its words) replaced by [replacement]. */
    fun replaceWord(text: String, position: Int, replacement: String): String {
        val range = wordSpans(text)[position].first
        return text.replaceRange(range, replacement)
    }

    /**
     * A word of [sentence] and wrong forms of it, from Gemma's answer to the quiz-forms question:
     * the word's position among the sentence's words and the wrong forms (single words, each
     * different from it and from each other). Null if the answer doesn't fit the sentence.
     */
    fun parseForms(raw: String, sentence: String): Pair<Int, List<String>>? {
        val word = raw.substringAfter("WORD:", "").substringBefore("WRONG:").let(::cleanWord)
        val words = wordsOf(sentence)
        val position = words.indexOfFirst { same(it, word) }
        if (word.isEmpty() || position < 0) return null
        val wrong = alternatives(raw.substringAfter("WRONG:", ""), words[position])
        return if (wrong.isEmpty()) null else position to wrong
    }

    /** Words that look or sound like [word], from Gemma's answer to the sound-alike question. */
    fun parseAlike(raw: String, word: String): List<String> = alternatives(raw.substringAfter("ALIKE:", ""), word)

    /** The nouns and their articles, from Gemma's answer to the articles question; only the [articles] given. */
    fun parseArticles(raw: String, articles: List<String>): List<Pair<String, String>> =
        raw.lines().mapNotNull { line ->
            val parts = line.split('|').map(::cleanWord)
            val article = articles.firstOrNull { a -> parts.size == 2 && same(a, parts[1]) }
            if (article == null || wordsOf(parts[0]).size != 1) null else wordsOf(parts[0])[0] to article
        }.distinctBy { Dictionary.key(it.first) }

    /** The single words in [text] (split at "|"), other than [word] and each other. */
    private fun alternatives(text: String, word: String): List<String> =
        text.lines().first().split('|').map(::cleanWord)
            .filter { it.isNotEmpty() && wordsOf(it) == listOf(it) && !same(it, word) }
            .distinctBy(Dictionary::key)

    private fun cleanWord(s: String) = s.trim().trim('"', '“', '”', '«', '»', '\'', '*', '.', ',', '`').trim()

    /**
     * A meaning short enough for an option: its first part (before a semicolon), without a part of
     * speech in brackets at either end, as Gemma writes them ("want (verb)").
     */
    fun shortMeaning(meaning: String): String = meaning.lines().first()
        .substringBefore(';')
        .replace(Regex("^\\s*\\([^)]*\\)\\s*"), "")
        .replace(Regex("\\s*\\([^)]*\\)\\s*$"), "")
        .trim().trimEnd('.', ',').trim()
}

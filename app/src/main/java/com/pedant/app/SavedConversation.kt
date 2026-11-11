package com.pedant.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The most recent conversation, saved whenever the app is idle so it can be continued later,
 * exactly where it was left. Only one is kept: starting a new conversation replaces it.
 */
data class SavedConversation(
    val languageCode: String,
    val mode: Mode,
    val scenario: Scenario,
    val chat: List<ChatItem>,
    /** What was said to and by Gemma, so it can pick up the conversation. */
    val history: List<Tutor.Turn>,
    /** Learning mode: the phrase being practised and its expected sounds, if any. */
    val drillPhrase: String?,
    val drillWords: List<RefWord>,
    val drillAttempts: Int,
    val editableIndex: Int?,
) {
    companion object {
        fun file(dir: File) = File(dir, "conversation.json")

        fun load(dir: File): SavedConversation? = runCatching {
            fromJson(JSONObject(file(dir).readText()))
        }.getOrNull()

        fun delete(dir: File) {
            file(dir).delete()
        }

        private fun fromJson(o: JSONObject) = SavedConversation(
            languageCode = o.getString("language"),
            mode = Mode.valueOf(o.getString("mode")),
            scenario = Scenario.valueOf(o.getString("scenario")),
            chat = o.getJSONArray("chat").objects().map(::chatItem),
            history = o.getJSONArray("history").objects().map { Tutor.Turn(it.getBoolean("learner"), it.getString("text")) },
            drillPhrase = o.optStringOrNull("drillPhrase"),
            drillWords = o.optJSONArray("drillWords")?.objects()?.map {
                RefWord(it.getString("word"), it.getJSONArray("sounds").strings())
            }.orEmpty(),
            drillAttempts = o.optInt("drillAttempts"),
            editableIndex = if (o.has("editableIndex")) o.getInt("editableIndex") else null,
        )

        private fun chatItem(o: JSONObject): ChatItem = when (o.getString("type")) {
            "user" -> ChatItem.User(o.getString("text"), o.optStringOrNull("sounds"))
            "tutor" -> ChatItem.Tutor(o.optStringOrNull("correction"), o.getString("reply"), o.optStringOrNull("english"))
            "say" -> ChatItem.Say(o.getString("english"), o.getString("phrase"))
            "attempt" -> ChatItem.Attempt(
                o.getString("heard"), o.getString("sounds"), attemptResult(o.getJSONObject("result")), o.optString("phrase"),
            )
            else -> ChatItem.Note(o.getString("text"))
        }

        private fun attemptResult(o: JSONObject) = AttemptResult(
            wordScore = o.getDouble("wordScore"),
            soundScore = o.getDouble("soundScore"),
            passed = o.getBoolean("passed"),
            issues = o.getJSONArray("issues").objects().map {
                WordIssue(it.getString("word"), it.optStringOrNull("heardWord"), it.optStringOrNull("expectedSounds"), it.optStringOrNull("heardSounds"))
            },
            words = o.optJSONArray("words")?.objects()?.map {
                WordResult(
                    word = it.getString("word"),
                    expected = it.getJSONArray("expected").strings(),
                    heard = it.getString("heard"),
                    score = it.getDouble("score"),
                    heardWord = it.optStringOrNull("heardWord"),
                    passed = it.getBoolean("passed"),
                    heardFrom = it.optInt("heardFrom", -1),
                    heardTo = it.optInt("heardTo", -1),
                )
            }.orEmpty(),
        )

        private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
        private fun JSONArray.strings() = (0 until length()).map { getString(it) }
        private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else optString(name)
    }

    /** Writes this conversation, replacing the saved one; a crash midway leaves the old one intact. */
    fun save(dir: File) {
        val o = JSONObject()
            .put("language", languageCode)
            .put("mode", mode.name)
            .put("scenario", scenario.name)
            .put("chat", JSONArray(chat.map { json(it) }))
            .put("history", JSONArray(history.map { JSONObject().put("learner", it.fromLearner).put("text", it.text) }))
            .put("drillPhrase", drillPhrase)
            .put("drillWords", JSONArray(drillWords.map { JSONObject().put("word", it.word).put("sounds", JSONArray(it.sounds)) }))
            .put("drillAttempts", drillAttempts)
            .put("editableIndex", editableIndex)
        val tmp = File(dir, "conversation.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file(dir))
    }

    private fun json(item: ChatItem): JSONObject = when (item) {
        is ChatItem.User -> JSONObject().put("type", "user").put("text", item.text).put("sounds", item.sounds)
        is ChatItem.Tutor -> JSONObject().put("type", "tutor").put("correction", item.correction).put("reply", item.reply)
            .put("english", item.english)
        is ChatItem.Say -> JSONObject().put("type", "say").put("english", item.english).put("phrase", item.phrase)
        is ChatItem.Attempt -> JSONObject().put("type", "attempt").put("heard", item.heard).put("sounds", item.sounds)
            .put("result", json(item.result)).put("phrase", item.phrase)
        is ChatItem.Note -> JSONObject().put("type", "note").put("text", item.text)
    }

    private fun json(r: AttemptResult) = JSONObject()
        .put("wordScore", r.wordScore)
        .put("soundScore", r.soundScore)
        .put("passed", r.passed)
        .put("issues", JSONArray(r.issues.map {
            JSONObject().put("word", it.word).put("heardWord", it.heardWord)
                .put("expectedSounds", it.expectedSounds).put("heardSounds", it.heardSounds)
        }))
        .put("words", JSONArray(r.words.map {
            JSONObject().put("word", it.word).put("expected", JSONArray(it.expected)).put("heard", it.heard)
                .put("score", it.score).put("heardWord", it.heardWord).put("passed", it.passed)
                .put("heardFrom", it.heardFrom).put("heardTo", it.heardTo)
        }))
}

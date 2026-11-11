package com.pedant.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

/** A file in a Hugging Face model repository. */
data class HfFile(val repo: String, val path: String, val size: Long) {
    val url get() = "https://huggingface.co/$repo/resolve/main/$path"
}

/** A language's dictionary as listed in the published manifest. */
data class DictionaryInfo(val version: String, val url: String, val bytes: Long, val sha256: String)

/**
 * Downloads the models from Hugging Face into app storage and tells the rest of the app
 * where they are. Downloads resume where they left off if interrupted.
 */
class ModelStore(context: Context) {

    private val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "models")

    private val gemmaDir = File(root, "gemma")
    private val asrDir = File(root, "asr")
    private val zipaDir = File(root, "zipa")
    val espeakData = File(root, "espeak-ng-data")
    private fun voiceDir(voice: String) = File(root, "piper/$voice")

    val gemmaModel: File? get() = gemmaDir.listFiles { f -> f.name.endsWith(".litertlm") }?.firstOrNull()
    val asrModel = File(asrDir, MODEL_FILE)
    val asrTokens = File(asrDir, TOKENS_FILE)
    val zipaModel = File(zipaDir, MODEL_FILE)
    val zipaTokens = File(zipaDir, TOKENS_FILE)
    fun voiceModel(voice: String) = File(voiceDir(voice), "$voice.onnx")
    fun voiceTokens(voice: String) = File(voiceDir(voice), "tokens.txt")

    private val readyMarker = ".complete"

    private fun isReady(dir: File) = File(dir, readyMarker).exists()

    fun coreReady() = listOf(gemmaDir, asrDir, zipaDir).all(::isReady)

    /** The voice already downloaded for [language], if any. */
    fun installedVoice(language: Language): String? =
        language.piperVoices.firstOrNull { voice ->
            isReady(voiceDir(voice)) && isReady(espeakData)
        }

    /**
     * Downloads Gemma, the speech recognizer and ZIPA, skipping any already downloaded.
     * [onProgress] gets (bytes done, bytes total, label).
     */
    suspend fun downloadCore(onProgress: (Long, Long, String) -> Unit) = withContext(Dispatchers.IO) {
        val gemmaFiles = listRepo(GEMMA_REPO).filter { it.path.endsWith(".litertlm") }
        val gemma = pickGemmaFile(gemmaFiles)
            ?: throw IOException("No .litertlm file found in $GEMMA_REPO")
        val asrRepo = ASR_REPOS.firstNotNullOfOrNull { repo -> runCatching { repo to listRepo(repo) }.getOrNull() }
            ?: throw IOException("Speech recognizer repository not found on Hugging Face")
        val modelFiles = setOf(MODEL_FILE, TOKENS_FILE)
        val asrFiles = asrRepo.second.filter { it.path in modelFiles }
        val zipaFiles = listRepo(ZIPA_REPO).filter { it.path in modelFiles }

        val jobs = listOf(gemma to File(gemmaDir, gemma.path.substringAfterLast('/'))) +
            asrFiles.map { it to File(asrDir, it.path) } +
            zipaFiles.map { it to File(zipaDir, it.path) }
        downloadAll(jobs.filterNot { (file, dest) -> dest.exists() && dest.length() == file.size }, onProgress)
        listOf(gemmaDir, asrDir, zipaDir).forEach { File(it, readyMarker).createNewFile() }
    }

    /**
     * Downloads the first available Piper voice for [language], plus espeak-ng's data the first
     * time, and returns the voice's name.
     */
    suspend fun downloadVoice(language: Language, onProgress: (Long, Long, String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val (voice, files) = language.piperVoices.firstNotNullOfOrNull { v ->
                runCatching { v to listRepo("csukuangfj/vits-piper-$v") }.getOrNull()
            } ?: throw IOException("No voice found for ${language.englishName}")
            val jobs = mutableListOf<Pair<HfFile, File>>()
            files.filter { it.path == "$voice.onnx" || it.path == "tokens.txt" }
                .forEach { jobs += it to File(voiceDir(voice), it.path) }
            val needEspeak = !isReady(espeakData)
            if (needEspeak) {
                files.filter { it.path.startsWith("espeak-ng-data/") }
                    .forEach { jobs += it to File(root, it.path) }
            }
            downloadAll(jobs, onProgress)
            if (needEspeak) File(espeakData, readyMarker).createNewFile()
            File(voiceDir(voice), readyMarker).createNewFile()
            voice
        }

    private val dictionaryDir = File(root, "dictionaries")

    /** The downloaded dictionary for [language], if any. */
    fun dictionaryFile(language: Language): File? =
        File(dictionaryDir, "${language.code}.db").takeIf { it.exists() }

    /** The version of the downloaded dictionary for [language], if any. */
    fun dictionaryVersion(language: Language): String? =
        File(dictionaryDir, "${language.code}.version").takeIf { it.exists() }?.readText()?.trim()

    /** The newest published dictionary for [language], or null if there is none (or no connection). */
    suspend fun latestDictionary(language: Language): DictionaryInfo? = withContext(Dispatchers.IO) {
        val conn = (URL(DICTIONARY_MANIFEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val manifest = JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            val release = manifest.optString("release").ifEmpty { return@withContext null }
            val entry = manifest.optJSONObject("languages")?.optJSONObject(language.code) ?: return@withContext null
            DictionaryInfo(
                version = manifest.getString("version"),
                url = "$DICTIONARY_RELEASES/$release/${entry.getString("file")}",
                bytes = entry.getLong("bytes"),
                sha256 = entry.getString("sha256"),
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads [info] for [language], replacing any older dictionary, and returns the file. */
    suspend fun downloadDictionary(language: Language, info: DictionaryInfo, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val gz = File(dictionaryDir, "${language.code}-${info.version}.db.gz")
            download(info.url, info.bytes, gz) { onProgress(it, info.bytes) }
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                gz.inputStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (hash != info.sha256) throw IOException("The ${language.englishName} dictionary download was damaged")
                val tmp = File(dictionaryDir, "${language.code}.db.tmp")
                GZIPInputStream(gz.inputStream()).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val dest = File(dictionaryDir, "${language.code}.db")
                if (!tmp.renameTo(dest)) throw IOException("Could not save the ${language.englishName} dictionary")
                File(dictionaryDir, "${language.code}.version").writeText(info.version)
                dest
            } finally {
                gz.delete()
            }
        }

    private suspend fun downloadAll(jobs: List<Pair<HfFile, File>>, onProgress: (Long, Long, String) -> Unit) {
        val total = jobs.sumOf { it.first.size }
        var done = 0L
        for ((file, dest) in jobs) {
            val label = file.path.substringAfterLast('/')
            download(file.url, file.size, dest) { fileDone -> onProgress(done + fileDone, total, label) }
            done += file.size
            onProgress(done, total, label)
        }
    }

    /** Downloads [url] ([size] bytes, or 0 if unknown) to [dest], resuming a partial download. */
    private suspend fun download(url: String, size: Long, dest: File, onBytes: (Long) -> Unit) {
        if (dest.exists() && dest.length() == size) return
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        var attempt = 0
        while (true) {
            try {
                val have = if (part.exists()) part.length() else 0L
                if (have < size || size == 0L) {
                    val conn = open(url, have)
                    val append = have > 0 && conn.responseCode == HttpURLConnection.HTTP_PARTIAL
                    var written = if (append) have else 0L
                    conn.inputStream.use { input ->
                        FileOutputStream(part, append).use { out ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                written += n
                                onBytes(written)
                            }
                        }
                    }
                }
                if (size > 0 && part.length() != size) {
                    throw IOException("Incomplete download of ${dest.name}")
                }
                if (!part.renameTo(dest)) throw IOException("Could not save ${dest.name}")
                return
            } catch (e: IOException) {
                if (++attempt >= 5) throw e
                delay(2000L * attempt)
            }
        }
    }

    /** Opens [url], following redirects by hand so the Range header survives each hop. */
    private fun open(url: String, from: Long): HttpURLConnection {
        var target = URL(url)
        repeat(10) {
            val conn = target.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            if (from > 0) conn.setRequestProperty("Range", "bytes=$from-")
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: throw IOException("Redirect without location")
                conn.disconnect()
                target = URL(target, location)
                return@repeat
            }
            if (code !in 200..299) throw IOException("HTTP $code for $target")
            return conn
        }
        throw IOException("Too many redirects for $url")
    }

    private fun listRepo(repo: String): List<HfFile> {
        val conn = open("https://huggingface.co/api/models/$repo/tree/main?recursive=true", 0)
        val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        val array = JSONArray(body)
        return (0 until array.length()).map { array.getJSONObject(it) }
            .filter { it.optString("type") == "file" }
            .map { obj ->
                val lfs = obj.optJSONObject("lfs")
                HfFile(repo, obj.getString("path"), lfs?.optLong("size") ?: obj.optLong("size"))
            }
    }

    /** Picks the general-purpose Gemma file, skipping chip-specific NPU builds. */
    private fun pickGemmaFile(files: List<HfFile>): HfFile? {
        val generic = files.filterNot { f ->
            val name = f.path.lowercase()
            listOf("qualcomm", "mediatek", "sm8", "mt6", "npu", "web", "tpu").any { it in name }
        }
        return (generic.ifEmpty { files }).minByOrNull { it.path.length }
    }

    companion object {
        private const val MODEL_FILE = "model.int8.onnx"
        private const val TOKENS_FILE = "tokens.txt"
        /** Lists the published dictionaries; see .github/workflows/dictionaries.yml. */
        private const val DICTIONARY_MANIFEST =
            "https://raw.githubusercontent.com/quentinjayp/pedant/HEAD/dictionaries/manifest.json"
        private const val DICTIONARY_RELEASES = "https://github.com/quentinjayp/pedant/releases/download"
        const val GEMMA_REPO = "litert-community/gemma-4-E4B-it-litert-lm"
        const val ZIPA_REPO = "anyspeech/zipa-large-crctc-500k"
        val ASR_REPOS = listOf(
            "csukuangfj2/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12",
            "csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12",
        )
    }
}

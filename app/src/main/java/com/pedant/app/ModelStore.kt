package com.pedant.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** A file in a Hugging Face model repository. */
data class HfFile(val repo: String, val path: String, val size: Long)

/**
 * Downloads the models from Hugging Face into app storage and tells the rest of the app
 * where they are. Downloads resume where they left off if interrupted.
 */
class ModelStore(context: Context) {

    private val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "models")

    private val gemmaDir = File(root, "gemma")
    private val asrDir = File(root, "asr")
    private val espeakDir = File(root, "espeak-ng-data")
    private fun voiceDir(voice: String) = File(root, "piper/$voice")

    val gemmaModel: File? get() = gemmaDir.listFiles { f -> f.name.endsWith(".litertlm") }?.firstOrNull()
    val asrModel get() = File(asrDir, "model.int8.onnx")
    val asrTokens get() = File(asrDir, "tokens.txt")
    val espeakData get() = espeakDir
    fun voiceModel(voice: String) = File(voiceDir(voice), "$voice.onnx")
    fun voiceTokens(voice: String) = File(voiceDir(voice), "tokens.txt")

    private val readyMarker = ".complete"

    fun coreReady() = File(gemmaDir, readyMarker).exists() && File(asrDir, readyMarker).exists()

    /** The voice already downloaded for [language], if any. */
    fun installedVoice(language: Language): String? =
        language.piperVoices.firstOrNull { voice ->
            File(voiceDir(voice), readyMarker).exists() && File(espeakDir, readyMarker).exists()
        }

    /** Downloads Gemma and the speech recognizer. [onProgress] gets (bytes done, bytes total, label). */
    suspend fun downloadCore(onProgress: (Long, Long, String) -> Unit) = withContext(Dispatchers.IO) {
        val gemmaFiles = listRepo(GEMMA_REPO).filter { it.path.endsWith(".litertlm") }
        val gemma = pickGemmaFile(gemmaFiles)
            ?: throw IOException("No .litertlm file found in $GEMMA_REPO")
        val asrRepo = ASR_REPOS.firstNotNullOfOrNull { repo -> runCatching { repo to listRepo(repo) }.getOrNull() }
            ?: throw IOException("Speech recognizer repository not found on Hugging Face")
        val asrFiles = asrRepo.second.filter { it.path == "model.int8.onnx" || it.path == "tokens.txt" }

        val jobs = listOf(gemma to File(gemmaDir, gemma.path.substringAfterLast('/'))) +
            asrFiles.map { it to File(asrDir, it.path) }
        downloadAll(jobs, onProgress)
        File(gemmaDir, readyMarker).createNewFile()
        File(asrDir, readyMarker).createNewFile()
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
            val needEspeak = !File(espeakDir, readyMarker).exists()
            if (needEspeak) {
                files.filter { it.path.startsWith("espeak-ng-data/") }
                    .forEach { jobs += it to File(root, it.path) }
            }
            downloadAll(jobs, onProgress)
            if (needEspeak) File(espeakDir, readyMarker).createNewFile()
            File(voiceDir(voice), readyMarker).createNewFile()
            voice
        }

    private suspend fun downloadAll(jobs: List<Pair<HfFile, File>>, onProgress: (Long, Long, String) -> Unit) {
        val total = jobs.sumOf { it.first.size }
        var done = 0L
        for ((file, dest) in jobs) {
            val label = file.path.substringAfterLast('/')
            download(file, dest) { fileDone -> onProgress(done + fileDone, total, label) }
            done += file.size
            onProgress(done, total, label)
        }
    }

    private suspend fun download(file: HfFile, dest: File, onBytes: (Long) -> Unit) {
        if (dest.exists() && dest.length() == file.size) return
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        val url = "https://huggingface.co/${file.repo}/resolve/main/${file.path}"
        var attempt = 0
        while (true) {
            try {
                val have = if (part.exists()) part.length() else 0L
                if (have < file.size || file.size == 0L) {
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
                if (file.size > 0 && part.length() != file.size) {
                    throw IOException("Incomplete download of ${file.path}")
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
        const val GEMMA_REPO = "litert-community/gemma-4-E4B-it-litert-lm"
        val ASR_REPOS = listOf(
            "csukuangfj2/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12",
            "csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12",
        )
    }
}

package com.pedant.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records what the app was doing so a crash can be explained on the next launch.
 * Native crashes (in the model libraries) can't be caught, so before each risky step the app
 * writes down the step; if the process dies, the step is still recorded when it starts again.
 */
class Diagnostics(private val context: Context) {

    private val prefs = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
    private val javaCrashFile = File(context.filesDir, "last-crash.txt")

    /** Catches Kotlin/Java crashes and saves their stack trace before the app closes. */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                javaCrashFile.writeText("Thread: ${thread.name}\n$trace")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Free memory right now, for crash reports. */
    fun memorySummary(): String {
        val am = context.getSystemService(ActivityManager::class.java) ?: return ""
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return "free ${info.availMem / 1_000_000} MB of ${info.totalMem / 1_000_000} MB" +
            if (info.lowMemory) ", low memory" else ""
    }

    /** The step in progress, saved synchronously so it survives a native crash. */
    var step: String?
        get() = prefs.getString("step", null)
        set(value) {
            prefs.edit().putString("step", value).commit()
        }

    /** Remembers how loading Gemma on the GPU or CPU went, with the time it happened. */
    fun recordLoad(gpu: Boolean, result: String) {
        val time = SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date())
        prefs.edit().putString(if (gpu) "lastGpu" else "lastCpu", "$result ($time)").commit()
    }

    fun lastLoadResult(gpu: Boolean): String? = prefs.getString(if (gpu) "lastGpu" else "lastCpu", null)

    /**
     * A description of the last crash if the previous run ended in one, else null.
     * Reading it marks it as seen.
     */
    fun takeCrashReport(): String? {
        val unfinishedStep = step
        val javaTrace = javaCrashFile.takeIf { it.exists() }?.readText()
        // Android also closes background apps for memory; only count that if a step was unfinished.
        val exit = lastExitInfo()?.takeIf {
            unfinishedStep != null || it.reason in setOf(
                ApplicationExitInfo.REASON_CRASH,
                ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR,
            )
        }
        if (unfinishedStep == null && javaTrace == null && exit == null) return null

        val report = buildString {
            appendLine("App version: ${BuildConfigInfo.version(context)}")
            unfinishedStep?.let { appendLine("Was doing: $it") }
            exit?.let {
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(it.timestamp))
                appendLine("Closed at: $time")
                appendLine("Reason: ${reasonName(it.reason)}")
                it.description?.let { d -> appendLine("Details: $d") }
                appendLine("Memory used: ${it.pss / 1024} MB (resident ${it.rss / 1024} MB)")
                appendLine("Status code: ${it.status}")
            }
            javaTrace?.let { appendLine(); append(it.take(4000)) }
        }

        step = null
        javaCrashFile.delete()
        exit?.let { prefs.edit().putLong("lastExitSeen", it.timestamp).commit() }
        return report
    }

    private fun lastExitInfo(): ApplicationExitInfo? {
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
        return info.takeIf { it.timestamp > prefs.getLong("lastExitSeen", 0) }
    }

    private fun reasonName(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "app error (Java/Kotlin crash)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash (inside a model library)"
        ApplicationExitInfo.REASON_ANR -> "app stopped responding"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "out of memory (closed by Android)"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "used too many resources"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_EXIT_SELF -> "the app (or a library inside it) exited by itself"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by the user (swiped away or force stopped)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by the user"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a system service it depended on stopped"
        ApplicationExitInfo.REASON_FREEZER -> "frozen and closed by the system"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "app was updated"
        ApplicationExitInfo.REASON_OTHER -> "closed by the system (other)"
        else -> "code $reason"
    }
}

private object BuildConfigInfo {
    fun version(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"
}

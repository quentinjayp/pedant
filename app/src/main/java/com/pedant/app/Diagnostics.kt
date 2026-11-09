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

    private val eventLog = File(context.filesDir, "events.log")

    /** Adds a timestamped line to a short rolling log that is included in crash reports. */
    fun log(event: String) {
        runCatching {
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            eventLog.appendText("$time $event\n")
            // Trim now and then rather than rewriting the file on every event.
            if (eventLog.length() > 16_384) eventLog.writeText(eventLog.readLines().takeLast(40).joinToString("\n") + "\n")
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
            if (value == prefs.getString("step", null)) return
            prefs.edit().putString("step", value).commit()
            value?.let { log(it) }
        }

    /**
     * A description of the last crash if the previous run ended in one, else null.
     * Reading it marks it as seen.
     */
    fun takeCrashReport(): String? {
        val unfinishedStep = step
        val javaTrace = javaCrashFile.takeIf { it.exists() }?.readText()
        val exits = recentExits()
        // Android also closes background apps for memory; only report if something went wrong.
        val crashed = exits.any {
            it.reason in setOf(
                ApplicationExitInfo.REASON_CRASH,
                ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR,
            )
        }
        if (unfinishedStep == null && javaTrace == null && !crashed) {
            markSeen(exits)
            return null
        }

        val report = buildString {
            appendLine("App version: ${appVersion()}")
            unfinishedStep?.let { appendLine("Was doing: $it") }
            exits.forEachIndexed { i, exit ->
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(exit.timestamp))
                appendLine()
                appendLine(if (i == 0) "Last closed at: $time" else "Before that, closed at: $time")
                appendLine("Reason: ${reasonName(exit.reason)}")
                exit.description?.let { d -> appendLine("Details: $d") }
                appendLine("Memory used: ${exit.pss / 1024} MB (resident ${exit.rss / 1024} MB)")
            }
            if (eventLog.exists()) {
                appendLine()
                appendLine("Recent events:")
                eventLog.readLines().takeLast(15).forEach { appendLine(it) }
            }
            javaTrace?.let { appendLine(); append(it.take(4000)) }
        }

        step = null
        javaCrashFile.delete()
        markSeen(exits)
        return report
    }

    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

    private fun markSeen(exits: List<ApplicationExitInfo>) {
        exits.firstOrNull()?.let { prefs.edit().putLong("lastExitSeen", it.timestamp).commit() }
    }

    /** How the app closed recently (newest first), since the last report. */
    private fun recentExits(): List<ApplicationExitInfo> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val seen = prefs.getLong("lastExitSeen", 0)
        return am.getHistoricalProcessExitReasons(context.packageName, 0, 3).filter { it.timestamp > seen }
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

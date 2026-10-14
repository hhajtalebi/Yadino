package ir.yadino.app.core

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import ir.yadino.app.BuildConfig
import java.io.File
import java.time.Instant
import kotlin.system.exitProcess

/**
 * Local, privacy-preserving error log.
 *
 * Only exception class names and stack frames (class, method, line) are stored. Exception
 * messages are deliberately dropped because they may contain user content (for example
 * note text inside a database error). Nothing is sent anywhere automatically; the user can
 * review and share the log from Settings › Advanced.
 *
 * A crash while the app is on screen opens [CrashReportActivity] instead of the system
 * "app has stopped" dialog, so the report can be copied or shared right away.
 */
object ErrorReporter {
    private const val TAG = "Yadino"
    private const val DIR = "error-log"
    private const val MAX_FILES = 30

    @Volatile
    var enabled: Boolean = true

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val trace = try {
                sanitize(throwable, "crash")
            } catch (_: Throwable) {
                null
            }
            try {
                if (trace != null) write(appContext, trace, "crash")
            } catch (_: Throwable) {
            }
            if (trace != null && AppVisibility.userFacing() && CrashReportActivity.start(appContext, trace)) {
                // The report screen runs in its own process; this one is in an unknown state.
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Records a handled error. */
    fun log(context: Context, throwable: Throwable, where: String) {
        Log.w(TAG, "error in $where: ${throwable.javaClass.name}")
        if (!enabled) return
        try {
            write(context.applicationContext, sanitize(throwable, where), where)
        } catch (_: Throwable) {
        }
    }

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    @Synchronized
    private fun write(context: Context, report: String, where: String) {
        if (!enabled) return
        val d = dir(context)
        val file = File(d, "${System.currentTimeMillis()}-${where.filter { it.isLetterOrDigit() || it == '-' }}.txt")
        file.writeText(report)
        d.listFiles()?.sortedByDescending { it.name }?.drop(MAX_FILES)?.forEach { it.delete() }
    }

    /** Stack trace without messages. */
    fun sanitize(throwable: Throwable, where: String, now: Instant = Instant.now()): String = buildString {
        append("time: ").append(now).append('\n')
        append("where: ").append(where).append('\n')
        var t: Throwable? = throwable
        var depth = 0
        while (t != null && depth < MAX_CAUSES) {
            append(if (depth == 0) "exception: " else "caused by: ").append(t.javaClass.name).append('\n')
            t.stackTrace.take(MAX_FRAMES).forEach { f ->
                append("  at ").append(f.className).append('.').append(f.methodName)
                    .append(':').append(f.lineNumber).append('\n')
            }
            t = t.cause.takeIf { it !== t }
            depth++
        }
    }

    const val MAX_CAUSES = 6
    const val MAX_FRAMES = 25

    fun entries(context: Context): List<File> = dir(context).listFiles()?.sortedByDescending { it.name } ?: emptyList()

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /** App version, Android version and device model: the first lines of every shared report. */
    fun header(): String = buildString {
        append("Yadino ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        append("Android ").append(Build.VERSION.RELEASE).append(" / SDK ").append(Build.VERSION.SDK_INT).append('\n')
        append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n\n")
    }

    /** Text suitable for sharing with the developer (device info + sanitized traces). */
    fun exportText(context: Context): String = buildString {
        append(header())
        entries(context).take(10).forEach { append(it.readText()).append("\n----\n") }
    }
}

/**
 * Whether the user is looking at the app, so a crash may open [CrashReportActivity]:
 * an activity is started, one was created moments ago (a crash in `onCreate` happens before
 * `onStart`), or the application itself is still starting. Crashes in the background (a
 * widget update, a reminder) keep the normal behaviour and are only logged.
 */
object AppVisibility : Application.ActivityLifecycleCallbacks {
    /** How long after an activity was created a crash still counts as a start-up crash. */
    const val START_GRACE_MILLIS = 5_000L

    @Volatile
    private var started = 0

    @Volatile
    private var lastCreated = Long.MIN_VALUE

    @Volatile
    var applicationReady = false

    fun userFacing(): Boolean = userFacing(started, lastCreated, SystemClock.uptimeMillis(), applicationReady)

    /** The decision itself, separate for tests. */
    fun userFacing(startedActivities: Int, lastCreatedAt: Long, now: Long, appReady: Boolean): Boolean =
        !appReady || startedActivities > 0 || (lastCreatedAt != Long.MIN_VALUE && now - lastCreatedAt in 0..START_GRACE_MILLIS)

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        lastCreated = SystemClock.uptimeMillis()
    }

    override fun onActivityStarted(activity: Activity) {
        started++
    }

    override fun onActivityStopped(activity: Activity) {
        if (started > 0) started--
    }

    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

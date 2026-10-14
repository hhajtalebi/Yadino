package ir.yadino.app.core

import android.content.Context
import android.os.Build
import android.util.Log
import ir.yadino.app.BuildConfig
import java.io.File
import java.time.Instant

/**
 * Local, privacy-preserving error log.
 *
 * Only exception class names and stack frames (class, method, line) are stored. Exception
 * messages are deliberately dropped because they may contain user content (for example
 * note text inside a database error). Nothing is sent anywhere automatically; the user can
 * review and share the log from Settings › Advanced.
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
            try {
                write(appContext, throwable, "crash")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Records a handled error. */
    fun log(context: Context, throwable: Throwable, where: String) {
        Log.w(TAG, "error in $where: ${throwable.javaClass.name}")
        if (!enabled) return
        try {
            write(context.applicationContext, throwable, where)
        } catch (_: Throwable) {
        }
    }

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    @Synchronized
    private fun write(context: Context, throwable: Throwable, where: String) {
        if (!enabled) return
        val d = dir(context)
        val file = File(d, "${System.currentTimeMillis()}-${where.filter { it.isLetterOrDigit() || it == '-' }}.txt")
        file.writeText(sanitize(throwable, where))
        d.listFiles()?.sortedByDescending { it.name }?.drop(MAX_FILES)?.forEach { it.delete() }
    }

    /** Stack trace without messages. */
    fun sanitize(throwable: Throwable, where: String): String = buildString {
        append("time: ").append(Instant.now()).append('\n')
        append("where: ").append(where).append('\n')
        var t: Throwable? = throwable
        var depth = 0
        while (t != null && depth < 6) {
            append(if (depth == 0) "exception: " else "caused by: ").append(t.javaClass.name).append('\n')
            t.stackTrace.take(25).forEach { f ->
                append("  at ").append(f.className).append('.').append(f.methodName)
                    .append(':').append(f.lineNumber).append('\n')
            }
            t = t.cause.takeIf { it !== t }
            depth++
        }
    }

    fun entries(context: Context): List<File> = dir(context).listFiles()?.sortedByDescending { it.name } ?: emptyList()

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /** Text suitable for sharing with the developer (device info + sanitized traces). */
    fun exportText(context: Context): String = buildString {
        append("Yadino ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        append("Android ").append(Build.VERSION.RELEASE).append(" / SDK ").append(Build.VERSION.SDK_INT).append('\n')
        append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n\n")
        entries(context).take(10).forEach { append(it.readText()).append("\n----\n") }
    }
}

package ir.yadino.app.core

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import ir.yadino.app.LocaleHelper
import ir.yadino.app.MainActivity
import ir.yadino.app.R

/**
 * Shown after an uncaught exception while the app was on screen, instead of the system
 * "app has stopped" dialog. It explains what happened and offers the report for copying or
 * sharing (app and Android version, device model and the message-free stack trace of
 * [ErrorReporter.sanitize] — no user content), and a button to open the app again.
 *
 * It runs in its own process (`:crash` in the manifest), where [ir.yadino.app.YadinoApplication]
 * skips all initialisation, and it uses plain Android views without the database or Compose,
 * so it also works when the crash happened while the app was starting.
 */
class CrashReportActivity : Activity() {

    companion object {
        const val EXTRA_TRACE = "trace"

        /** Opens the report screen in a new task; false if the system refused. */
        fun start(context: Context, trace: String): Boolean = try {
            context.startActivity(
                Intent(context, CrashReportActivity::class.java)
                    .putExtra(EXTRA_TRACE, trace)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash)
        val trace = intent?.getStringExtra(EXTRA_TRACE)?.takeIf { it.isNotBlank() } ?: getString(R.string.crash_no_details)
        val report = ErrorReporter.header() + trace
        findViewById<TextView>(R.id.crash_report).text = report
        findViewById<Button>(R.id.crash_copy).setOnClickListener { copy(report) }
        findViewById<Button>(R.id.crash_share).setOnClickListener { share(report) }
        findViewById<Button>(R.id.crash_restart).setOnClickListener { restart() }
        findViewById<Button>(R.id.crash_close).setOnClickListener { finishAndRemoveTask() }
    }

    private fun copy(report: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.crash_report_label), report))
        // Android 13+ confirms clipboard writes itself
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun share(report: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.crash_share_subject))
            .putExtra(Intent.EXTRA_TEXT, report)
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share)))
        } catch (_: ActivityNotFoundException) {
            copy(report)
        }
    }

    private fun restart() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}

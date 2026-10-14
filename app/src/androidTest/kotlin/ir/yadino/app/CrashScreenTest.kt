package ir.yadino.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ir.yadino.app.core.CrashReportActivity
import ir.yadino.app.core.ErrorReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The crash report screen: declared in its own process (YadinoApplication skips all
 * initialisation there), its layout inflates with the app theme in Persian, and the report
 * header names the app version and the device. (The activity itself runs in another process,
 * so it cannot be driven by ActivityScenario from this test process.)
 */
@RunWith(AndroidJUnit4::class)
class CrashScreenTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun crashScreenRunsInItsOwnProcess() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, CrashReportActivity::class.java), PackageManager.GET_META_DATA)
        assertEquals(context.packageName + ":crash", info.processName)
        assertTrue(!info.exported)
    }

    @Test
    fun layoutInflatesInPersianWithAllControls() {
        val themed = ContextThemeWrapper(LocaleHelper.wrap(context), R.style.Theme_Yadino)
        val root = LayoutInflater.from(themed).inflate(R.layout.activity_crash, null)
        assertEquals(View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
        for ((id, text) in listOf(
            R.id.crash_copy to R.string.crash_copy, R.id.crash_share to R.string.crash_share,
            R.id.crash_restart to R.string.crash_restart, R.id.crash_close to R.string.crash_close,
        )) {
            assertEquals(themed.getString(text), root.findViewById<Button>(id).text.toString())
        }
        assertEquals("یادینو با خطا متوقف شد", root.findViewById<TextView>(R.id.crash_title).text.toString())
        assertNotNull(root.findViewById<TextView>(R.id.crash_report))
    }

    @Test
    fun reportHeaderNamesVersionAndDeviceAndHasNoMessages() {
        val report = ErrorReporter.header() + ErrorReporter.sanitize(IllegalStateException("متن خصوصی یادداشت"), "crash")
        assertTrue(report.startsWith("Yadino ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n"))
        assertTrue(report.contains("SDK ${Build.VERSION.SDK_INT}"))
        assertTrue(report.contains(Build.MODEL))
        assertTrue(report.contains("exception: java.lang.IllegalStateException"))
        assertTrue(!report.contains("متن خصوصی"))
    }
}

package ir.yadino.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ir.yadino.domain.model.AppSettings
import ir.yadino.domain.model.DashboardSection
import ir.yadino.domain.model.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Settings changes persist, and "reset settings" keeps user data and the dashboard layout. */
@RunWith(AndroidJUnit4::class)
class SettingsPersistenceTest {
    private val app = ApplicationProvider.getApplicationContext<Context>() as YadinoApplication

    @Test
    fun updateAndReset() = runBlocking {
        val repo = app.container.settings
        repo.update { it.copy(themeMode = ThemeMode.DARK, fontScale = 1.2f) }
        assertEquals(ThemeMode.DARK, repo.current().themeMode)
        repo.updateDashboard { cfg -> cfg.copy(sections = cfg.sections.map { if (it.section == DashboardSection.HABITS) it.copy(visible = false) else it }) }
        repo.resetToDefaults()
        assertEquals(AppSettings(), repo.current())
        assertFalse(repo.currentDashboard().sections.first { it.section == DashboardSection.HABITS }.visible)
        repo.resetDashboard()
    }
}

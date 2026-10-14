package ir.yadino.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ir.yadino.domain.model.AppSettings
import ir.yadino.domain.model.DashboardConfig
import ir.yadino.domain.settings.DashboardCodec
import ir.yadino.domain.settings.SettingsCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)
private val Context.appStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_state",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)
private val Context.widgetDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "widgets",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

private fun DataStore<Preferences>.safeData(): Flow<Preferences> =
    data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

/**
 * User settings backed by Preferences DataStore. Values are stored with the keys of
 * [SettingsCodec], which is also used for export/import and backups.
 */
class SettingsRepository(context: Context, scope: CoroutineScope) {
    private val store = context.applicationContext.settingsDataStore
    private val dashboardKey = stringPreferencesKey("dashboard.config")

    val settings: Flow<AppSettings> = store.safeData().map { prefs -> decode(prefs) }.distinctUntilChanged()

    /** Hot settings state; null until the settings file has been read (a few ms after start). */
    val state: StateFlow<AppSettings?> = settings.map<AppSettings, AppSettings?> { it }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Latest settings, or defaults while they are still loading. */
    fun currentOrDefault(): AppSettings = state.value ?: AppSettings()

    /** Suspends until the settings have been loaded. */
    suspend fun awaitLoaded(): AppSettings = state.filterNotNull().first()

    val dashboard: Flow<DashboardConfig> =
        store.safeData().map { DashboardCodec.decode(it[dashboardKey]) }.distinctUntilChanged()

    private fun decode(prefs: Preferences): AppSettings {
        val map = HashMap<String, String>()
        for ((k, v) in prefs.asMap()) if (v is String) map[k.name] = v
        return SettingsCodec.decode(map)
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val updated = transform(decode(prefs))
            for ((k, v) in SettingsCodec.encode(updated)) prefs[stringPreferencesKey(k)] = v
        }
    }

    suspend fun replaceAll(settings: AppSettings) = update { settings }

    /** Applies a key/value map (from a backup or import). Unknown keys are ignored. */
    suspend fun applyMap(values: Map<String, String>) = update { SettingsCodec.decode(values, it) }

    suspend fun exportMap(): Map<String, String> = SettingsCodec.encode(current())

    /** Resets every setting to its default value without touching user data. */
    suspend fun resetToDefaults() {
        store.edit { prefs ->
            val dashboard = prefs[dashboardKey]
            prefs.clear()
            if (dashboard != null) prefs[dashboardKey] = dashboard
        }
    }

    suspend fun updateDashboard(transform: (DashboardConfig) -> DashboardConfig) {
        store.edit { prefs ->
            val cur = DashboardCodec.decode(prefs[dashboardKey])
            prefs[dashboardKey] = DashboardCodec.encode(transform(cur).normalized())
        }
    }

    suspend fun currentDashboard(): DashboardConfig = dashboard.first()

    suspend fun resetDashboard() {
        store.edit { it.remove(dashboardKey) }
    }
}

/** Status of the last backup operation shown in the data & backup screen. */
data class BackupStatus(
    val lastLocalBackupAt: Long?,
    val lastDriveBackupAt: Long?,
    val lastResult: String?,
    val lastResultAt: Long?,
    val lastRestoredChecksum: String?,
    val driveAccount: String?,
    val onboardingDone: Boolean,
    val lastBackupReminderAt: Long?,
)

/** Non-preference application state (backup bookkeeping, onboarding, Drive account). */
class AppStateStore(context: Context) {
    private val store = context.applicationContext.appStateDataStore
    private val lastLocal = longPreferencesKey("backup.lastLocal")
    private val lastDrive = longPreferencesKey("backup.lastDrive")
    private val lastResult = stringPreferencesKey("backup.lastResult")
    private val lastResultAt = longPreferencesKey("backup.lastResultAt")
    private val lastRestored = stringPreferencesKey("backup.lastRestoredChecksum")
    private val driveAccount = stringPreferencesKey("drive.account")
    private val onboarding = stringPreferencesKey("onboarding.done")
    private val lastReminder = longPreferencesKey("backup.lastReminder")

    val status: Flow<BackupStatus> = store.safeData().map { p ->
        BackupStatus(
            lastLocalBackupAt = p[lastLocal], lastDriveBackupAt = p[lastDrive], lastResult = p[lastResult],
            lastResultAt = p[lastResultAt], lastRestoredChecksum = p[lastRestored], driveAccount = p[driveAccount],
            onboardingDone = p[onboarding] == "1", lastBackupReminderAt = p[lastReminder],
        )
    }.distinctUntilChanged()

    suspend fun current(): BackupStatus = status.first()

    suspend fun recordLocalBackup(at: Long) = store.edit { it[lastLocal] = at }
    suspend fun recordDriveBackup(at: Long) = store.edit { it[lastDrive] = at }
    suspend fun recordResult(message: String, at: Long) = store.edit { it[lastResult] = message; it[lastResultAt] = at }
    suspend fun recordRestore(checksum: String) = store.edit { it[lastRestored] = checksum }
    suspend fun setDriveAccount(account: String?) = store.edit { if (account == null) it.remove(driveAccount) else it[driveAccount] = account }
    suspend fun setOnboardingDone() = store.edit { it[onboarding] = "1" }
    suspend fun recordBackupReminder(at: Long) = store.edit { it[lastReminder] = at }
}

/** Per-instance widget configuration. */
data class WidgetConfig(
    /** SYSTEM, LIGHT or DARK */
    val theme: String = "SYSTEM",
    /** Background opacity 0..100 */
    val opacity: Int = 92,
    /** Text size multiplier 0.8..1.4 */
    val textScale: Float = 1.0f,
    /** Accent: GREEN, GOLD, BLUE, ROSE */
    val accent: String = "GREEN",
    /** Content selector; meaning depends on widget type (task filter, note id, goal id, ...). */
    val content: String = "",
    /** Month offset for the calendar widget navigation. */
    val monthOffset: Int = 0,
    /** Show Gregorian date (date/clock and calendar widgets). */
    val showGregorian: Boolean = true,
)

class WidgetConfigStore(context: Context) {
    private val store = context.applicationContext.widgetDataStore

    private fun key(id: Int, field: String) = stringPreferencesKey("w.$id.$field")

    private fun decode(p: Preferences, id: Int) = WidgetConfig(
        theme = p[key(id, "theme")] ?: "SYSTEM",
        opacity = p[key(id, "opacity")]?.toIntOrNull()?.coerceIn(0, 100) ?: 92,
        textScale = p[key(id, "text")]?.toFloatOrNull()?.coerceIn(0.8f, 1.4f) ?: 1.0f,
        accent = p[key(id, "accent")] ?: "GREEN",
        content = p[key(id, "content")] ?: "",
        monthOffset = p[key(id, "month")]?.toIntOrNull() ?: 0,
        showGregorian = p[key(id, "greg")] != "0",
    )

    suspend fun get(id: Int): WidgetConfig = decode(store.safeData().first(), id)

    fun observe(id: Int): Flow<WidgetConfig> = store.safeData().map { decode(it, id) }.distinctUntilChanged()

    suspend fun isConfigured(id: Int): Boolean = store.safeData().first()[key(id, "theme")] != null

    suspend fun save(id: Int, c: WidgetConfig) {
        store.edit { p ->
            p[key(id, "theme")] = c.theme
            p[key(id, "opacity")] = c.opacity.toString()
            p[key(id, "text")] = c.textScale.toString()
            p[key(id, "accent")] = c.accent
            p[key(id, "content")] = c.content
            p[key(id, "month")] = c.monthOffset.toString()
            p[key(id, "greg")] = if (c.showGregorian) "1" else "0"
        }
    }

    suspend fun setMonthOffset(id: Int, offset: Int) = store.edit { it[key(id, "month")] = offset.toString() }

    suspend fun delete(ids: IntArray) {
        store.edit { p ->
            val prefixes = ids.map { "w.$it." }
            p.asMap().keys.filter { k -> prefixes.any { k.name.startsWith(it) } }.forEach {
                @Suppress("UNCHECKED_CAST")
                p.remove(it as Preferences.Key<Any>)
            }
        }
    }
}

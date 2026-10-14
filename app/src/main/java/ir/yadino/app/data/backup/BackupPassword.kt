package ir.yadino.app.data.backup

import ir.yadino.app.data.settings.AppStateStore

/**
 * The optional backup password.
 *
 * The password is kept in [SecureTokenStore] (AES-GCM with a key in the Android Keystore,
 * never part of Android Auto Backup) so that automatic backups can be encrypted without
 * asking. A separate non-secret flag in [AppStateStore] remembers that protection is on:
 * if the password ever becomes unreadable (for example after moving to a new phone),
 * backups fail with a clear message instead of silently being written unencrypted.
 */
class BackupPasswordStore(private val secure: TokenStore, private val appState: AppStateStore) {

    enum class State {
        /** Backups are written as plain JSON. */
        OFF,

        /** Backups are encrypted with the saved password. */
        ON,

        /** Protection is on, but the saved password cannot be read on this device. */
        UNAVAILABLE,
    }

    private companion object {
        const val KEY = "backup_password"
    }

    suspend fun state(): State {
        val saved = secure.get(KEY)
        return when {
            saved != null -> State.ON
            appState.current().encryptionEnabled -> State.UNAVAILABLE
            else -> State.OFF
        }
    }

    /** The saved password, or null. Callers should clear the returned array after use. */
    fun password(): CharArray? = secure.get(KEY)?.toCharArray()

    suspend fun set(password: CharArray) {
        secure.put(KEY, String(password))
        appState.setEncryptionEnabled(true)
    }

    suspend fun clear() {
        secure.put(KEY, null)
        appState.setEncryptionEnabled(false)
    }
}

/** Thrown when protection is on but the password is not available on this device. */
class BackupPasswordUnavailableException : IllegalStateException("backup password unavailable")

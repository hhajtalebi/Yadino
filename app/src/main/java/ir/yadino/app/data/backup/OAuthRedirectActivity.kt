package ir.yadino.app.data.backup

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import ir.yadino.app.LocaleHelper
import ir.yadino.app.MainActivity
import ir.yadino.app.R
import ir.yadino.app.YadinoApplication
import ir.yadino.app.notifications.NotificationHelper
import ir.yadino.app.ui.navigation.Route
import kotlinx.coroutines.launch

/**
 * Receives the OAuth redirect (`<reverse-client-id>:/oauth2redirect?code=…&state=…`),
 * exchanges the code for tokens and returns to Settings › Data.
 */
class OAuthRedirectActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        val app = application as YadinoApplication
        if (data == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            val result = app.container.drive.completeAuthorization(data)
            val msg = when (result) {
                is DriveResult.Success -> {
                    app.container.appState.setDriveAccount(app.container.drive.accountEmail() ?: "connected")
                    R.string.drive_connected_toast
                }
                DriveResult.NetworkError -> R.string.drive_network_error
                else -> R.string.drive_auth_failed
            }
            Toast.makeText(this@OAuthRedirectActivity, msg, Toast.LENGTH_LONG).show()
            startActivity(Intent(this@OAuthRedirectActivity, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(NotificationHelper.EXTRA_ROUTE, Route.SettingsData.encode())
            })
            finish()
        }
    }
}

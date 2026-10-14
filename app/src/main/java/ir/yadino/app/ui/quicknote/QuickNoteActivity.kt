package ir.yadino.app.ui.quicknote

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.yadino.app.LocaleHelper
import ir.yadino.app.R
import ir.yadino.app.YadinoApplication
import ir.yadino.app.ui.theme.YadinoTheme
import ir.yadino.domain.model.AppSettings
import kotlinx.coroutines.launch

/** Minimal dialog to capture a note from the widget or a launcher shortcut. */
class QuickNoteActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as YadinoApplication
        val c = app.container
        setContent {
            val loaded by c.settings.state.collectAsStateWithLifecycle()
            YadinoTheme(loaded ?: AppSettings()) {
                var title by rememberSaveable { mutableStateOf("") }
                var body by rememberSaveable { mutableStateOf("") }
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.quick_note), style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(body, { body = it }, label = { Text(stringResource(R.string.note_body)) }, minLines = 4, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { finish() }) { Text(stringResource(R.string.cancel)) }
                            Button(enabled = title.isNotBlank() || body.isNotBlank(), onClick = {
                                app.appScope.launch {
                                    c.notes.create(title, body, c.settings.current().defaultNoteColor)
                                }
                                Toast.makeText(this@QuickNoteActivity, R.string.msg_note_saved, Toast.LENGTH_SHORT).show()
                                finish()
                            }) { Text(stringResource(R.string.save)) }
                        }
                    }
                }
            }
        }
    }
}

package ir.yadino.app.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ir.yadino.app.R
import ir.yadino.app.core.AppClock
import ir.yadino.app.core.ErrorReporter
import ir.yadino.app.data.repository.ValidationException
import ir.yadino.app.di.AppContainer
import ir.yadino.app.ui.LocalSnackbar
import ir.yadino.domain.validation.ValidationError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** One-off messages shown as snackbars. */
sealed interface UiMessage {
    data class Text(val res: Int, val args: List<String> = emptyList()) : UiMessage
    data class WithUndo(val res: Int, val undo: () -> Unit, val args: List<String> = emptyList()) : UiMessage
    data class Validation(val errors: List<ValidationError>) : UiMessage
    data object Failure : UiMessage
}

abstract class YadinoViewModel(protected val c: AppContainer) : ViewModel() {
    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages

    protected fun message(m: UiMessage) {
        _messages.tryEmit(m)
    }

    /** Runs [block], turning validation and storage errors into user messages. */
    protected fun launchSafe(onError: (Throwable) -> Unit = {}, block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ValidationException) {
            onError(e)
            _messages.emit(UiMessage.Validation(e.errors))
        } catch (e: Exception) {
            onError(e)
            ErrorReporter.log(c.app, e, javaClass.simpleName)
            _messages.emit(UiMessage.Failure)
        }
    }
}

/** Emits the current local date and updates it after midnight while observed. */
fun todayFlow(clock: AppClock): Flow<LocalDate> = flow {
    while (true) {
        emit(clock.today())
        delay(30_000)
    }
}.distinctUntilChanged()

@Composable
fun validationText(e: ValidationError): String = stringResource(
    when (e) {
        ValidationError.TITLE_REQUIRED -> R.string.v_title_required
        ValidationError.TITLE_TOO_LONG -> R.string.v_title_too_long
        ValidationError.DESCRIPTION_TOO_LONG -> R.string.v_description_too_long
        ValidationError.CONTENT_TOO_LONG -> R.string.v_content_too_long
        ValidationError.END_BEFORE_START -> R.string.v_end_before_start
        ValidationError.TIME_WITHOUT_DATE -> R.string.v_time_without_date
        ValidationError.DUE_BEFORE_DATE -> R.string.v_due_before_date
        ValidationError.REMINDER_WITHOUT_DATE -> R.string.v_reminder_without_date
        ValidationError.REMINDER_OUT_OF_RANGE -> R.string.v_reminder_range
        ValidationError.RECURRENCE_WITHOUT_DATE -> R.string.v_recurrence_without_date
        ValidationError.RECURRENCE_UNTIL_BEFORE_START -> R.string.v_until_before_start
        ValidationError.TOO_MANY_TAGS -> R.string.v_too_many_tags
        ValidationError.TAG_TOO_LONG -> R.string.v_tag_too_long
        ValidationError.DEADLINE_BEFORE_START -> R.string.v_deadline_before_start
        ValidationError.PROGRESS_OUT_OF_RANGE -> R.string.v_progress_range
        ValidationError.TARGET_OUT_OF_RANGE -> R.string.v_target_range
        ValidationError.NAME_REQUIRED -> R.string.v_name_required
        ValidationError.NAME_TOO_LONG -> R.string.v_name_too_long
        ValidationError.NAME_DUPLICATE -> R.string.v_name_duplicate
    },
)

/** Shows the ViewModel's messages as snackbars. */
@Composable
fun CollectMessages(messages: SharedFlow<UiMessage>) {
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current
    LaunchedEffect(messages) {
        messages.collect { m ->
            when (m) {
                is UiMessage.Text -> snackbar.showSnackbar(context.getString(m.res, *m.args.toTypedArray()))
                is UiMessage.WithUndo -> {
                    val r = snackbar.showSnackbar(
                        context.getString(m.res, *m.args.toTypedArray()),
                        actionLabel = context.getString(R.string.undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (r == SnackbarResult.ActionPerformed) m.undo()
                }
                is UiMessage.Validation -> snackbar.showSnackbar(
                    m.errors.joinToString("\n") { context.getString(validationRes(it)) },
                )
                UiMessage.Failure -> snackbar.showSnackbar(context.getString(R.string.err_generic))
            }
        }
    }
}

fun validationRes(e: ValidationError): Int = when (e) {
    ValidationError.TITLE_REQUIRED -> R.string.v_title_required
    ValidationError.TITLE_TOO_LONG -> R.string.v_title_too_long
    ValidationError.DESCRIPTION_TOO_LONG -> R.string.v_description_too_long
    ValidationError.CONTENT_TOO_LONG -> R.string.v_content_too_long
    ValidationError.END_BEFORE_START -> R.string.v_end_before_start
    ValidationError.TIME_WITHOUT_DATE -> R.string.v_time_without_date
    ValidationError.DUE_BEFORE_DATE -> R.string.v_due_before_date
    ValidationError.REMINDER_WITHOUT_DATE -> R.string.v_reminder_without_date
    ValidationError.REMINDER_OUT_OF_RANGE -> R.string.v_reminder_range
    ValidationError.RECURRENCE_WITHOUT_DATE -> R.string.v_recurrence_without_date
    ValidationError.RECURRENCE_UNTIL_BEFORE_START -> R.string.v_until_before_start
    ValidationError.TOO_MANY_TAGS -> R.string.v_too_many_tags
    ValidationError.TAG_TOO_LONG -> R.string.v_tag_too_long
    ValidationError.DEADLINE_BEFORE_START -> R.string.v_deadline_before_start
    ValidationError.PROGRESS_OUT_OF_RANGE -> R.string.v_progress_range
    ValidationError.TARGET_OUT_OF_RANGE -> R.string.v_target_range
    ValidationError.NAME_REQUIRED -> R.string.v_name_required
    ValidationError.NAME_TOO_LONG -> R.string.v_name_too_long
    ValidationError.NAME_DUPLICATE -> R.string.v_name_duplicate
}

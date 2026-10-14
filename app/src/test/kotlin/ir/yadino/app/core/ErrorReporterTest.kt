package ir.yadino.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Error reports never contain user content, stay small, and the crash screen only opens in front of the user. */
class ErrorReporterTest {
    private val now = Instant.parse("2026-10-14T08:00:00Z")

    @Test
    fun reportKeepsExceptionTypesAndFramesButNoMessages() {
        val cause = IllegalStateException("یادداشت: رمز کارت ۱۲۳۴")
        val error = RuntimeException("note text: buy milk", cause)
        val report = ErrorReporter.sanitize(error, "crash", now)
        assertTrue(report, report.startsWith("time: 2026-10-14T08:00:00Z\nwhere: crash\nexception: java.lang.RuntimeException\n"))
        assertTrue(report.contains("\ncaused by: java.lang.IllegalStateException\n"))
        assertTrue(report.contains("  at ir.yadino.app.core.ErrorReporterTest.reportKeepsExceptionTypesAndFramesButNoMessages:"))
        for (secret in listOf("buy milk", "note text", "یادداشت", "رمز", "۱۲۳۴")) {
            assertFalse("report contains \"$secret\"", report.contains(secret))
        }
    }

    @Test
    fun longCauseChainsAndDeepStacksAreCut() {
        var error: Throwable = Exception("leaf")
        repeat(9) { error = Exception("level $it", error) }
        val frames = Array(60) { StackTraceElement("ir.yadino.Deep", "f$it", "Deep.kt", it) }
        generateSequence(error) { it.cause }.forEach { it.stackTrace = frames }
        val lines = ErrorReporter.sanitize(error, "test", now).lines()
        assertEquals(ErrorReporter.MAX_CAUSES, lines.count { it.startsWith("exception: ") || it.startsWith("caused by: ") })
        assertEquals(ErrorReporter.MAX_CAUSES * ErrorReporter.MAX_FRAMES, lines.count { it.startsWith("  at ") })
    }

    @Test
    fun exceptionThatIsItsOwnCauseDoesNotLoop() {
        val selfCaused = object : RuntimeException() {
            override val cause: Throwable get() = this
        }
        val lines = ErrorReporter.sanitize(selfCaused, "test", now).lines()
        assertEquals(1, lines.count { it.startsWith("exception: ") || it.startsWith("caused by: ") })
    }

    @Test
    fun crashScreenOpensOnlyWhileTheUserIsLookingAtTheApp() {
        val t = 100_000L
        // an activity is on screen
        assertTrue(AppVisibility.userFacing(startedActivities = 1, lastCreatedAt = t - 60_000, now = t, appReady = true))
        // the crash happened in onCreate, before the activity was started
        assertTrue(AppVisibility.userFacing(startedActivities = 0, lastCreatedAt = t - 1_000, now = t, appReady = true))
        // the application itself was still starting
        assertTrue(AppVisibility.userFacing(startedActivities = 0, lastCreatedAt = Long.MIN_VALUE, now = t, appReady = false))
        // background work (widget update, reminder) long after the last screen was closed
        assertFalse(AppVisibility.userFacing(startedActivities = 0, lastCreatedAt = t - 60_000, now = t, appReady = true))
        assertFalse(AppVisibility.userFacing(startedActivities = 0, lastCreatedAt = Long.MIN_VALUE, now = t, appReady = true))
        assertFalse(AppVisibility.userFacing(startedActivities = 0, lastCreatedAt = t - AppVisibility.START_GRACE_MILLIS - 1, now = t, appReady = true))
    }
}

package org.intentional.shared

import kotlin.test.*

class ForegroundWindowTest {
    private val app = ForegroundWindow(WindowKind.APPLICATION, focused = true, active = true)
    @Test fun nonFocusedNotificationsCannotPauseAnIdleApp() {
        assertEquals(1, foregroundWindowIndex(listOf(ForegroundWindow(WindowKind.SYSTEM, false, false), app)))
    }
    @Test fun reminderOverlayCannotPauseAnIdleApp() {
        assertEquals(1, foregroundWindowIndex(listOf(ForegroundWindow(WindowKind.ACCESSIBILITY_OVERLAY, true, true), app.copy(focused = false))))
    }
    @Test fun keyboardPreservesTheUnderlyingApp() {
        assertEquals(1, foregroundWindowIndex(listOf(ForegroundWindow(WindowKind.KEYBOARD, true, true), app.copy(focused = false))))
    }
    @Test fun notificationShadePausesButDismissalResumesWithoutAnyAppEvent() {
        assertNull(foregroundWindowIndex(listOf(ForegroundWindow(WindowKind.SYSTEM, true, true), app.copy(focused = false))))
        assertEquals(0, foregroundWindowIndex(listOf(app)))
    }
    @Test fun splitScreenUsesFocusedAppNotOtherVisibleApps() {
        assertEquals(1, foregroundWindowIndex(listOf(app.copy(focused = false, active = false), app)))
    }
    @Test fun missingOrUnsupportedWindowsDoNotInventForegroundUsage() {
        assertNull(foregroundWindowIndex(emptyList()))
        assertNull(foregroundWindowIndex(listOf(ForegroundWindow(WindowKind.OTHER, true, true))))
    }
    @Test fun oneMinuteIdleYouTubeExpiresAfterSixtySecondsDespiteReminderWindows() {
        var time = 1000L
        val e = SessionEngine({ time }, { 1700000000000L + time })
        e.open(false, ProtectedApp.YOUTUBE); e.acceptGate(); e.intention("Relax")
        e.chooseDuration(); e.duration(1); e.start(); e.visit(ProtectedApp.YOUTUBE)
        repeat(120) {
            time += 500
            val windows = listOf(ForegroundWindow(WindowKind.ACCESSIBILITY_OVERLAY, false, false),
                ForegroundWindow(WindowKind.SYSTEM, false, false), app)
            val foreground = if (foregroundWindowIndex(windows) == 2) ProtectedApp.YOUTUBE else null
            e.foregroundChanged(foreground); e.visit(ProtectedApp.YOUTUBE); e.tick()
            if (it < 119) assertEquals(Stage.ACTIVE, e.current.stage)
        }
        assertEquals(Stage.EXPIRED, e.current.stage)
        assertEquals(60_000L, e.current.session!!.elapsedMs)
        assertEquals(0L, e.remainingMs())
    }
}

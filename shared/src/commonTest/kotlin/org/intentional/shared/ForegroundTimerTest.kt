package org.intentional.shared

import kotlin.test.*

class ForegroundTimerTest {
    private var time = 1000L
    private var wallOffset = 1700000000000L
    private fun engine(initial: Snapshot = Snapshot()) = SessionEngine({ time }, { wallOffset + time }, initial)
    private fun start(e: SessionEngine, app: ProtectedApp = ProtectedApp.INSTAGRAM) {
        e.open(false, app); e.acceptGate(); e.intention("Relax"); e.chooseDuration(); e.start()
        e.visit(app)
    }
    @Test fun noUsageAccruesUntilAppActuallyOpens() {
        val e = engine(); e.open(false); e.acceptGate(); e.intention("Relax"); e.chooseDuration(); e.start()
        time += 10_000; e.tick()
        assertEquals(Stage.PAUSED, e.current.stage); assertEquals(900_000, e.remainingMs())
        assertEquals(0, e.exportSnapshot().session!!.elapsedMs)
    }
    @Test fun leavingFreezesRemainingAndUsageAndDoesNotPromptReflection() {
        val e = engine(); start(e); time += 30_000; e.foregroundChanged(null)
        time += 120_000; e.tick()
        assertEquals(Stage.PAUSED, e.current.stage)
        assertEquals(870_000, e.remainingMs()); assertEquals(30_000, e.current.session!!.elapsedMs)
        assertFalse(e.visit(ProtectedApp.INSTAGRAM))
        time += 20_000; e.foregroundChanged(null)
        assertEquals(850_000, e.remainingMs()); assertEquals(50_000, e.current.session!!.elapsedMs)
    }
    @Test fun fiveMinutesAwayArchivesOnceWithUnknownOutcomes() {
        val e = engine(); start(e); time += 42_000; e.foregroundChanged(null)
        val pausedAt = time
        time += 299_999; assertEquals(TimerEvent.NONE, e.tick())
        time++; assertEquals(TimerEvent.INACTIVITY, e.tick())
        assertEquals(Stage.HOME, e.current.stage); assertNull(e.current.session)
        val saved = e.current.history.single()
        assertEquals(42_000, saved.elapsedMs); assertNull(saved.after); assertNull(saved.completed)
        assertEquals("inactivity_timeout", saved.finishReason)
        assertEquals(wallOffset + pausedAt + 300_000, saved.endedAt)
        e.tick(); assertEquals(1, e.current.history.size)
        assertEquals(0, e.remainingMs())
        assertTrue(e.visit(ProtectedApp.INSTAGRAM)); assertEquals(Stage.GATE, e.current.stage)
    }
    @Test fun returningJustBeforeDeadlineResumesButExactlyAtDeadlineStartsFresh() {
        for (delay in listOf(299_999L, 300_000L)) {
            val e = engine(); start(e); time += 1000; e.pause(); time += delay
            val intercepted = e.visit(ProtectedApp.INSTAGRAM)
            assertEquals(delay == 300_000L, intercepted)
            assertEquals(if (delay == 300_000L) Stage.GATE else Stage.ACTIVE, e.current.stage)
        }
    }
    @Test fun repeatedAwayEventsDoNotRestartTheFiveMinuteWindow() {
        val e = engine(); start(e); time += 1000; e.pause()
        repeat(6) { time += 50_000; e.foregroundChanged(null) }
        assertEquals(1, e.current.history.size); assertEquals(1000, e.current.history.single().elapsedMs)
    }
    @Test fun warningAndExpiryUseOnlyForegroundBudget() {
        val e = engine(); start(e); time += 800_000; e.pause()
        time += 200_000; e.tick(); e.visit(ProtectedApp.INSTAGRAM)
        assertEquals(100_000, e.remainingMs())
        time += 40_000; assertEquals(TimerEvent.WARNING, e.tick())
        e.pause(); time += 200_000; e.visit(ProtectedApp.INSTAGRAM)
        assertEquals(TimerEvent.NONE, e.tick())
        time += 60_000; assertEquals(TimerEvent.EXPIRED, e.tick())
        assertEquals(900_000, e.current.session!!.elapsedMs)
    }
    @Test fun extensionWaitsForForegroundAndAccumulatesUsageWithoutAwayTime() {
        val e = engine(); start(e); time += 60_000; e.pause()
        e.requestExtension(); e.extensionReason("Finish a reply"); e.extend()
        time += 10_000; assertEquals(Stage.PAUSED, e.current.stage)
        e.visit(ProtectedApp.INSTAGRAM); time += 20_000; e.pause()
        time += 300_000; e.tick(); assertEquals(80_000, e.current.history.single().elapsedMs)
        assertEquals(1, e.current.history.single().extensions.size)
    }
    @Test fun appsHaveIndependentPausedInteractionsAndTimeouts() {
        val e = engine(); start(e); time += 10_000
        e.visit(ProtectedApp.YOUTUBE); assertEquals(1, e.current.suspended.size)
        e.acceptGate(); e.intention("Learn"); e.chooseDuration(); e.start(); e.visit(ProtectedApp.YOUTUBE)
        time += 300_000; e.tick()
        assertEquals(Stage.ACTIVE, e.current.stage); assertEquals(ProtectedApp.YOUTUBE, e.current.session!!.app)
        assertEquals(10_000, e.current.history.single().elapsedMs)
        assertEquals(ProtectedApp.INSTAGRAM, e.current.history.single().app)
    }
    @Test fun pausedStateSurvivesRestartWithoutGrantingAnotherFiveMinutes() {
        val e = engine(); start(e); time += 20_000; e.pause(); time += 200_000
        val restored = engine(SessionEngine.decode(SessionEngine.encode(e.current)))
        restored.recoverAfterRestart(false)
        assertEquals(100_000, restored.inactivityRemainingMs())
        time += 100_000; restored.tick(); assertEquals(20_000, restored.current.history.single().elapsedMs)
    }
    @Test fun suspendedAppSurvivesPersistenceAndResumesFromAnUnstartedOtherGate() {
        val e = engine(); start(e); time += 30_000; e.visit(ProtectedApp.YOUTUBE)
        val restored = engine(SessionEngine.decode(SessionEngine.encode(e.current)))
        restored.recoverAfterRestart(false); time += 100_000
        assertFalse(restored.visit(ProtectedApp.INSTAGRAM))
        assertEquals(870_000, restored.remainingMs())
        assertTrue(restored.current.suspended.isEmpty())
    }
    @Test fun aNewAbsenceGetsFiveMinutesAfterAValidReturn() {
        val e = engine(); start(e); time += 10_000; e.pause(); time += 200_000
        e.visit(ProtectedApp.INSTAGRAM); time += 10_000; e.pause()
        time += 200_000; e.tick(); assertEquals(Stage.PAUSED, e.current.stage)
        time += 100_000; e.tick(); assertEquals(20_000, e.current.history.single().elapsedMs)
    }
    @Test fun processGapDoesNotBecomeInventedUsage() {
        val e = engine(); start(e); time += 5000; e.tick()
        val checkpoint = e.current; time += 60_000
        val restored = engine(checkpoint); restored.recoverAfterRestart(false)
        assertEquals(Stage.PAUSED, restored.current.stage)
        assertEquals(5000, restored.current.session!!.elapsedMs)
        assertEquals(895_000, restored.remainingMs())
    }
    @Test fun rebootPreservesConfirmedUsageAndUsesWallTimeForInactivity() {
        val e = engine(); start(e); time += 30_000; e.pause()
        val checkpoint = e.current
        wallOffset += time + 300_000; time = 0
        val restored = engine(checkpoint); restored.recoverAfterRestart(true)
        assertEquals(Stage.HOME, restored.current.stage)
        assertEquals(30_000, restored.current.history.single().elapsedMs)
    }
    @Test fun csvHasLiteralNAAndTimeoutStatusAndIncludesSuspendedSessions() {
        val e = engine(); start(e); time += 15_000; e.visit(ProtectedApp.YOUTUBE)
        assertTrue(StudyCsv.encode(e.exportSnapshot(), wallOffset + time).contains("\"paused\""))
        time += 300_000; e.tick()
        val csv = StudyCsv.encode(e.exportSnapshot(), wallOffset + time)
        assertTrue(csv.contains("\"inactivity_timeout\"")); assertTrue(csv.contains("\"N/A\",\"N/A\""))
        assertTrue(csv.contains("\"15.0\""))
    }
    @Test fun backCannotChangeCompletionQuestions() {
        val e = engine(); start(e); time += 900_000; e.tick()
        e.back(); assertEquals(Stage.EXPIRED, e.current.stage)
        e.reflect(); e.back(); assertEquals(Stage.REFLECT, e.current.stage)
    }
}

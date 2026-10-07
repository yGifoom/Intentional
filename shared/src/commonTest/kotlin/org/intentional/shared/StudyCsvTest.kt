package org.intentional.shared

import kotlin.test.*

class StudyCsvTest {
    private var time = 1000L
    private fun engine() = SessionEngine({ time }, { 1700000000000 + time }, Snapshot(participantId = "participant-test", trackingStartedAt = 1700000000000))
    private fun start(e: SessionEngine) {
        e.open(false, ProtectedApp.YOUTUBE); e.acceptGate(); e.intention("Relax, learn \"Kotlin\"\n🌿")
        e.before(2); e.chooseDuration(); e.duration(10); e.start()
        e.visit(ProtectedApp.YOUTUBE)
    }
    @Test fun cancelledOpeningAndDraftAreRetained() {
        val e = engine(); e.open(false); e.acceptGate(); e.intention("Just bored"); e.before(1); e.home()
        assertEquals(1, e.current.openings.size)
        assertEquals("Just bored", e.current.openings.single().intention)
        assertEquals(1, e.current.openings.single().before)
        assertTrue(e.current.history.isEmpty())
        assertEquals("participant-test", e.current.participantId)
    }
    @Test fun managedContinuationIsNotCountedTwice() {
        val e = engine(); start(e); e.expectManagedLaunch(ProtectedApp.YOUTUBE)
        e.recordOpening(ProtectedApp.YOUTUBE)
        assertEquals(1, e.current.openings.size)
        e.recordOpening(ProtectedApp.YOUTUBE)
        assertEquals(2, e.current.openings.size)
        assertEquals(e.current.session!!.id, e.current.openings.last().sessionId)
    }
    @Test fun expiredLaunchSuppressionDoesNotHideLaterOpenings() {
        val e = engine(); e.expectManagedLaunch(ProtectedApp.YOUTUBE); time += 6000
        e.recordOpening(ProtectedApp.YOUTUBE); assertEquals(1, e.current.openings.size)
    }
    @Test fun csvContainsSessionOpeningAndExtensionWithStableIds() {
        val e = engine(); start(e); time += 30_000; e.requestExtension(); e.extensionReason("Finish video")
        e.extend(); e.visit(ProtectedApp.YOUTUBE); time += 10_000; e.reflect(); e.after(4); e.complete(true)
        val csv = StudyCsv.encode(e.exportSnapshot(), 1700000100000)
        assertTrue(csv.contains("\"opening\"")); assertTrue(csv.contains("\"session\"")); assertTrue(csv.contains("\"extension\""))
        assertTrue(csv.contains("Relax, learn \"\"Kotlin\"\"\n🌿"))
        assertTrue(csv.contains("\"40.0\"")); assertTrue(csv.contains("\"Finish video\""))
        assertEquals(e.current.history.single().id, e.current.openings.single().sessionId)
        assertEquals(e.current, SessionEngine.decode(SessionEngine.encode(e.current)))
    }
    @Test fun ongoingExportDoesNotFinalizeOrInventOutcomes() {
        val e = engine(); start(e); time += 5000
        val snapshot = e.exportSnapshot()
        assertEquals(5000, snapshot.session!!.elapsedMs)
        assertNull(snapshot.session.after); assertNull(snapshot.session.completed)
        assertEquals(0, e.current.session!!.elapsedMs)
        assertEquals(Stage.ACTIVE, e.current.stage)
    }
    @Test fun formulasAreNeutralizedAndNullsAreBlank() {
        assertEquals("\"'=SUM(A1)\"", StudyCsv.cell("=SUM(A1)"))
        assertEquals("\"'  @evil\"", StudyCsv.cell("  @evil"))
        assertEquals("\"-2\"", StudyCsv.cell(-2))
        assertEquals("\"\"", StudyCsv.cell(null))
    }
    @Test fun demoDoesNotCreateRealOpeningCounts() {
        val e = engine(); e.open(true); e.recordOpening(ProtectedApp.INSTAGRAM)
        assertTrue(e.current.openings.isEmpty())
    }
    @Test fun observedEntryAndGateDoNotCreateTwoAttempts() {
        val e = engine(); e.recordOpening(ProtectedApp.YOUTUBE)
        assertTrue(e.visit(ProtectedApp.YOUTUBE))
        assertEquals(1, e.current.openings.size)
        assertEquals("foreground_entry", e.current.openings.single().source)
        assertEquals(e.current.openings.single().id, e.current.draftOpeningId)
    }
    @Test fun retentionNoLongerDropsSessionsAtFiveHundred() {
        val e = engine()
        repeat(502) { start(e); e.reflect(); e.complete(true); e.home() }
        assertEquals(502, e.current.history.size); assertEquals(502, e.current.openings.size)
        assertEquals(502, e.current.history.map { it.id }.toSet().size)
    }
    @Test fun deletingStudyRecordsClearsOpeningsButKeepsParticipantId() {
        val e = engine(); start(e); e.reflect(); e.complete(true); e.clearHistory()
        assertTrue(e.current.openings.isEmpty()); assertTrue(e.current.history.isEmpty())
        assertEquals("participant-test", e.current.participantId)
    }
}

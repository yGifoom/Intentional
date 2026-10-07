package org.intentional.shared

import kotlin.test.*

class SessionEngineTest {
    private var time = 1_000L
    private fun engine() = SessionEngine({ time }, { 1_700_000_000_000 + time })
    private fun start(engine: SessionEngine, demo: Boolean = false, app: ProtectedApp = ProtectedApp.INSTAGRAM) {
        engine.open(demo, app); engine.acceptGate(); engine.intention("Relax after studying")
        engine.before(2); engine.chooseDuration(); assertTrue(engine.start())
        if (!demo) engine.visit(app)
    }
    @Test fun defaultIsFifteenMinutes() {
        val e = engine(); start(e)
        assertEquals(900_000, e.remainingMs())
        assertEquals(Stage.ACTIVE, e.current.stage)
    }
    @Test fun emptyIntentionCannotStart() {
        val e = engine(); e.open(false); e.acceptGate(); e.intention("   "); e.chooseDuration()
        assertEquals(Stage.INTENTION, e.current.stage); assertFalse(e.start())
    }
    @Test fun repeatedInstagramOpenPreservesPendingIntention() {
        val e = engine(); e.open(false); e.acceptGate(); e.intention("Reply to Léa")
        e.open(false)
        assertEquals(Stage.INTENTION, e.current.stage)
        assertEquals("Reply to Léa", e.current.intention)
    }
    @Test fun warningOccursOnceAtOneMinuteAndExpiryWins() {
        val e = engine(); start(e)
        time += 839_999; assertEquals(TimerEvent.NONE, e.tick())
        time++; assertEquals(TimerEvent.WARNING, e.tick()); assertEquals(TimerEvent.NONE, e.tick())
        time += 60_000; assertEquals(TimerEvent.EXPIRED, e.tick())
        assertEquals(Stage.EXPIRED, e.current.stage); assertEquals(900_000, e.current.session!!.elapsedMs)
    }
    @Test fun delayedTickExpiresWithoutLateWarning() {
        val e = engine(); start(e); time += 1_000_000
        assertEquals(TimerEvent.EXPIRED, e.tick()); assertEquals(1_000_000, e.current.session!!.elapsedMs)
    }
    @Test fun extensionRequiresReasonAndGetsNewReminder() {
        val e = engine(); start(e); time += 900_000; e.tick(); e.requestExtension()
        assertFalse(e.extend()); e.extensionReason("Finish replying to a friend"); e.extensionDuration(5)
        assertTrue(e.extend()); e.visit(ProtectedApp.INSTAGRAM); assertEquals(300_000, e.remainingMs())
        assertEquals(1, e.current.session!!.extensions.size)
        time += 240_000; assertEquals(TimerEvent.WARNING, e.tick())
    }
    @Test fun reflectionTimeDoesNotCountAsScrolling() {
        val e = engine(); start(e); time += 60_000; e.requestExtension()
        time += 120_000; e.extensionReason("One more reply"); e.extend(); e.visit(ProtectedApp.INSTAGRAM)
        time += 30_000; e.reflect(); time += 180_000; e.after(5); e.complete(false)
        val s = e.current.history.single()
        assertEquals(90_000, s.elapsedMs); assertEquals(false, s.completed)
        assertEquals(2, s.before); assertEquals(5, s.after)
    }
    @Test fun completionIsRecordedOnlyOnce() {
        val e = engine(); start(e); e.reflect(); e.complete(true); e.complete(false)
        assertEquals(1, e.current.history.size); assertEquals(true, e.current.history.single().completed)
        assertNull(e.current.session)
    }
    @Test fun demoCanBeAcceleratedButRealSessionCannot() {
        val e = engine(); start(e); e.demoJump(false); assertEquals(900_000, e.remainingMs())
        val demo = engine(); start(demo, true); assertEquals(75_000, demo.remainingMs())
        demo.demoJump(true); assertEquals(TimerEvent.WARNING, demo.tick())
        demo.demoJump(false); assertEquals(TimerEvent.EXPIRED, demo.tick())
        demo.reflect(); demo.complete(true); assertTrue(demo.current.history.single().demo)
    }
    @Test fun stateRestoresAndDeadlineDoesNotReset() {
        val e = engine(); start(e); time += 200_000
        val restored = SessionEngine({ time }, { time }, SessionEngine.decode(SessionEngine.encode(e.current)))
        assertEquals(700_000, restored.remainingMs()); time += 700_000
        assertEquals(TimerEvent.EXPIRED, restored.tick())
    }
    @Test fun journalRoundTripPreservesUnicodeQuotesAndExtensions() {
        val e = engine(); start(e); e.requestExtension(); e.extensionReason("Écrire \"bonjour\" 🌿\nà Léa"); e.extend()
        e.reflect(); e.after(4); e.complete(false)
        assertEquals(e.current, SessionEngine.decode(SessionEngine.encode(e.current)))
        assertTrue(JournalCodec.export(e.current.history).contains("schemaVersion"))
    }
    @Test fun activeSessionCannotBeOverwrittenOrSilentlyDismissed() {
        val e = engine(); start(e); val session = e.current.session
        e.open(true); e.home(); assertEquals(session, e.current.session)
        e.back(); assertEquals(Stage.REFLECT, e.current.stage)
        e.back(); assertEquals(Stage.REFLECT, e.current.stage)
    }
    @Test fun rebootedMonotonicClockExpiresSession() {
        val e = engine(); start(e); time = 0
        assertEquals(TimerEvent.EXPIRED, e.tick()); assertEquals(0, e.current.session!!.elapsedMs)
    }
    @Test fun durationAndRatingBoundsAreEnforced() {
        val e = engine(); e.duration(999); assertEquals(120, e.current.minutes)
        e.duration(0); assertEquals(1, e.current.minutes)
        e.before(-1); e.after(10); assertEquals(1, e.current.before); assertEquals(5, e.current.after)
    }
    @Test fun youtubeHasSameReminderExpiryAndExtensionFlow() {
        val e = engine(); start(e, app = ProtectedApp.YOUTUBE)
        assertEquals(ProtectedApp.YOUTUBE, e.current.session!!.app)
        time += 840_000; assertEquals(TimerEvent.WARNING, e.tick())
        time += 60_000; assertEquals(TimerEvent.EXPIRED, e.tick())
        e.requestExtension(); e.extensionReason("Finish the tutorial"); assertTrue(e.extend())
        e.reflect(); e.complete(true)
        assertEquals(ProtectedApp.YOUTUBE, e.current.history.single().app)
        assertEquals(e.current, SessionEngine.decode(SessionEngine.encode(e.current)))
        assertTrue(JournalCodec.export(e.current.history).contains("YOUTUBE"))
    }
    @Test fun revisitingSameAppPreservesTimer() {
        val e = engine(); start(e, app = ProtectedApp.YOUTUBE); time += 50_000
        assertFalse(e.visit(ProtectedApp.YOUTUBE))
        assertEquals(850_000, e.remainingMs())
    }
    @Test fun switchingAppsDoesNotGrantAccessUnderAnotherAppsSession() {
        for (app in ProtectedApp.entries) {
            val other = ProtectedApp.entries.first { it != app }
            val e = engine(); start(e, app = app); time += 30_000
            assertTrue(e.visit(other)); assertEquals(Stage.GATE, e.current.stage)
            assertEquals(app, e.current.suspended.single().app)
            assertEquals(30_000, e.current.suspended.single().elapsedMs)
            assertEquals(other, e.current.app)
            assertFalse(e.visit(app)); assertEquals(Stage.ACTIVE, e.current.stage)
            assertEquals(app, e.current.session!!.app)
        }
    }
    @Test fun newAppDuringUnstartedDraftGetsItsOwnGate() {
        val e = engine(); e.open(false); e.acceptGate(); e.intention("Reply to a friend")
        assertTrue(e.visit(ProtectedApp.YOUTUBE))
        assertEquals(Stage.GATE, e.current.stage); assertEquals("", e.current.intention)
        assertEquals(ProtectedApp.YOUTUBE, e.current.app)
    }
    @Test fun olderJournalsStillLoadAsInstagram() {
        val e = engine(); start(e); e.reflect(); e.complete(true)
        val expected = e.current.copy(openings = emptyList())
        val oldJson = SessionEngine.encode(expected).replace("\"app\":\"INSTAGRAM\",", "")
        assertFalse(oldJson.contains("\"app\""))
        assertEquals(expected, SessionEngine.decode(oldJson))
    }
    @Test fun onlyNativeSupportedPackagesAreIntercepted() {
        assertEquals(ProtectedApp.YOUTUBE, ProtectedApp.fromPackage("com.google.android.youtube"))
        assertEquals(ProtectedApp.INSTAGRAM, ProtectedApp.fromPackage("com.instagram.android"))
        assertNull(ProtectedApp.fromPackage("com.android.chrome"))
        assertNull(ProtectedApp.fromPackage("com.google.android.apps.youtube.music"))
    }
}

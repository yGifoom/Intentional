package org.intentional.shared

import kotlin.test.*

class StudySyncPolicyTest {
    private fun eligible(configured: Boolean = true, acknowledged: Boolean = true,
                         digest: String = "new", received: String? = "old", wait: Long = 0,
                         bytes: Int = 100) = StudySyncPolicy.shouldUpload(configured, acknowledged, digest, received, wait, bytes)
    @Test fun backgroundCollectionWaitsForTheNoticeAndConfiguration() {
        assertFalse(eligible(acknowledged = false)); assertFalse(eligible(configured = false))
        assertTrue(eligible())
    }
    @Test fun unchangedAndCoolingDownDataDoNotUpload() {
        assertFalse(eligible(digest = "old")); assertFalse(eligible(wait = 1))
        assertTrue(eligible(wait = 0))
    }
    @Test fun oversizedJournalsCannotUploadAndNoFirstAckMeansPending() {
        assertFalse(eligible(bytes = 524289)); assertFalse(eligible(bytes = 0))
        assertTrue(eligible(bytes = 524288)); assertTrue(eligible(received = null))
    }
    @Test fun comparisonIsStableButActualNewRecordsChangeIt() {
        val snapshot = Snapshot(participantId = "participant", trackingStartedAt = 100)
        assertEquals(StudySyncPolicy.comparableCsv(snapshot), StudySyncPolicy.comparableCsv(snapshot.copy()))
        val changed = snapshot.copy(openings = listOf(Opening(1, 200, ProtectedApp.YOUTUBE, "foreground_entry")))
        assertNotEquals(StudySyncPolicy.comparableCsv(snapshot), StudySyncPolicy.comparableCsv(changed))
        assertNotEquals(StudyCsv.encode(snapshot, 1000), StudyCsv.encode(snapshot, 2000))
    }
    @Test fun newDataDuringUploadRemainsPendingAndFailedUploadsAreNotAcknowledged() {
        val captured = "snapshot-A"
        var acknowledged: String? = null
        assertTrue(eligible(digest = captured, received = acknowledged))
        // Failure leaves the acknowledgment unchanged.
        assertTrue(eligible(digest = captured, received = acknowledged))
        // Success acknowledges A, not data arriving while A was in flight.
        acknowledged = captured
        assertFalse(eligible(digest = captured, received = acknowledged))
        assertTrue(eligible(digest = "snapshot-B", received = acknowledged))
    }
}

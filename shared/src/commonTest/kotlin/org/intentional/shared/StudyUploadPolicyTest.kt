package org.intentional.shared

import kotlin.test.*

class StudyUploadPolicyTest {
    @Test fun maximumSizeIsInBytesNotCharacters() {
        StudyUploadPolicy.validate(ByteArray(524288))
        assertFailsWith<IllegalArgumentException> { StudyUploadPolicy.validate(ByteArray(524289)) }
        assertFailsWith<IllegalArgumentException> { StudyUploadPolicy.validate(ByteArray(0)) }
    }
    @Test fun unicodeCountsAgainstTheByteLimit() {
        assertFailsWith<IllegalArgumentException> { StudyUploadPolicy.validate("🌿".repeat(131073).encodeToByteArray()) }
    }
    @Test fun fifteenMinuteBoundaryIsInclusive() {
        assertEquals(0, StudyUploadPolicy.remainingMs(null, 0))
        assertEquals(900_000, StudyUploadPolicy.remainingMs(1000, 1000))
        assertEquals(1, StudyUploadPolicy.remainingMs(1000, 900_999))
        assertEquals(0, StudyUploadPolicy.remainingMs(1000, 901_000))
    }
    @Test fun backwardsClockCannotCreateAnUnboundedLocalCooldown() {
        assertEquals(900_000, StudyUploadPolicy.remainingMs(1000, -100_000))
    }
}

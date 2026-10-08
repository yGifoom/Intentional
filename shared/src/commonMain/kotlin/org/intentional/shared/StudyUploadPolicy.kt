package org.intentional.shared

object StudyUploadPolicy {
    const val MAX_CSV_BYTES = 512 * 1024
    const val INTERVAL_MS = 15 * 60_000L
    fun remainingMs(lastSubmission: Long?, now: Long): Long = lastSubmission?.let {
        (INTERVAL_MS - (now - it)).coerceIn(0, INTERVAL_MS)
    } ?: 0
    fun validate(csv: ByteArray) {
        require(csv.isNotEmpty() && csv.size <= MAX_CSV_BYTES) { "CSV must be at most 512 KiB; oversized journals stay on the device." }
    }
}

package org.intentional.shared

/** Use a fixed export timestamp when comparing content; a clock tick is not new data. */
object StudySyncPolicy {
    fun comparableCsv(snapshot: Snapshot) = "\uFEFF" + StudyCsv.encode(snapshot, exportedAt = 0)
    fun shouldUpload(configured: Boolean, enabled: Boolean, fingerprint: String,
                     acknowledgedFingerprint: String?, cooldownMs: Long, csvBytes: Int): Boolean =
        configured && enabled && fingerprint != acknowledgedFingerprint && cooldownMs == 0L &&
            csvBytes in 1..StudyUploadPolicy.MAX_CSV_BYTES
}

package org.intentional.app

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.intentional.shared.SessionEngine
import org.intentional.shared.StudyCsv
import org.intentional.shared.StudySyncPolicy
import org.intentional.shared.StudyUploadPolicy
import java.security.MessageDigest

data class StudySyncStatus(val configured: Boolean, val enabled: Boolean = false, val sending: Boolean = false,
    val pending: Boolean = false, val lastSuccessfulAt: Long = 0, val retryAt: Long = 0, val message: String? = null)
enum class SyncResult { DONE, RETRY }

/** Single coordinator for the UI and Android background jobs. Journal mutations and
 * snapshot capture stay on the main thread; authentication/network IO runs off it. */
class StudySyncManager(private val context: Context, private val engine: SessionEngine) {
    private val preferences = context.getSharedPreferences("study_sync_settings", Context.MODE_PRIVATE)
    private val uploader = FirebaseStudyUploader(context)
    private val scheduler = context.getSystemService(JobScheduler::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(StudySyncStatus(uploader.configured,
        enabled = preferences.getBoolean("notice_acknowledged", false)))
    val state = mutable.asStateFlow()
    private fun fingerprint() = MessageDigest.getInstance("SHA-256")
        .digest(StudySyncPolicy.comparableCsv(engine.current).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private val refreshTask = Runnable { refresh() }
    fun journalChanged() {
        handler.removeCallbacks(refreshTask)
        handler.postDelayed(refreshTask, 1000) // Coalesce typing/checkpoints; never enqueue per keystroke.
    }
    fun refresh() {
        val pending = fingerprint() != uploader.lastFingerprint
        mutable.value = mutable.value.copy(pending = pending, lastSuccessfulAt = uploader.lastSuccessfulAt,
            retryAt = System.currentTimeMillis() + uploader.cooldownMs())
        if (mutable.value.enabled && uploader.configured) {
            if (scheduler.getPendingJob(PERIODIC_JOB) == null) enqueue(periodic = true)
            if (pending && scheduler.getPendingJob(CHANGED_JOB) == null) enqueue(periodic = false)
        } else {
            scheduler.cancel(PERIODIC_JOB); scheduler.cancel(CHANGED_JOB)
        }
    }
    fun acknowledgeNotice() {
        preferences.edit().putBoolean("notice_acknowledged", true).apply()
        mutable.value = mutable.value.copy(enabled = true)
        refresh()
    }
    private fun enqueue(periodic: Boolean) {
        val builder = JobInfo.Builder(if (periodic) PERIODIC_JOB else CHANGED_JOB,
            ComponentName(context, StudySyncJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
        if (periodic) builder.setPeriodic(StudyUploadPolicy.INTERVAL_MS)
        else builder.setMinimumLatency(uploader.cooldownMs())
        if (scheduler.schedule(builder.build()) == JobScheduler.RESULT_FAILURE) {
            mutable.value = mutable.value.copy(message = "Android could not schedule background sync. Retry on the next launch.")
        }
    }
    suspend fun sync(): SyncResult = mutex.withLock {
        if (!uploader.configured || !mutable.value.enabled) return@withLock SyncResult.DONE
        engine.tick() // Finalize an inactivity deadline before capturing the CSV.
        val snapshot = engine.current // Confirmed checkpoint data; no invented process gap.
        val capturedFingerprint = fingerprint()
        if (capturedFingerprint == uploader.lastFingerprint) {
            mutable.value = mutable.value.copy(message = "Study CSV is already up to date.")
            refresh(); return@withLock SyncResult.DONE
        }
        if (uploader.cooldownMs() > 0) {
            refresh(); return@withLock SyncResult.DONE
        }
        val csv = ("\uFEFF" + StudyCsv.encode(snapshot, System.currentTimeMillis())).toByteArray(Charsets.UTF_8)
        if (!StudySyncPolicy.shouldUpload(uploader.configured, mutable.value.enabled,
                capturedFingerprint, uploader.lastFingerprint, uploader.cooldownMs(), csv.size)) {
            mutable.value = mutable.value.copy(message = "CSV exceeds 512 KiB. Upload blocked; local data is kept for researcher support.")
            refresh(); return@withLock SyncResult.DONE
        }
        mutable.value = mutable.value.copy(sending = true, message = null)
        try {
            val message = uploader.submit(snapshot.participantId, csv, capturedFingerprint)
            mutable.value = mutable.value.copy(message = message)
            SyncResult.DONE
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) {
            mutable.value = mutable.value.copy(message = if (error is IllegalStateException || error is IllegalArgumentException) error.message
                else "Sync is waiting to retry. Local data is kept.")
            if (uploader.cooldownMs() > 0) SyncResult.DONE else SyncResult.RETRY
        } finally {
            mutable.value = mutable.value.copy(sending = false)
            refresh()
        }
    }
    companion object {
        const val PERIODIC_JOB = 7301
        const val CHANGED_JOB = 7302
    }
}

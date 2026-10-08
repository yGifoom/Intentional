package org.intentional.app

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.*

class StudySyncJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = mutableMapOf<Int, Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result = try { (application as IntentionalApplication).studySync.sync() }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { SyncResult.RETRY }
            running.remove(params.jobId)
            jobFinished(params, result == SyncResult.RETRY)
        }
        running[params.jobId] = job
        job.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return (application as IntentionalApplication).studySync.state.value.enabled
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

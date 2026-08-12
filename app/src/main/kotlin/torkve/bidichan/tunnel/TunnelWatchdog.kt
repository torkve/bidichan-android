package torkve.bidichan.tunnel

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import torkve.bidichan.core.AppLog

/**
 * Brings the tunnel back when it is found down.
 *
 * Nothing an app can do stops Android killing it, so the answer is not to try
 * but to notice and return — which is what ics-openvpn does, and it is the part
 * of its design that keeps a tunnel up across days. A job survives the process
 * that scheduled it and, being persisted, the device restarting too; so where
 * the service being restarted depends on the system choosing to restart it,
 * this depends on nothing that died.
 *
 * It only ever restarts a profile the user asked for and has not switched off:
 * [TunnelService] forgets that on a deliberate disconnect, and this is
 * unscheduled at the same time.
 */
class TunnelWatchdog : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val profileId = params.extras.getString(EXTRA_PROFILE)
        if (profileId == null) {
            cancel(this)
            return false
        }
        if (TunnelService.isRunning) return false
        AppLog.log("the tunnel was not running; starting it again")
        runCatching { TunnelService.start(this, profileId) }
            .onFailure { AppLog.log("could not restart the tunnel: ${it.message}") }
        // Done: the work is a single start, with nothing to wait for.
        return false
    }

    /** Nothing is in flight, so there is nothing to stop and nothing to retry. */
    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val JOB_ID = 0x0b1d1
        private const val EXTRA_PROFILE = "profileId"

        fun schedule(context: Context, profileId: String) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, TunnelWatchdog::class.java))
                // The platform's floor is fifteen minutes and it will not be
                // argued down, so that is the worst case for noticing.
                .setPeriodic(JobInfo.getMinPeriodMillis())
                // Outlives a reboot, which is the case the service's own
                // restart cannot cover.
                .setPersisted(true)
                .setExtras(PersistableBundle().apply { putString(EXTRA_PROFILE, profileId) })
                .build()
            runCatching { scheduler.schedule(info) }
                .onFailure { AppLog.log("could not schedule the watchdog: ${it.message}") }
        }

        fun cancel(context: Context) {
            runCatching { context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) }
        }
    }
}

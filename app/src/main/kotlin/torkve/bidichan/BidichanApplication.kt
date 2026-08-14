package torkve.bidichan

import android.app.Application
import torkve.bidichan.core.AppLog
import torkve.bidichan.core.CrashOutput
import torkve.bidichan.core.ExitReason

/**
 * Exists to give the log somewhere to live before anything else runs.
 *
 * The process is created both when the user opens the app and when the system
 * restarts the tunnel service on its own, and only Application.onCreate is
 * guaranteed to come first in either case.
 */
class BidichanApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.attach(this)
        // Straight after the marker, so the log reads: the tunnel stopped
        // mid-sentence, a process started, and this is what took the last one.
        ExitReason.logPrevious(this)
        // And, where the last one died inside the core, what it said on the way
        // out — which the system's own record does not carry.
        CrashOutput.collectAndArm(this)
    }
}

package torkve.bidichan

import android.app.Application
import torkve.bidichan.core.AppLog

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
    }
}

package tw.com.recora.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tw.com.recora.BuildConfig

/** Cleans up cached update APKs right after Recora updates itself. */
class AppReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val hadUpdateFiles = AppUpdater.cleanupUpdateCache(context.applicationContext)
        if (hadUpdateFiles) {
            AppUpdateNotifier.notifyUpdated(context.applicationContext, BuildConfig.VERSION_NAME)
        }
    }
}

package soft.shadlv.twp_rewritekts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import soft.shadlv.twp_rewritekts.services.LocalTGProxyService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val serviceIntent = Intent(context, LocalTGProxyService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
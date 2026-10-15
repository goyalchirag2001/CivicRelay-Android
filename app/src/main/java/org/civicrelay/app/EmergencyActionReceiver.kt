package org.civicrelay.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Explicit user tap on foreground notification; never sends SMS directly. */
class EmergencyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) return
        (context.applicationContext as CivicRelayApp).civic.triggerQuickSos()
    }
    companion object { const val ACTION = "org.civicrelay.app.ACTION_QUICK_SOS" }
}

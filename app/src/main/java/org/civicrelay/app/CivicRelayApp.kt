package org.civicrelay.app

import android.app.Application
import android.os.Handler
import android.os.Looper

/** Process-owned components; navigating away never implicitly stops a user-enabled radio session. */
class CivicRelayApp : Application() {
    val civic: CivicService by lazy { CivicService(applicationContext) }
    private val handler = Handler(Looper.getMainLooper())
    private val lockLater = Runnable { if (civic.vault.unlocked) civic.lock() }
    /** Two-minute grace period only; the Nearby radio is independent of the vault. */
    fun schedulePrivateLock() {
        handler.removeCallbacks(lockLater)
        handler.postDelayed(lockLater, 120_000L)
    }
    fun cancelPrivateLock() = handler.removeCallbacks(lockLater)
}

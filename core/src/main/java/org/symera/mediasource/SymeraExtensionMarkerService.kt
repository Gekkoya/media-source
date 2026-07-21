package org.symera.mediasource

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Disabled service used exclusively for extension discovery by the host. */
class SymeraExtensionMarkerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}

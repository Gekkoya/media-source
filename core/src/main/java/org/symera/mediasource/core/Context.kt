package org.symera.mediasource.core

import android.app.Application

@Volatile
var applicationProvider: () -> Application? = {
    runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        activityThread.getMethod("currentApplication").invoke(null) as? Application
    }.getOrNull()
}

val applicationContext: Application
    get() = requireNotNull(applicationProvider()) { "Symera application provider is not initialized" }

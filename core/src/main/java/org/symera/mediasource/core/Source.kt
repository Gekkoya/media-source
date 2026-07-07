package org.symera.mediasource.core

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.serialization.json.Json
import org.symera.source.ConfigurableSymeraSource
import org.symera.source.online.SymeraHttpSource

abstract class Source :
    SymeraHttpSource(),
    ConfigurableSymeraSource {
    protected val context: Application
        get() = applicationContext

    open val json: Json = jsonInstance

    val preferences: SourcePreferenceAccessor by lazy { sourcePreferences() }

    protected val handler: Handler by lazy { Handler(Looper.getMainLooper()) }

    protected fun displayToast(message: String, length: Int = Toast.LENGTH_SHORT) {
        handler.post {
            Toast.makeText(context, message, length).show()
        }
    }
}

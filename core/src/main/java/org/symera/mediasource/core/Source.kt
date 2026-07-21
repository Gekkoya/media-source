package org.symera.mediasource.core

import kotlinx.serialization.json.Json
import org.symera.source.ConfigurableSymeraSource
import org.symera.source.SourceEnvironment
import org.symera.source.SourcePreferenceValues
import org.symera.source.online.SymeraHttpSource

abstract class Source(
    environment: SourceEnvironment,
) : SymeraHttpSource(environment),
    ConfigurableSymeraSource {
    open val json: Json = jsonInstance

    protected fun preferenceValues(): SourcePreferenceValues = environment.preferencesFor(sourcePreferenceNamespace)
}

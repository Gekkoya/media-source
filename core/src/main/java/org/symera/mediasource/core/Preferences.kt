package org.symera.mediasource.core

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

interface SourcePreferenceProvider {
    fun getString(sourceKey: String, key: String, default: String): String = default
    fun getBoolean(sourceKey: String, key: String, default: Boolean): Boolean = default
    fun getStringSet(sourceKey: String, key: String, default: Set<String>): Set<String> = default
}

object SourcePreferences {
    @Volatile
    var provider: SourcePreferenceProvider = object : SourcePreferenceProvider {}

    fun forSource(sourceKey: String): SourcePreferenceAccessor = SourcePreferenceAccessor(sourceKey)
}

class SourcePreferenceAccessor internal constructor(
    private val sourceKey: String,
) {
    fun getString(key: String, default: String): String = SourcePreferences.provider.getString(sourceKey, key, default)

    fun getBoolean(key: String, default: Boolean): Boolean = SourcePreferences.provider.getBoolean(sourceKey, key, default)

    fun getStringSet(key: String, default: Set<String>): Set<String> = SourcePreferences.provider.getStringSet(sourceKey, key, default)
}

fun Any.sourcePreferences(sourceKey: String = this::class.java.name): SourcePreferenceAccessor = SourcePreferences.forSource(sourceKey)

class LazyMutable<T>(
    private val initializer: () -> T,
) : ReadWriteProperty<Any?, T> {
    private object UninitializedValue

    @Volatile
    private var propValue: Any? = UninitializedValue

    @Suppress("UNCHECKED_CAST")
    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        val localValue = propValue
        if (localValue != UninitializedValue) return localValue as T

        return synchronized(this) {
            val localValue2 = propValue
            if (localValue2 != UninitializedValue) {
                localValue2 as T
            } else {
                initializer().also { propValue = it }
            }
        }
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        synchronized(this) {
            propValue = value
        }
    }
}

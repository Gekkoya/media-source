package symera.gradle.extensions

import org.gradle.api.Project

internal val Project.baseVersionCode: Int
    get() = extraInt("baseVersionCode")

internal fun Project.extraString(name: String): String = extensions.extraProperties.get(name) as String

internal fun Project.extraInt(name: String): Int = extensions.extraProperties.get(name) as Int

internal fun Project.extraBoolean(name: String): Boolean {
    val values = extensions.extraProperties
    return values.has(name) && values.get(name) == true
}

internal fun Project.hasExtra(name: String): Boolean = extensions.extraProperties.has(name)

internal fun Project.optionalExtraString(name: String): String? = if (hasExtra(name)) extraString(name) else null

package symera.gradle.extensions

import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.accessors.dm.LibrariesForSymera
import org.gradle.api.Project
import org.gradle.api.plugins.PluginManager
import org.gradle.kotlin.dsl.the

internal val Project.libs: LibrariesForLibs
    get() = the()

internal val Project.symera: LibrariesForSymera
    get() = the()

internal fun Project.applyPlugins(block: PluginManager.() -> Unit) {
    pluginManager.block()
}

internal fun Project.formatTaskName(): String = if (providers.environmentVariable("CI").orNull == "true") "spotlessCheck" else "spotlessApply"

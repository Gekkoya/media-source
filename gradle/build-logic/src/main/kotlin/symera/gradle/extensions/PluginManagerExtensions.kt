package symera.gradle.extensions

import org.gradle.api.plugins.PluginManager
import org.gradle.api.provider.Provider
import org.gradle.plugin.use.PluginDependency

internal fun PluginManager.applyCatalogPlugin(plugin: Provider<PluginDependency>) {
    apply(plugin.get().pluginId)
}

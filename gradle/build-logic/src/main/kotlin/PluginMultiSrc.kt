import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import symera.gradle.extensions.applyCatalogPlugin
import symera.gradle.extensions.applyPlugins
import symera.gradle.extensions.compileOnly
import symera.gradle.extensions.configureAndroidMainSourceSet
import symera.gradle.extensions.implementation
import symera.gradle.extensions.libs
import symera.gradle.extensions.symera

@Suppress("unused")
class PluginMultiSrc : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        val libsCatalog = libs
        val symeraCatalog = symera

        applyPlugins {
            applyCatalogPlugin(libsCatalog.plugins.android.library)
            applyCatalogPlugin(libsCatalog.plugins.kotlin.serialization)
            applyCatalogPlugin(symeraCatalog.plugins.android.base)
            applyCatalogPlugin(symeraCatalog.plugins.spotless)
        }

        android {
            namespace = "org.symera.mediasource.multisrc.${project.name.replace('-', '_')}"
        }

        configureAndroidMainSourceSet(manifestFile = "AndroidManifest.xml", resDir = "res", assetsDir = "assets")

        dependencies {
            compileOnly(libsCatalog.bundles.common)
            implementation(project(":core"))
        }
    }
}

private fun Project.android(block: LibraryExtension.() -> Unit) {
    extensions.configure(block)
}

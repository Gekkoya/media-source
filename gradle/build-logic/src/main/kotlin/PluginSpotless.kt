import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.spotless.LineEnding
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import symera.gradle.extensions.applyCatalogPlugin
import symera.gradle.extensions.applyPlugins
import symera.gradle.extensions.libs

@Suppress("unused")
class PluginSpotless : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        val libsCatalog = libs

        applyPlugins {
            applyCatalogPlugin(libsCatalog.plugins.spotless)
        }

        val generatedExcludes = arrayOf("build/**", "**/build/**", ".gradle/**", "**/.gradle/**")
        val ktlintVersion = libsCatalog.versions.ktlint.get()

        spotless {
            lineEndings = LineEnding.UNIX

            kotlin {
                target("src/**/*.kt", "*.kts")
                targetExclude(*generatedExcludes)
                ktlint(ktlintVersion)
                    .editorConfigOverride(
                        mapOf(
                            "max_line_length" to 2147483647,
                        ),
                    )
                trimTrailingWhitespace()
                endWithNewline()
            }

            java {
                target("src/**/*.java")
                targetExclude(*generatedExcludes)
                googleJavaFormat()
                removeUnusedImports()
                trimTrailingWhitespace()
                endWithNewline()
            }

            format("gradle") {
                target("*.gradle")
                targetExclude(*generatedExcludes)
                trimTrailingWhitespace()
                endWithNewline()
            }

            format("xml") {
                target("src/**/*.xml")
                targetExclude(*generatedExcludes)
                trimTrailingWhitespace()
                endWithNewline()
            }
        }
    }
}

private fun Project.spotless(block: SpotlessExtension.() -> Unit) {
    extensions.configure(block)
}

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import symera.gradle.configurations.configureKotlin
import symera.gradle.extensions.symera

@Suppress("unused")
class PluginAndroidBase : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        configureKotlin()

        val javaVersion = JavaVersion.toVersion(symera.versions.java.get())

        plugins.withId("com.android.application") {
            extensions.configure<ApplicationExtension> {
                compileSdk = symera.versions.android.sdk.compile.get().toInt()
                defaultConfig {
                    minSdk = symera.versions.android.sdk.min.get().toInt()
                    targetSdk = symera.versions.android.sdk.target.get().toInt()
                    file("proguard-rules.pro").takeIf { it.exists() }?.let(::proguardFile)
                }
                compileOptions {
                    sourceCompatibility = javaVersion
                    targetCompatibility = javaVersion
                }
            }
        }

        plugins.withId("com.android.library") {
            extensions.configure<LibraryExtension> {
                compileSdk = symera.versions.android.sdk.compile.get().toInt()
                defaultConfig {
                    minSdk = symera.versions.android.sdk.min.get().toInt()
                    file("proguard-rules.pro").takeIf { it.exists() }?.let { consumerProguardFiles(it) }
                }
                compileOptions {
                    sourceCompatibility = javaVersion
                    targetCompatibility = javaVersion
                }
            }
        }
    }
}

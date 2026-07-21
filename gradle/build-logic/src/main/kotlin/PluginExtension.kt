import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.tasks.PackageAndroidArtifact
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import symera.gradle.extensions.applyCatalogPlugin
import symera.gradle.extensions.applyPlugins
import symera.gradle.extensions.baseVersionCode
import symera.gradle.extensions.compileOnly
import symera.gradle.extensions.configureAndroidMainSourceSet
import symera.gradle.extensions.extraBoolean
import symera.gradle.extensions.extraInt
import symera.gradle.extensions.extraString
import symera.gradle.extensions.hasExtra
import symera.gradle.extensions.implementation
import symera.gradle.extensions.libs
import symera.gradle.extensions.symera
import symera.gradle.tasks.GenerateKeepRulesTask
import symera.gradle.util.requireBuildCondition

@Suppress("unused")
class PluginExtension : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        val libsCatalog = libs
        val symeraCatalog = symera

        applyPlugins {
            applyCatalogPlugin(libsCatalog.plugins.android.application)
            applyCatalogPlugin(libsCatalog.plugins.kotlin.serialization)
            applyCatalogPlugin(symeraCatalog.plugins.android.base)
            applyCatalogPlugin(symeraCatalog.plugins.spotless)
        }

        val extensionName = extString("extName")
        val extensionClass = extString("extClass")
        requireBuildCondition(!hasExtra("pkgNameSuffix")) { "Gradle configuration cannot contain 'pkgNameSuffix'" }
        requireBuildCondition(!hasExtra("libVersion")) { "Gradle configuration cannot contain 'libVersion'" }
        requireBuildCondition(extensionClass.startsWith(".")) { "'extClass' must start with '.'" }
        requireBuildCondition(extensionName.maxOf { it.code } < 0x180) { "Extension name should be romanized" }

        val theme: Project? = if (extensions.extraProperties.has("themePkg")) {
            project(":lib-multisrc:${extString("themePkg")}")
        } else {
            null
        }
        if (theme != null) evaluationDependsOn(theme.path)

        android {
            namespace = "org.symera.mediasource"

            defaultConfig {
                val lang = project.parent?.name ?: "all"
                applicationId = "org.symera.mediasource.$lang.${project.name}"
                val extensionVersion = if (theme == null) extInt("extVersionCode") else theme.baseVersionCode + extInt("overrideVersionCode")
                versionCode = SDK_VERSION_CODE_OFFSET + extensionVersion
                versionName = "4.$extensionVersion"
                base {
                    archivesName.set("symera-$lang-${project.name}-v$versionName")
                }
                manifestPlaceholders += mapOf(
                    "appName" to "Symera: $extensionName",
                    "extClass" to "$applicationId$extensionClass",
                    "sourceSdk" to symeraCatalog.versions.source.sdk.get(),
                    "nsfw" to extBoolean("isNsfw"),
                )
            }

            lint {
                checkReleaseBuilds = false
            }

            signingConfigs {
                create("release") {
                    storeFile = rootProject.file("signingkey.jks")
                    storePassword = providers.environmentVariable("KEY_STORE_PASSWORD").orNull
                    keyAlias = providers.environmentVariable("ALIAS").orNull
                    keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
                }
            }

            buildTypes {
                named("release") {
                    signingConfig = if (rootProject.file("signingkey.jks").exists()) {
                        signingConfigs.getByName("release")
                    } else {
                        signingConfigs.getByName("debug")
                    }
                    isMinifyEnabled = true
                    proguardFiles(rootProject.file("common/proguard-rules.pro"))
                    vcsInfo.include = false
                }
            }

            dependenciesInfo {
                includeInApk = false
            }

            buildFeatures {
                buildConfig = true
            }

            buildTypes {
                configureEach {
                    buildConfigField("String", "MEGACLOUD_API", buildConfigString("MEGACLOUD_API", MEGACLOUD_API_DEFAULT))
                    buildConfigField("String", "KISSKH_API", buildConfigString("KISSKH_API", KISSKH_API_DEFAULT))
                    buildConfigField("String", "KISSKH_SUB_API", buildConfigString("KISSKH_SUB_API", KISSKH_SUB_API_DEFAULT))
                    buildConfigField("String", "KAISVA", buildConfigString("KAISVA", KAISVA_DEFAULT))
                    buildConfigField("String", "TMDB_API", buildConfigString("TMDB_API"))
                }
            }

            packaging {
                resources.excludes.add("kotlin-tooling-metadata.json")
            }
        }

        configureAndroidMainSourceSet(
            manifestFile = rootProject.file("common/AndroidManifest.xml"),
            resDir = "res",
            assetsDir = "assets",
        )

        androidComponents {
            onVariants { variant ->
                val variantName = variant.name.replaceFirstChar { it.uppercase() }
                val keepRules = variant.sources.keepRules
                if (keepRules != null) {
                    val task = tasks.register<GenerateKeepRulesTask>("generate${variantName}KeepRules") {
                        applicationId.set(variant.applicationId)
                        extClass.set(extensionClass)
                    }
                    keepRules.addGeneratedSourceDirectory(task) { it.outputDir }
                }
            }
        }

        dependencies {
            if (theme != null) implementation(theme)
            implementation(project(":core"))
            compileOnly(libsCatalog.bundles.common)
        }

        afterEvaluate {
            tasks.withType<PackageAndroidArtifact>().configureEach {
                createdBy.set("")
                doFirst {
                    appMetadata.asFile.orNull?.writeText("")
                }
            }
        }
    }
}

private fun Project.android(block: ApplicationExtension.() -> Unit) {
    extensions.configure(block)
}

private fun Project.androidComponents(block: ApplicationAndroidComponentsExtension.() -> Unit) {
    extensions.configure(block)
}

private fun Project.base(block: BasePluginExtension.() -> Unit) {
    extensions.configure(block)
}

private fun Project.extString(name: String): String = extraString(name)

private fun Project.extInt(name: String): Int = extraInt(name)

private fun Project.extBoolean(name: String): Boolean = extraBoolean(name)

private const val MEGACLOUD_API_DEFAULT = "https://script.google.com/macros/s/AKfycbxHbYHbrGMXYD2-bC-C43D3njIbU-wGiYQuJL61H4vyy6YVXkybMNNEPJNPPuZrD1gRVA/exec"
private const val KISSKH_API_DEFAULT = "https://script.google.com/macros/s/AKfycbzn8B31PuDxzaMa9_CQ0VGEDasFqfzI5bXvjaIZH4DM8DNq9q6xj1ALvZNz_JT3jF0suA/exec?id="
private const val KISSKH_SUB_API_DEFAULT = "https://script.google.com/macros/s/AKfycbyq6hTj0ZhlinYC6xbggtgo166tp6XaDKBCGtnYk8uOfYBUFwwxBui0sGXiu_zIFmA/exec?id="
private const val KAISVA_DEFAULT = "https://c-kai-8090.amarullz.com"
private const val SDK_VERSION_CODE_OFFSET = 400_000

private fun Project.buildConfigString(name: String, defaultValue: String = ""): String {
    val value = providers.environmentVariable(name).orNull ?: defaultValue
    return "\"${value.escapeBuildConfigString()}\""
}

private fun String.escapeBuildConfigString(): String = buildString(length) {
    for (char in this@escapeBuildConfigString) {
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
}

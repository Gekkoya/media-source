import com.diffplug.spotless.LineEnding

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.samWithReceiver)
    alias(libs.plugins.spotless)
    `java-gradle-plugin`
}

val ktlintVersion = libs.versions.ktlint.get()
val editorConfigFile = rootProject.file("../../.editorconfig")

spotless {
    lineEndings = LineEnding.UNIX

    kotlin {
        target("src/**/*.kt", "*.kts")
        ktlint(ktlintVersion)
            .setEditorConfigPath(editorConfigFile)
            .editorConfigOverride(
                mapOf(
                    "max_line_length" to 2147483647,
                ),
            )
        trimTrailingWhitespace()
        endWithNewline()
    }
}

dependencies {
    compileOnly(gradleKotlinDsl())
    compileOnly(libs.android.gradle)
    compileOnly(libs.kotlin.gradle)
    implementation(libs.spotless.gradle)

    compileOnly(files(libs::class.java.superclass.protectionDomain.codeSource.location))
    compileOnly(files(symera::class.java.superclass.protectionDomain.codeSource.location))
}

samWithReceiver {
    annotation("org.gradle.api.HasImplicitReceiver")
}

gradlePlugin {
    plugins {
        register("android-base") {
            id = symera.plugins.android.base.get().pluginId
            implementationClass = "PluginAndroidBase"
        }
        register("extension") {
            id = symera.plugins.extension.get().pluginId
            implementationClass = "PluginExtension"
        }
        register("library") {
            id = symera.plugins.library.get().pluginId
            implementationClass = "PluginLibrary"
        }
        register("multisrc") {
            id = symera.plugins.multisrc.get().pluginId
            implementationClass = "PluginMultiSrc"
        }
        register("spotless") {
            id = symera.plugins.spotless.get().pluginId
            implementationClass = "PluginSpotless"
        }
    }
}

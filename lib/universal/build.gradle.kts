plugins {
    alias(symera.plugins.library)
}

dependencies {
    implementation(projects.lib.playlistutils)
    implementation(projects.lib.webview)
    testImplementation(libs.bundles.common)
    testImplementation(libs.junit)
}

android {
    sourceSets {
        named("test") {
            java.srcDir("test")
        }
    }
}

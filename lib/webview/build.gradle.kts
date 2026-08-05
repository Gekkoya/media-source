plugins {
    alias(symera.plugins.library)
}

dependencies {
    implementation(libs.bundles.common)
    testImplementation(libs.junit)
}

android {
    sourceSets {
        named("test") {
            java.srcDir("test")
        }
    }
}

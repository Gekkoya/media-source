plugins {
    alias(symera.plugins.library)
}

android {
    sourceSets {
        named("androidTest") {
            java.srcDir("test")
        }
        named("test") {
            java.srcDir("test")
        }
    }
}

dependencies {
    testImplementation(libs.bundles.common)
    testImplementation(libs.junit)
    androidTestImplementation(libs.bundles.common)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

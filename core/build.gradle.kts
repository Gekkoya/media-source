plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(symera.plugins.android.base)
    alias(symera.plugins.spotless)
}

android {
    namespace = "org.symera.mediasource.core"

    buildFeatures {
        resValues = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    compileOnly(libs.bundles.common)
    testImplementation(libs.bundles.common)
    testImplementation(libs.junit)
}

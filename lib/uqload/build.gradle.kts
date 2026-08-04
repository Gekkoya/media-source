plugins {
    id("symera.plugins.library")
}

dependencies {
    implementation(project(":lib:unpacker"))
    testImplementation(libs.bundles.common)
    testImplementation(libs.junit)
    androidTestImplementation(libs.bundles.common)
    androidTestImplementation(libs.junit)
}

android {
    sourceSets {
        getByName("androidTest").java.srcDir("test")
        getByName("test").java.srcDir("test")
    }
}

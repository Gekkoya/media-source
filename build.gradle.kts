// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(symera.plugins.spotless)
}

tasks.named<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

val buildLogic = gradle.includedBuild("build-logic")

tasks {
    listOf("clean", "spotlessApply", "spotlessCheck").forEach { taskName ->
        named(taskName) {
            dependsOn(buildLogic.task(":$taskName"))
        }
    }
}

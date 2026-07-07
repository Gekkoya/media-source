pluginManagement {
    includeBuild("gradle/build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven(url = "https://www.jitpack.io")
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    versionCatalogs {
        create("symera") {
            from(files("gradle/symera.versions.toml"))
        }
    }
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://www.jitpack.io")
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "media-source"
include(":core")

File(rootDir, "lib").eachGradleModule { include("lib:${it.name}") }
File(rootDir, "lib-multisrc").eachGradleModule { include("lib-multisrc:${it.name}") }

loadAllIndividualExtensions()

fun loadAllIndividualExtensions() {
    File(rootDir, "src").eachDir { langDir ->
        langDir.eachGradleModule { extensionDir ->
            include("src:${langDir.name}:${extensionDir.name}")
        }
    }
}

fun File.eachGradleModule(block: (File) -> Unit) {
    eachDir { dir ->
        if (dir.resolve("build.gradle").isFile || dir.resolve("build.gradle.kts").isFile) {
            block(dir)
        }
    }
}

fun File.eachDir(block: (File) -> Unit) {
    val files = listFiles() ?: return
    for (file in files) {
        if (file.isDirectory && file.name != ".gradle" && file.name != "build") {
            block(file)
        }
    }
}

package symera.gradle.extensions

import org.gradle.api.Project

internal fun Project.configureAndroidMainSourceSet(
    manifestFile: Any? = null,
    sourceDir: Any = "src",
    resDir: Any? = null,
    assetsDir: Any? = null,
) {
    val main = androidSourceSet("main")
    main.directorySet("getJava")?.replaceWith(sourceDir)
    main.directorySet("getKotlin")?.replaceWith(sourceDir)
    resDir?.let { main.directorySet("getRes")?.replaceWith(it) }
    assetsDir?.let { main.directorySet("getAssets")?.replaceWith(it) }
    manifestFile?.let { main.sourceFile("getManifest")?.pointTo(it) }
}

private fun Project.androidSourceSet(name: String): Any {
    val android = extensions.getByName("android")
    val sourceSets = android.zeroArg("getSourceSets")
    return sourceSets.invokeOneArg("getByName", name)
}

private fun Any.directorySet(getter: String): Any? = maybeZeroArg(getter)

private fun Any.sourceFile(getter: String): Any? = maybeZeroArg(getter)

private fun Any.replaceWith(path: Any) {
    val setSrcDirs = javaClass.methods.firstOrNull { it.name == "setSrcDirs" && it.parameterCount == 1 }
    if (setSrcDirs != null) {
        setSrcDirs.invoke(this, listOf(path))
    } else {
        javaClass.methods.first { it.name == "srcDirs" }.invoke(this, arrayOf(path))
    }
}

private fun Any.pointTo(path: Any) {
    javaClass.methods.first { it.name == "srcFile" && it.parameterCount == 1 }.invoke(this, path)
}

private fun Any.zeroArg(name: String): Any = javaClass.methods.first { it.name == name && it.parameterCount == 0 }.invoke(this)

private fun Any.maybeZeroArg(name: String): Any? = javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(this)

private fun Any.invokeOneArg(name: String, value: Any): Any = javaClass.methods.first { it.name == name && it.parameterCount == 1 }.invoke(this, value)

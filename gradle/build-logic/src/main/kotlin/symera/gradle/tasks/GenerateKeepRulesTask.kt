package symera.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class GenerateKeepRulesTask : DefaultTask() {
    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    abstract val extClass: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun writeRules() {
        val packageName = applicationId.get()
        val className = extClass.get().removePrefix(".")
        val outputFile = outputDir.file("symera-extension.keep").get().asFile

        outputDir.get().asFile.deleteRecursively()
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            -keep class $packageName.$className { public <init>(); }
            -keep class org.symera.source.** { *; }
            -keep class kotlinx.serialization.** { *; }
            -keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
            """.trimIndent(),
        )
    }
}

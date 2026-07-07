package symera.gradle.extensions

import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependencyBundle
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.DependencyHandlerScope

internal fun DependencyHandlerScope.api(dependency: Provider<MinimalExternalModuleDependency>) {
    add("api", dependency)
}

internal fun DependencyHandlerScope.compileOnly(dependency: Provider<ExternalModuleDependencyBundle>) {
    add("compileOnly", dependency)
}

internal fun DependencyHandlerScope.implementation(dependency: Provider<MinimalExternalModuleDependency>) {
    add("implementation", dependency)
}

internal fun DependencyHandlerScope.implementation(project: Project) {
    add("implementation", project)
}

internal fun DependencyHandlerScope.implementation(project: ProjectDependency) {
    add("implementation", project)
}

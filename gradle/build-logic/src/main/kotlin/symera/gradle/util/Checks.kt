package symera.gradle.util

internal inline fun requireBuildCondition(value: Boolean, message: () -> String) {
    if (!value) {
        throw AssertionError(message())
    }
}

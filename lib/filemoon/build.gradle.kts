plugins {
    alias(symera.plugins.library)
}

dependencies {
    implementation(projects.lib.playlistutils)
    implementation(projects.lib.unpacker)
    implementation(projects.lib.webview)
}

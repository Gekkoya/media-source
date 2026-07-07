plugins {
    id("symera.plugins.library")
}

dependencies {
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:unpacker"))
    implementation(project(":lib:synchrony"))
}

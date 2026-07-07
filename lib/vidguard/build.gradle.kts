plugins {
    id("symera.plugins.library")
}

dependencies {
    implementation(project(":lib:playlistutils"))
    implementation(libs.rhino)
}

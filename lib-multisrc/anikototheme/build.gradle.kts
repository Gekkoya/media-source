plugins {
    id("symera.plugins.multisrc")
}

extra["baseVersionCode"] = 1

dependencies {
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:m3u8server"))
}

plugins {
    id("symera.plugins.multisrc")
}

extra["baseVersionCode"] = 5

dependencies {
    api(project(":lib:playlistutils"))
}

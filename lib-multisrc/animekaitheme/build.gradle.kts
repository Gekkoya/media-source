plugins {
    id("symera.plugins.multisrc")
}

extra["baseVersionCode"] = 4

dependencies {
    api(project(":lib:megaup"))
}

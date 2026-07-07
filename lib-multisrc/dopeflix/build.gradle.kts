plugins {
    id("symera.plugins.multisrc")
}

extra["baseVersionCode"] = 23

dependencies {
    api(project(":lib:dopeflix"))
}

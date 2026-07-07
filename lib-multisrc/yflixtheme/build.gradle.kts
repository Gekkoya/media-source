plugins {
    id("symera.plugins.multisrc")
}

extra["baseVersionCode"] = 2

dependencies {
    api(project(":lib:rapidshare"))
}

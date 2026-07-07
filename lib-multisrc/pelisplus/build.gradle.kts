plugins {
    id("symera.plugins.multisrc")
}

extra["baseVersionCode"] = 8

dependencies {
    api(project(":lib:vudeo"))
    api(project(":lib:uqload"))
    api(project(":lib:streamwish"))
    api(project(":lib:filemoon"))
    api(project(":lib:streamlare"))
    api(project(":lib:yourupload"))
    api(project(":lib:streamtape"))
    api(project(":lib:dood"))
    api(project(":lib:voe"))
    api(project(":lib:okru"))
    api(project(":lib:mp4upload"))
    api(project(":lib:mixdrop"))
    api(project(":lib:burstcloud"))
    api(project(":lib:fastream"))
    api(project(":lib:upstream"))
    api(project(":lib:vidhide"))
    api(project(":lib:streamsilk"))
    api(project(":lib:vidguard"))
    api(project(":lib:universal"))
}

package org.symera.mediasource.lib.upstream

import okhttp3.OkHttpClient
import org.symera.mediasource.lib.playlistutils.PlaylistUtils
import org.symera.mediasource.lib.unpacker.JsUnpacker
import org.symera.source.model.SStream
import org.symera.source.online.GET
import org.symera.source.online.asJsoup

class UpstreamExtractor(private val client: OkHttpClient) {
    fun streamsFromUrl(url: String, prefix: String = ""): List<SStream> = runCatching {
        val js = client.newCall(GET(url)).execute().asJsoup().selectFirst("script:containsData(eval)")?.data() ?: return@runCatching emptyList()
        val masterUrl = JsUnpacker.unpackAndCombine(js)?.substringAfter("{file:\"")?.substringBefore("\"")?.takeIf(String::isNotBlank)
            ?: return@runCatching emptyList()
        PlaylistUtils(client).extractFromHls(masterUrl, videoNameGen = { "${prefix}Upstream - $it" })
    }.getOrDefault(emptyList())

    fun videosFromUrl(url: String, prefix: String = ""): List<SStream> = streamsFromUrl(url, prefix)
}

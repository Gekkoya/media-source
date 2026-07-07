package org.symera.mediasource.lib.burstcloud

import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.symera.mediasource.core.bodyString
import org.symera.mediasource.core.parseAs
import org.symera.source.model.SStream
import org.symera.source.online.GET
import org.symera.source.online.POST
import org.symera.source.online.asJsoup

class BurstCloudExtractor(private val client: OkHttpClient) {
    fun streamFromUrl(url: String, headers: Headers, name: String = "BurstCloud", prefix: String = ""): List<SStream> {
        val newHeaders = headers.newBuilder().set("referer", BURSTCLOUD_URL).build()
        return runCatching {
            val document = client.newCall(GET(url, newHeaders)).execute().asJsoup()
            val videoId = document.selectFirst("div#player")?.attr("data-file-id")?.takeIf(String::isNotBlank) ?: return@runCatching emptyList()
            val formBody = FormBody.Builder().add("fileId", videoId).build()
            val jsonHeaders = headers.newBuilder().set("referer", document.location()).build()
            val jsonObj = client.newCall(POST("$BURSTCLOUD_URL/file/play-request/", jsonHeaders, formBody)).execute().bodyString().parseAs<BurstCloudDto>()
            val videoUrl = jsonObj.purchase.cdnUrl.takeIf(String::isNotEmpty) ?: return@runCatching emptyList()
            listOf(SStream(url = videoUrl, title = prefix + name, headers = newHeaders, initialized = true))
        }.getOrDefault(emptyList())
    }

    fun videoFromUrl(url: String, headers: Headers, name: String = "BurstCloud", prefix: String = ""): List<SStream> = streamFromUrl(url, headers, name, prefix)
}

@Serializable
data class BurstCloudDto(val purchase: Purchase)

@Serializable
data class Purchase(val cdnUrl: String)

private const val BURSTCLOUD_URL = "https://www.burstcloud.co"

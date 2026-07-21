package org.symera.mediasource.lib.yourupload

import okhttp3.Headers
import okhttp3.OkHttpClient
import org.symera.source.model.HttpHeader
import org.symera.source.model.MediaRequest
import org.symera.source.model.PlayableStream
import org.symera.source.model.SStream
import org.symera.source.online.GET
import org.symera.source.online.asJsoup

class YourUploadExtractor(private val client: OkHttpClient) {
    fun streamFromUrl(url: String, headers: Headers, name: String = "YourUpload", prefix: String = ""): List<SStream> {
        val newHeaders = headers.newBuilder().add("referer", "https://www.yourupload.com/").build()
        return runCatching {
            val document = client.newCall(GET(url, headers = newHeaders)).execute().asJsoup()
            val baseData = document.selectFirst("script:containsData(jwplayerOptions)")?.data()
            val basicUrl = baseData?.substringAfter("file: '")?.substringBefore("',")?.takeIf(String::isNotBlank)
            if (basicUrl == null) {
                emptyList()
            } else {
                listOf(
                    PlayableStream(
                        id = basicUrl,
                        title = prefix + name,
                        request = MediaRequest(uri = basicUrl, headers = newHeaders.toMultimap().flatMap { (name, values) -> values.map { HttpHeader(name, it) } }),
                    ),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun videoFromUrl(url: String, headers: Headers, name: String = "YourUpload", prefix: String = ""): List<SStream> = streamFromUrl(url, headers, name, prefix)
}

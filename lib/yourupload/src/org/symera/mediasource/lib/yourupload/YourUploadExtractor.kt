package org.symera.mediasource.lib.yourupload

import okhttp3.Headers
import okhttp3.OkHttpClient
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
            if (basicUrl == null) emptyList() else listOf(SStream(url = basicUrl, title = prefix + name, headers = newHeaders, initialized = true))
        }.getOrDefault(emptyList())
    }

    fun videoFromUrl(url: String, headers: Headers, name: String = "YourUpload", prefix: String = ""): List<SStream> = streamFromUrl(url, headers, name, prefix)
}

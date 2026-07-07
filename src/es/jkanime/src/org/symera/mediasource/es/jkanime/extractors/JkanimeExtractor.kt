package org.symera.mediasource.es.jkanime.extractors

import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.symera.mediasource.core.parseAs
import org.symera.source.model.SStream
import org.symera.source.online.GET
import org.symera.source.online.POST
import org.symera.source.online.asJsoup

class JkanimeExtractor(private val client: OkHttpClient) {
    fun getNozomiFromUrl(url: String, prefix: String = ""): List<SStream> {
        val dataKeyHeaders = Headers.Builder().add("Referer", url).build()
        val doc = client.newCall(GET(url, dataKeyHeaders)).execute().asJsoup()
        val dataKey = doc.select("form input[value]").attr("value")

        val gsplayBody = "data=$dataKey".toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())
        val location = client.newCall(POST("https://jkanime.net/gsplay/redirect_post.php", dataKeyHeaders, gsplayBody))
            .execute().request.url.toString()
        val postKey = location.substringAfter("player.html#")

        val nozomiBody = "v=$postKey".toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())
        val nozomiResponse = client.newCall(POST("https://jkanime.net/gsplay/api.php", body = nozomiBody)).execute()
        val nozomiUrl = nozomiResponse.body.string().parseAs<NozomiResponse>().file ?: return emptyList()

        return listOf(SStream(url = nozomiUrl, title = "${prefix}Nozomi", initialized = true))
    }

    fun parseStreamFromDpPlayer(response: Response, quality: String = ""): List<SStream> {
        val document = response.asJsoup()
        val streamUrl = document
            .selectFirst("""script:containsData(new DPlayer\({)""")
            ?.data()?.substringAfter("url: '")
            ?.substringBefore("'") ?: return emptyList()

        return listOf(SStream(url = streamUrl, title = quality, initialized = true))
    }

    fun getDesuFromUrl(url: String, prefix: String = ""): List<SStream> {
        val response = client.newCall(GET(url)).execute()
        return parseStreamFromDpPlayer(response, "${prefix}Desu")
    }

    fun getDesukaFromUrl(url: String, prefix: String = ""): List<SStream> {
        val response = client.newCall(GET(url)).execute()
        val contentType = response.header("Content-Type") ?: ""

        if (contentType.startsWith("video/")) {
            val realUrl = response.request.url.toString()
            return listOf(SStream(url = realUrl, title = "${prefix}Desuka", initialized = true))
        }
        return parseStreamFromDpPlayer(response, "${prefix}Desuka")
    }

    fun getMagiFromUrl(url: String, prefix: String = ""): List<SStream> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val videoUrl = document.selectFirst("""source[src*=".m3u8"]""")?.attr("src") ?: return emptyList()
        return listOf(SStream(url = videoUrl, title = "${prefix}Magi", initialized = true))
    }

    fun getMediafireFromUrl(url: String, prefix: String = ""): List<SStream> {
        val response = client.newCall(GET(url)).execute()
        val downloadUrl = response.asJsoup().selectFirst("a#downloadButton")?.attr("href")
        if (!downloadUrl.isNullOrBlank()) {
            return listOf(SStream(url = downloadUrl, title = "${prefix}MediaFire", initialized = true))
        }
        return emptyList()
    }

    @Serializable
    data class NozomiResponse(val file: String? = null)
}

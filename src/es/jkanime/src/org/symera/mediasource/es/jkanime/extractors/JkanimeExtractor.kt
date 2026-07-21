package org.symera.mediasource.es.jkanime.extractors

import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.symera.mediasource.core.parseAs
import org.symera.source.model.HttpHeader
import org.symera.source.model.MediaRequest
import org.symera.source.model.PlayableStream
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

        return listOf(PlayableStream(id = nozomiUrl, title = "${prefix}Nozomi", request = MediaRequest(nozomiUrl)))
    }

    fun parseStreamFromDpPlayer(response: Response, quality: String = ""): List<SStream> {
        val document = response.asJsoup()
        val streamUrl = document
            .selectFirst("""script:containsData(new DPlayer\({)""")
            ?.data()?.substringAfter("url: '")
            ?.substringBefore("'") ?: return emptyList()

        return listOf(PlayableStream(id = streamUrl, title = quality.ifBlank { "Jkanime" }, request = MediaRequest(streamUrl)))
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
            return listOf(PlayableStream(id = realUrl, title = "${prefix}Desuka", request = MediaRequest(realUrl)))
        }
        return parseStreamFromDpPlayer(response, "${prefix}Desuka")
    }

    fun getMagiFromUrl(url: String, prefix: String = ""): List<SStream> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val videoUrl = document.selectFirst("""source[src*=".m3u8"]""")?.attr("abs:src") ?: return emptyList()
        return listOf(
            PlayableStream(
                id = videoUrl,
                title = "${prefix}Magi",
                request = MediaRequest(videoUrl, headers = listOf(HttpHeader("Referer", url))),
            ),
        )
    }

    fun getMediafireFromUrl(url: String, prefix: String = ""): List<SStream> {
        val response = client.newCall(GET(url)).execute()
        val downloadUrl = response.asJsoup().selectFirst("a#downloadButton")?.attr("href")
        if (!downloadUrl.isNullOrBlank()) {
            return listOf(PlayableStream(id = downloadUrl, title = "${prefix}MediaFire", request = MediaRequest(downloadUrl)))
        }
        return emptyList()
    }

    @Serializable
    data class NozomiResponse(val file: String? = null)
}

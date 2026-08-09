package org.symera.mediasource.es.rojadirecta

import java.io.IOException
import java.net.URI
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.symera.source.iptv.IptvChannel
import org.symera.source.iptv.IptvChannelKind
import org.symera.source.iptv.IptvError
import org.symera.source.iptv.IptvInstant
import org.symera.source.iptv.IptvPlaybackRequest
import org.symera.source.iptv.IptvResult
import org.symera.source.iptv.IptvStreamProtocol

private const val ROOT_URL = "https://rojadirecta.st/"
private const val FALLBACK_CAPO_HOST = "capo8play.com"
private const val GROUP_ID = "deportes"
private val UNAVAILABLE_SLUGS = setOf(
    "dsports-plus-en-vivo",
    "espn-2-en-vivo",
    "espn-premium-argentina-en-vivo",
    "tyc-sports-en-vivo",
)

internal class CapoPlaybackResolver(
    private val client: OkHttpClient,
    private val userAgent: String,
) {
    suspend fun resolve(channel: IptvChannel): IptvResult<IptvPlaybackRequest> = try {
        val pageUrl = channel.attributes["pageUrl"]
            ?: return IptvResult.Failure(IptvError.InvalidConfiguration("Channel page URL is missing"))
        val channelHtml = fetch(pageUrl, ROOT_URL)
        val playerUrl = Regex("<iframe[^>]+src=[\\\"']([^\\\"']*playcapo[^\\\"']+)[\\\"']", RegexOption.IGNORE_CASE)
            .find(channelHtml)?.groupValues?.get(1)
            ?: return IptvResult.Failure(IptvError.Parse("Player iframe not found"))
        val playcapoHtml = fetch(playerUrl, pageUrl)
        val fid = Regex("fid\\s*=\\s*[\\\"']([^\\\"']+)").find(playcapoHtml)?.groupValues?.get(1)
            ?: return IptvResult.Failure(IptvError.Parse("Player identifier not found"))
        val capoHost = Regex("src=[\\\"']https?://([^/\\\"']+)/capo\\.js", RegexOption.IGNORE_CASE)
            .find(playcapoHtml)?.groupValues?.get(1)
            ?: FALLBACK_CAPO_HOST
        val capoUrl = "https://$capoHost/capo.php?player=desktop&live=$fid"
        val capoHtml = fetch(capoUrl, playerUrl)
        val streamUrl = extractStreamUrl(capoHtml, capoHost)
            ?: return IptvResult.Failure(IptvError.NotFound("HLS stream URL not found"))
        val expiresAt = Regex("[?&]expires=(\\d+)").find(streamUrl)?.groupValues?.get(1)?.toLongOrNull()
            ?.let { IptvInstant(it * 1_000L) }
        IptvResult.Success(
            IptvPlaybackRequest(
                uri = URI(streamUrl),
                protocol = IptvStreamProtocol.HLS,
                userAgent = userAgent,
                referrer = URI("https://$capoHost/"),
                expiresAt = expiresAt,
            ),
        )
    } catch (error: IOException) {
        IptvResult.Failure(IptvError.Network("RojaDirecta request failed", error))
    } catch (error: IllegalArgumentException) {
        IptvResult.Failure(IptvError.Parse("Invalid RojaDirecta response", cause = error))
    }

    suspend fun loadChannels(): IptvResult<List<IptvChannel>> = try {
        val html = fetch("${ROOT_URL}canales/", ROOT_URL)
        val rows = Regex(
            """<a\s+class=["']linkrow["'][^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        ).findAll(html).mapNotNull { match ->
            val href = match.groupValues[1]
            if (!href.contains("/canales/") || !href.endsWith("/")) return@mapNotNull null
            val row = match.groupValues[2]
            val slug = href.trimEnd('/').substringAfterLast('/')
            if (slug in UNAVAILABLE_SLUGS) return@mapNotNull null
            val name = Regex("""<span\s+class=["']nm["'][^>]*>(.*?)</span>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(row)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.trim()
                ?: return@mapNotNull null
            val pageUrl = URI(ROOT_URL).resolve(href).toString()
            IptvChannel(
                id = slug,
                name = name,
                kind = IptvChannelKind.TV,
                groupIds = listOf(GROUP_ID),
                attributes = mapOf("pageUrl" to pageUrl),
            )
        }.toList()
        if (rows.isEmpty()) {
            IptvResult.Failure(IptvError.Parse("RojaDirecta channel list is empty"))
        } else {
            IptvResult.Success(rows.distinctBy(IptvChannel::id))
        }
    } catch (error: IOException) {
        IptvResult.Failure(IptvError.Network("RojaDirecta channel list request failed", error))
    } catch (error: IllegalArgumentException) {
        IptvResult.Failure(IptvError.Parse("Invalid RojaDirecta channel list", cause = error))
    }

    private suspend fun fetch(url: String, referer: String): String = withContext(Dispatchers.IO) {
        val headers = Headers.Builder()
            .set("User-Agent", userAgent)
            .set("Referer", referer)
            .build()
        val request = Request.Builder().url(url).headers(headers).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            response.body.string()
        }
    }

    internal fun extractStreamUrl(html: String, capoHost: String = FALLBACK_CAPO_HOST): String? {
        val returnExpression = Regex(
            """return\(\s*(\[.*?\])""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
            .find(html)?.groupValues?.get(1)
        val pieces = returnExpression?.let { expression ->
            Regex("\"([^\"]*)\"").findAll(expression).joinToString("") { it.groupValues[1] }
        }
        val spanId = Regex("""getElementById\("([^\"]+)"\)""").find(html)?.groupValues?.get(1)
        val spanText = spanId?.let { id ->
            Regex("<span[^>]+id=[\\\"']$id[\\\"'][^>]*>(.*?)</span>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(html)?.groupValues?.get(1).orEmpty()
        }.orEmpty()
        val assembled = (pieces.orEmpty() + spanText).replace("\\/", "/")
        return Regex("https?://[^\\\"'\\s]+\\.m3u8(?:\\?[^\\\"'\\s<]+)?", RegexOption.IGNORE_CASE)
            .find(assembled)?.value
            ?: decodeMustave(html, capoHost)
    }

    private fun decodeMustave(html: String, capoHost: String): String? {
        val encoded = Regex("atob\\(\\s*[\\\"']([^\\\"']+)[\\\"']\\s*\\)").find(html)?.groupValues?.get(1)
            ?: return null
        return runCatching {
            val path = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
            URI("https://$capoHost/").resolve(path).toString()
        }.getOrNull()
    }
}

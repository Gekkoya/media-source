package org.symera.mediasource.lib.mixdrop

import android.util.Log
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.symera.mediasource.lib.unpacker.autoUnpacker
import org.symera.source.model.HttpHeader
import org.symera.source.model.MediaRequest
import org.symera.source.model.PlayableStream
import org.symera.source.model.SStream
import org.symera.source.model.SubtitleFormat
import org.symera.source.model.SubtitleTrack
import org.symera.source.online.GET
import org.symera.source.online.asJsoup
import java.net.URI
import java.net.URLDecoder

class MixDropExtractor(private val client: OkHttpClient) {
    fun streamsFromUrl(
        url: String,
        lang: String = "",
        prefix: String = "",
        externalSubs: List<SubtitleTrack> = emptyList(),
        referer: String = DEFAULT_REFERER,
    ): List<SStream> {
        val headers = Headers.headersOf(
            "Referer",
            referer,
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36",
        )
        val doc = client.newCall(GET(url, headers)).execute().asJsoup()
        val packedScript = doc.selectFirst("script:containsData(eval):containsData(MDCore)")
            ?.data()
        if (packedScript == null) {
            Log.w(TAG, "failure host=${safeHost(url)} category=missing_script")
            return emptyList()
        }
        val unpacked = autoUnpacker(packedScript)
        if (unpacked.isNullOrBlank()) {
            Log.w(TAG, "failure host=${safeHost(url)} category=empty_payload")
            return emptyList()
        }

        val rawVideoPath = Regex("Core\\.wurl\\s*=\\s*['\"]([^'\"]+)")
            .find(unpacked)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
            .replace("\\/", "/")
            .trim()
        val rawSubtitle = Regex("Core\\.remotesub\\s*=\\s*['\"]([^'\"]*)")
            .find(unpacked)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
            .replace("\\/", "/")
            .trim()
        Log.d(TAG, "host=${safeHost(url)} protocol=${protocol(rawVideoPath)}")
        val videoUrl = normalizeMediaUrl(rawVideoPath, url)
        if (videoUrl == null) {
            Log.w(TAG, "failure host=${safeHost(url)} category=invalid_media_candidate")
            return emptyList()
        }
        val subs = rawSubtitle
            .takeIf(String::isNotBlank)
            ?.let { encodedUrl ->
                val decodedUrl = runCatching { URLDecoder.decode(encodedUrl, "utf-8") }.getOrNull()
                val subtitleUrl = decodedUrl?.let { normalizeMediaUrl(it, url, allowRelative = true) }
                val format = subtitleUrl?.let(::subtitleFormat) ?: SubtitleFormat.UNKNOWN
                Log.d(TAG, "host=${safeHost(url)} protocol=${protocol(subtitleUrl)}")
                if (subtitleUrl != null && format != SubtitleFormat.UNKNOWN) {
                    listOf(
                        SubtitleTrack(
                            id = subtitleUrl,
                            request = MediaRequest(uri = subtitleUrl, headers = headers.toMultimap().flatMap { (name, values) -> values.map { HttpHeader(name, it) } }),
                            language = "sub",
                            format = format,
                        ),
                    )
                } else {
                    emptyList()
                }
            }
            ?: emptyList()

        val quality = buildString {
            append("${prefix}MixDrop")
            if (lang.isNotBlank()) append("($lang)")
        }

        return listOf(
            PlayableStream(
                id = videoUrl,
                title = quality,
                request = MediaRequest(uri = videoUrl, headers = headers.toMultimap().flatMap { (name, values) -> values.map { HttpHeader(name, it) } }),
                subtitleTracks = subs + externalSubs,
            ),
        )
    }

    private companion object {
        const val TAG = "MixDropExtractor"

        fun safeHost(value: String?): String = runCatching { value?.let(::URI)?.host }.getOrNull() ?: "unknown"

        fun protocol(value: String?): String = runCatching { value?.let(::URI)?.scheme }.getOrNull() ?: "unknown"
    }
}

private fun normalizeMediaUrl(value: String, pageUrl: String, allowRelative: Boolean = false): String? {
    val candidate = when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://") || value.startsWith("https://") -> value
        allowRelative -> URI(pageUrl).resolve(value).toString()
        else -> return null
    }
    return candidate.takeIf { it.startsWith("http://") || it.startsWith("https://") }
}

private fun subtitleFormat(url: String): SubtitleFormat = when (URI(url).path.substringAfterLast('.', "").lowercase()) {
    "vtt" -> SubtitleFormat.WEBVTT
    "ttml", "xml" -> SubtitleFormat.TTML
    "srt" -> SubtitleFormat.SUBRIP
    "ass", "ssa" -> SubtitleFormat.SSA_ASS
    else -> SubtitleFormat.UNKNOWN
}

private const val DEFAULT_REFERER = "https://mixdrop.co/"

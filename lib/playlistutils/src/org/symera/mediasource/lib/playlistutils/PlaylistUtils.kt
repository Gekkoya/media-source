package org.symera.mediasource.lib.playlistutils

import android.net.Uri
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.symera.mediasource.core.UrlUtils
import org.symera.mediasource.core.bodyString
import org.symera.mediasource.core.commonEmptyHeaders
import org.symera.mediasource.core.parallelMapNotNullBlocking
import org.symera.mediasource.core.useAsJsoup
import org.symera.source.model.AudioTrack
import org.symera.source.model.HttpHeader
import org.symera.source.model.MediaRequest
import org.symera.source.model.PlayableStream
import org.symera.source.model.SStream
import org.symera.source.model.StreamHints
import org.symera.source.model.SubtitleTrack
import org.symera.source.network.awaitSuccess
import org.symera.source.online.GET
import java.io.File
import kotlin.math.abs

class PlaylistUtils(private val client: OkHttpClient, private val headers: Headers = commonEmptyHeaders) {
    fun extractFromHls(
        playlistUrl: String,
        referer: String = playlistUrl.toDefaultReferer(),
        masterHeaders: Headers,
        videoHeaders: Headers,
        videoNameGen: (String) -> String = { quality -> quality },
        subtitleList: List<SubtitleTrack> = emptyList(),
        audioList: List<AudioTrack> = emptyList(),
        toStandardQuality: (String) -> String = { quality -> stnQuality(quality) },
    ): List<SStream> = extractFromHls(
        playlistUrl,
        referer,
        { _, _ -> masterHeaders },
        { _, _, _ -> videoHeaders },
        videoNameGen,
        subtitleList,
        audioList,
        toStandardQuality,
    )

    fun extractFromHls(
        playlistUrl: String,
        referer: String = playlistUrl.toDefaultReferer(),
        masterHeadersGen: (Headers, String) -> Headers = ::generateMasterHeaders,
        videoHeadersGen: (Headers, String, String) -> Headers = { baseHeaders, refererValue, _ ->
            generateMasterHeaders(baseHeaders, refererValue)
        },
        videoNameGen: (String) -> String = { quality -> quality },
        subtitleList: List<SubtitleTrack> = emptyList(),
        audioList: List<AudioTrack> = emptyList(),
        toStandardQuality: (String) -> String = { quality -> stnQuality(quality) },
    ): List<SStream> {
        val masterHeaders = masterHeadersGen(headers, referer)
        val masterPlaylist = client.newCall(GET(playlistUrl, masterHeaders)).execute().bodyString()

        if (PLAYLIST_SEPARATOR !in masterPlaylist) {
            return listOf(
                PlayableStream(
                    id = playlistUrl,
                    title = videoNameGen("Video"),
                    request = MediaRequest(uri = playlistUrl, headers = masterHeaders.toMultimap().flatMap { (name, values) -> values.map { HttpHeader(name, it) } }),
                    subtitleTracks = subtitleList,
                    audioTracks = audioList,
                ),
            )
        }

        val subtitleTracks = subtitleList + SUBTITLE_REGEX.findAll(masterPlaylist).mapNotNull {
            SubtitleTrack(
                id = it.groupValues[2],
                request = MediaRequest(uri = UrlUtils.fixUrl(it.groupValues[2], playlistUrl) ?: return@mapNotNull null),
                language = it.groupValues[1],
            )
        }.toList()

        val audioTracks = audioList + AUDIO_REGEX.findAll(masterPlaylist).mapNotNull {
            AudioTrack(
                id = it.groupValues[2],
                request = MediaRequest(uri = UrlUtils.fixUrl(it.groupValues[2], playlistUrl) ?: return@mapNotNull null),
                label = it.groupValues[1],
            )
        }.toList()

        return masterPlaylist.substringAfter(PLAYLIST_SEPARATOR).split(PLAYLIST_SEPARATOR).mapNotNull { stream ->
            val codec = CODECS_REGEX.find(stream)?.groupValues?.get(1)
            if (!codec.isNullOrBlank()) {
                val codecs = codec.split(',')
                if (codecs.all { it.startsWith("mp4a") }) return@mapNotNull null
            }

            val resolution = RESOLUTION_REGEX.find(stream)
                ?.groupValues?.get(1)
                ?.let { resolution ->
                    val standardQuality = QUALITY_REGEX.find(resolution)
                        ?.groupValues?.get(1)
                        ?.let { toStandardQuality(it) }
                    if (!standardQuality.isNullOrBlank()) "$standardQuality ($resolution)" else resolution
                }
            val bandwidth = BANDWIDTH_REGEX.find(stream)?.groupValues?.get(1)?.toLongOrNull()
            val bandwidthFormatted = bandwidth?.let(::formatBytes)
            val streamName = listOfNotNull(resolution, bandwidthFormatted).joinToString(" - ").takeIf { it.isNotBlank() } ?: "Video"
            val videoUrl = stream.substringAfter("\n").substringBefore("\n").let { url ->
                UrlUtils.fixUrl(url, playlistUrl)?.trimEnd()
            } ?: return@mapNotNull null

            bandwidth to PlayableStream(
                id = videoUrl,
                title = videoNameGen(streamName),
                request = MediaRequest(
                    uri = videoUrl,
                    headers = videoHeadersGen(headers, referer, videoUrl).toMultimap().flatMap { (name, values) -> values.map { HttpHeader(name, it) } },
                ),
                hints = StreamHints(
                    height = QUALITY_REGEX.find(resolution.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull(),
                    bitrateBitsPerSecond = bandwidth,
                ),
                subtitleTracks = subtitleTracks,
                audioTracks = audioTracks,
            )
        }
            .sortedByDescending { (bandwidth, _) -> bandwidth ?: 0L }
            .map { (_, video) -> video }
    }

    fun generateMasterHeaders(baseHeaders: Headers, referer: String): Headers = baseHeaders.newBuilder().apply {
        set("Accept", "*/*")
        if (referer.isNotEmpty()) {
            set("Origin", "https://${referer.toHttpUrl().host}")
            set("Referer", referer)
        }
    }.build()

    fun extractFromDash(
        mpdUrl: String,
        videoNameGen: (String) -> String,
        mpdHeaders: Headers,
        videoHeaders: Headers,
        referer: String = mpdUrl.toDefaultReferer(),
        subtitleList: List<SubtitleTrack> = emptyList(),
        audioList: List<AudioTrack> = emptyList(),
        toStandardQuality: (String) -> String = { quality -> stnQuality(quality) },
    ): List<SStream> = extractFromDash(
        mpdUrl,
        { videoRes, bandwidth -> videoNameGen(videoRes) + " - ${formatBytes(bandwidth.toLongOrNull())}" },
        referer,
        { _, _ -> mpdHeaders },
        { _, _, _ -> videoHeaders },
        subtitleList,
        audioList,
        toStandardQuality,
    )

    fun extractFromDash(
        mpdUrl: String,
        videoNameGen: (String) -> String,
        referer: String = mpdUrl.toDefaultReferer(),
        mpdHeadersGen: (Headers, String) -> Headers = ::generateMasterHeaders,
        videoHeadersGen: (Headers, String, String) -> Headers = { baseHeaders, refererValue, _ ->
            generateMasterHeaders(baseHeaders, refererValue)
        },
        subtitleList: List<SubtitleTrack> = emptyList(),
        audioList: List<AudioTrack> = emptyList(),
        toStandardQuality: (String) -> String = { quality -> stnQuality(quality) },
    ): List<SStream> = extractFromDash(
        mpdUrl,
        { videoRes, bandwidth -> videoNameGen(videoRes) + " - ${formatBytes(bandwidth.toLongOrNull())}" },
        referer,
        mpdHeadersGen,
        videoHeadersGen,
        subtitleList,
        audioList,
        toStandardQuality,
    )

    fun extractFromDash(
        mpdUrl: String,
        videoNameGen: (String, String) -> String,
        referer: String = mpdUrl.toDefaultReferer(),
        mpdHeadersGen: (Headers, String) -> Headers = ::generateMasterHeaders,
        videoHeadersGen: (Headers, String, String) -> Headers = { baseHeaders, refererValue, _ ->
            generateMasterHeaders(baseHeaders, refererValue)
        },
        subtitleList: List<SubtitleTrack> = emptyList(),
        audioList: List<AudioTrack> = emptyList(),
        toStandardQuality: (String) -> String = { quality -> stnQuality(quality) },
    ): List<SStream> {
        val mpdHeaders = mpdHeadersGen(headers, referer)
        val doc = client.newCall(GET(mpdUrl, mpdHeaders)).execute().useAsJsoup()

        val audioTracks = audioList + doc.select("Representation[mimetype~=audio]").map { audioSrc ->
            val bandwidth = audioSrc.attr("bandwidth").toLongOrNull()
            AudioTrack(id = audioSrc.text(), request = MediaRequest(uri = audioSrc.text()), label = formatBytes(bandwidth))
        }

        return doc.select("Representation[mimetype~=video]").map { videoSrc ->
            val bandwidth = videoSrc.attr("bandwidth")
            val res = videoSrc.attr("height").let(toStandardQuality).let { "$it (${videoSrc.attr("width")}x${videoSrc.attr("height")})" }
            val videoUrl = videoSrc.text()
            PlayableStream(
                id = videoUrl,
                title = videoNameGen(res, bandwidth),
                request = MediaRequest(
                    uri = videoUrl,
                    headers = videoHeadersGen(headers, referer, videoUrl).toMultimap().flatMap { (name, values) -> values.map { HttpHeader(name, it) } },
                ),
                hints = StreamHints(
                    height = videoSrc.attr("height").toIntOrNull(),
                    bitrateBitsPerSecond = bandwidth.toLongOrNull(),
                ),
                audioTracks = audioTracks,
                subtitleTracks = subtitleList,
            )
        }
    }

    private fun formatBytes(bytes: Long?): String = when {
        bytes == null -> ""
        bytes >= 1_000_000_000 -> "%.2f GB/s".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.2f MB/s".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.2f KB/s".format(bytes / 1_000.0)
        bytes > 1 -> "$bytes bytes/s"
        bytes == 1L -> "$bytes byte/s"
        else -> ""
    }

    private fun String.toDefaultReferer(): String = try {
        toHttpUrl().run { "$scheme://$host/" }
    } catch (_: IllegalArgumentException) {
        ""
    }

    private fun stnQuality(quality: String): String {
        val intQuality = quality.trim().toIntOrNull() ?: return quality
        val result = STANDARD_QUALITIES.minByOrNull { abs(it - intQuality) } ?: intQuality
        return "${result}p"
    }

    private fun cleanSubtitleData(matchResult: MatchResult): String {
        val lineCount = matchResult.groupValues[1].count { it == '\n' }
        return "\n" + "&nbsp;\n".repeat(lineCount - 1)
    }

    fun fixSubtitles(subtitleList: List<SubtitleTrack>): List<SubtitleTrack> = subtitleList.parallelMapNotNullBlocking {
        runCatching {
            val subData = client.awaitSuccess(GET(it.request.uri)).bodyString()
            val file = File.createTempFile("subs", "vtt").also(File::deleteOnExit)
            file.writeText(FIX_SUBTITLE_REGEX.replace(subData, ::cleanSubtitleData))
            val uri = Uri.fromFile(file)
            SubtitleTrack(id = uri.toString(), request = MediaRequest(uri = uri.toString()), language = it.language, label = it.label)
        }.getOrNull()
    }

    companion object {
        private val FIX_SUBTITLE_REGEX = Regex("""$(\n{2,})(?!(?:\d+:)*\d+(?:\.\d+)?\s-+>\s(?:\d+:)*\d+(?:\.\d+)?)""", RegexOption.MULTILINE)
        private const val PLAYLIST_SEPARATOR = "#EXT-X-STREAM-INF:"
        private val SUBTITLE_REGEX by lazy { Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES.*?NAME="(.*?)".*?URI="(.*?)"""") }
        private val AUDIO_REGEX by lazy { Regex("""#EXT-X-MEDIA:TYPE=AUDIO.*?NAME="(.*?)".*?URI="(.*?)"""") }
        private val CODECS_REGEX by lazy { Regex("""CODECS="([^"]+)"""") }
        private val RESOLUTION_REGEX by lazy { Regex("""RESOLUTION=([xX\d]+)""") }
        private val QUALITY_REGEX by lazy { Regex("""[xX](\d+)""") }
        private val BANDWIDTH_REGEX by lazy { Regex("""BANDWIDTH=(\d+)""") }
        private val STANDARD_QUALITIES = listOf(144, 240, 360, 480, 720, 1080, 1440, 2160)
    }
}

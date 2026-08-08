package org.symera.mediasource.lib.filemoon

import android.util.Base64
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.symera.mediasource.core.bodyString
import org.symera.mediasource.core.parseAs
import org.symera.mediasource.lib.playlistutils.PlaylistUtils
import org.symera.mediasource.lib.unpacker.JsUnpacker
import org.symera.mediasource.lib.webview.WebViewMediaResolver
import org.symera.source.model.SStream
import org.symera.source.network.MediaBrowserFactory
import org.symera.source.online.GET
import org.symera.source.online.POST
import org.symera.source.online.asJsoup
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class FilemoonExtractor(
    private val client: OkHttpClient,
    browserFactory: MediaBrowserFactory? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val playlistUtils by lazy { PlaylistUtils(client) }
    private val browserResolver = browserFactory?.let(::WebViewMediaResolver)

    suspend fun streamsFromUrl(url: String, prefix: String = "Filemoon - ", headers: Headers? = null): List<SStream> {
        return try {
            val httpUrl = url.toHttpUrl()
            val host = httpUrl.host
            val mediaId = if (httpUrl.pathSegments.size > 1 && httpUrl.pathSegments[0] == "e") {
                httpUrl.pathSegments[1]
            } else {
                httpUrl.pathSegments.lastOrNull { it.isNotEmpty() } ?: return emptyList()
            }

            val userAgent = headers?.get("User-Agent")
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

            val embedUrl = client.newCall(GET("https://$host/api/videos/$mediaId/embed/details"))
                .execute().bodyString()
                .substringAfter("embed_frame_url", "")
                .substringAfter(":")
                .substringAfter('"')
                .substringBefore('"')

            if (embedUrl.isBlank()) return fallbackStreamsFromUrl(url, prefix, headers)

            val embedHost = embedUrl.toHttpUrl().host
            val playbackHeaders = (headers?.newBuilder() ?: Headers.Builder()).apply {
                set("Referer", embedUrl)
                set("X-Embed-Origin", host)
                set("X-Embed-Parent", url.encodeUrlPath())
                set("X-Embed-Referer", url)
                set("Accept", "*/*")
                set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
                set("Cache-Control", "no-cache")
                set("Pragma", "no-cache")
                set("Priority", "u=1, i")
                set("Sec-Fetch-Dest", "empty")
                set("Sec-Fetch-Mode", "cors")
                set("Sec-Fetch-Site", "same-origin")
                set("Sec-Fetch-Storage-Access", "active")
                set("User-Agent", userAgent)
            }.build()

            val apiUrl = "https://$embedHost/api/videos/$mediaId/embed/playback"
            val playbackJson = client.newCall(GET(apiUrl, playbackHeaders)).execute().parseAs<PlaybackResponse>()

            val finalSources = when {
                !playbackJson.sources.isNullOrEmpty() -> playbackJson.sources
                playbackJson.playback != null -> decrypt(playbackJson.playback).parseAs<PlaybackResponse>().sources
                else -> null
            }

            if (finalSources.isNullOrEmpty()) {
                val challenged = challengeFlow(mediaId, playbackHeaders, "https://$embedHost", userAgent, url)
                val challengedSources = challenged?.let(::extractSources)
                if (!challengedSources.isNullOrEmpty()) {
                    return challengedSources.flatMap { source ->
                        val streamUrl = source.url ?: source.file ?: return@flatMap emptyList<SStream>()
                        playlistUtils.extractFromHls(streamUrl, masterHeaders = playbackHeaders, videoHeaders = playbackHeaders) { quality -> "$prefix$quality" }
                    }
                }
                return fallbackStreamsFromUrl(url, prefix, headers)
            }

            val videoHeaders = (headers?.newBuilder() ?: Headers.Builder()).apply {
                set("Referer", "https://$host/")
                set("User-Agent", userAgent)
                removeAll("Origin")
            }.build()

            finalSources.flatMap { source ->
                val streamUrl = source.url ?: source.file ?: return@flatMap emptyList<SStream>()
                val quality = source.label ?: "Unknown"

                playlistUtils.extractFromHls(
                    streamUrl,
                    masterHeaders = videoHeaders,
                    videoHeaders = videoHeaders,
                    videoNameGen = { "$prefix${it.replace("Video", quality)}p" },
                )
            }
        } catch (e: Exception) {
            Log.e("FilemoonExtractor", "failure host=${url.safeHost()} category=${e.javaClass.simpleName}")
            fallbackStreamsFromUrl(url, prefix, headers)
        }
    }

    private suspend fun fallbackStreamsFromUrl(url: String, prefix: String, headers: Headers?): List<SStream> {
        rootApiStreamsFromUrl(url, prefix, headers).takeIf(List<SStream>::isNotEmpty)?.let { return it }
        legacyStreamsFromUrl(url, prefix, headers).takeIf(List<SStream>::isNotEmpty)?.let { return it }
        val request = browserResolver?.let {
            runCatching {
                it.resolve(
                    entryUrl = url,
                    headers = headers ?: Headers.headersOf("Referer", url),
                )
            }.getOrNull()
        } ?: return emptyList()
        val playbackHeaders = Headers.Builder().apply {
            request.headers.forEach { header -> set(header.name, header.value) }
        }.build()
        return playlistUtils.extractFromHls(
            request.uri,
            masterHeaders = playbackHeaders,
            videoHeaders = playbackHeaders,
            videoNameGen = { "$prefix${it.replace("Video", "Filemoon")}" },
        )
    }

    private suspend fun rootApiStreamsFromUrl(url: String, prefix: String, headers: Headers?): List<SStream> = runCatching {
        val httpUrl = url.toHttpUrl()
        val host = httpUrl.host
        val mediaId = httpUrl.pathSegments.lastOrNull { it.isNotEmpty() } ?: return@runCatching emptyList()
        val requestHeaders = (headers?.newBuilder() ?: Headers.Builder()).apply {
            set("Referer", url.substringBefore("?"))
            set("Accept", "application/json")
        }.build()
        val playback = client.newCall(GET("https://$host/api/videos/$mediaId/", requestHeaders))
            .execute()
            .parseAs<PlaybackResponse>()
        val decrypted = playback.playback?.let(::decrypt)?.parseAs<PlaybackResponse>() ?: return@runCatching emptyList()
        val videoHeaders = headers ?: Headers.headersOf("Referer", "https://$host/")
        decrypted.sources.orEmpty().flatMap { source ->
            val streamUrl = source.url ?: source.file ?: return@flatMap emptyList<SStream>()
            playlistUtils.extractFromHls(
                streamUrl,
                masterHeaders = videoHeaders,
                videoHeaders = videoHeaders,
                videoNameGen = { "$prefix${it.replace("Video", source.label ?: "Filemoon")}" },
            )
        }
    }.getOrDefault(emptyList())

    private fun legacyStreamsFromUrl(url: String, prefix: String, headers: Headers?): List<SStream> = runCatching {
        val pageHeaders = headers ?: Headers.headersOf("Referer", url)
        val scripts = client.newCall(GET(url, pageHeaders)).execute().asJsoup().select("script").map { it.data() }
        val payload = scripts.firstNotNullOfOrNull { script ->
            val source = if (JsUnpacker.detect(script)) JsUnpacker.unpackAndCombine(script).orEmpty() else script
            if (source.contains("m3u8", ignoreCase = true)) source else null
        } ?: return@runCatching emptyList()
        val streamUrl = Regex("(?:file|src)\\s*[:=]\\s*['\"]([^'\"]+\\.m3u8[^'\"]*)")
            .find(payload)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { if (it.startsWith("//")) "https:$it" else it }
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return@runCatching emptyList()
        playlistUtils.extractFromHls(
            streamUrl,
            masterHeaders = pageHeaders,
            videoHeaders = pageHeaders,
            videoNameGen = { "$prefix${it.replace("Video", "Filemoon")}" },
        )
    }.getOrDefault(emptyList())

    private fun decrypt(input: PlaybackData): String {
        val keyBytes = if (input.version == null) {
            input.key_parts.map { decodeBase64Url(it) }.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        } else {
            val version = input.version.toIntOrNull() ?: 1
            val selected = listOf(input.key_parts[version - 1], input.key_parts[input.key_parts.size - version])
            selected.map { decodeBase64Url(it) }.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        }
        val ivBytes = decodeBase64Url(input.iv)
        val payloadBytes = decodeBase64Url(input.payload)

        val secretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, ivBytes)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        val decryptedBytes = cipher.doFinal(payloadBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    private fun extractSources(data: PlaybackResponse): List<VideoSource>? = when {
        !data.sources.isNullOrEmpty() -> data.sources
        data.playback != null -> runCatching { decrypt(data.playback).parseAs<PlaybackResponse>().sources }.getOrNull()
        else -> null
    }

    private fun challengeFlow(mediaId: String, headers: Headers, origin: String, userAgent: String, pageUrl: String): PlaybackResponse? = runCatching {
        val challenge = client.newCall(POST("$origin/api/videos/access/challenge", headers, "{}".toRequestBody("application/json".toMediaType())))
            .execute().parseAs<ChallengeResponse>()
        val (privateKey, publicKey) = generateEcKeyPair()
        val clientFingerprint = fingerprint(userAgent)
        val attest = AttestRequest("", "", challenge.challengeId, challenge.nonce, signNonce(privateKey, challenge.nonce), publicKey, clientFingerprint, emptyMap(), mapOf("entropy" to "low"))
        val identity = client.newCall(POST("$origin/api/videos/access/attest", headers, json.encodeToString(attest).toRequestBody("application/json".toMediaType())))
            .execute().parseAs<AttestResponse>()
        val fingerprint = FingerprintData(identity.token, identity.viewerId, identity.deviceId, identity.confidence)
        val captchaHeaders = headers.newBuilder().set("Cookie", "byse_viewer_id=${identity.viewerId}; byse_device_id=${identity.deviceId}").build()
        val captcha = client.newCall(POST("$origin/api/videos/$mediaId/embed/captcha", captchaHeaders, json.encodeToString(FingerprintPayload(fingerprint)).toRequestBody("application/json".toMediaType())))
            .execute().parseAs<CaptchaResponse>()
        val verify = VerifyRequest(captcha.powToken, solvePow(captcha.powNonce, captcha.powDifficulty), fingerprint)
        val verified = client.newCall(POST("$origin/api/videos/$mediaId/embed/captcha/verify", captchaHeaders, json.encodeToString(verify).toRequestBody("application/json".toMediaType())))
            .execute().parseAs<VerifyResponse>()
        if (verified.status != "ok" || verified.token.isNullOrBlank()) return@runCatching null
        val playbackHeaders = captchaHeaders.newBuilder().set("X-Captcha-Token", verified.token).build()
        client.newCall(POST("$origin/api/videos/$mediaId/embed/playback", playbackHeaders, json.encodeToString(FingerprintPayload(fingerprint)).toRequestBody("application/json".toMediaType())))
            .execute().parseAs<PlaybackResponse>()
    }.onFailure { error ->
        Log.w("FilemoonExtractor", "attestation host=${pageUrl.safeHost()} category=${error.javaClass.simpleName}")
    }.getOrNull()

    private fun generateEcKeyPair(): Pair<java.security.PrivateKey, EcJwk> {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), SecureRandom()) }
        val pair = generator.generateKeyPair()
        val publicKey = pair.public as ECPublicKey
        fun coordinate(value: java.math.BigInteger): String {
            val bytes = value.toByteArray()
            val fixed = if (bytes.size < 32) ByteArray(32 - bytes.size) + bytes else bytes.takeLast(32).toByteArray()
            return Base64.encodeToString(fixed, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }
        return pair.private to EcJwk("ES256", "P-256", true, listOf("verify"), "EC", coordinate(publicKey.w.affineX), coordinate(publicKey.w.affineY))
    }

    private fun String.safeHost(): String = runCatching { toHttpUrl().host }.getOrDefault("unknown")

    private fun signNonce(privateKey: java.security.PrivateKey, nonce: String): String = Signature.getInstance("SHA256withECDSA").run {
        initSign(privateKey)
        update(nonce.toByteArray(StandardCharsets.UTF_8))
        Base64.encodeToString(sign(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun fingerprint(userAgent: String) = ClientFingerprint(
        userAgent, 1, 1920, 1080, 24, listOf("en-US", "en"), "America/New_York", 8, 0,
        "Google Inc. (Intel)", "ANGLE (Intel, Intel(R) UHD Graphics 630, OpenGL 4.5)",
        randomHash(), randomHash(), randomHash(), randomHash(), randomHash(), "ai0ao0vi0", "fine,hover",
        mapOf("vendor" to "", "appVersion" to "5.0 (X11)"),
    )

    private fun randomHash(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }

    private fun solvePow(nonce: String, difficulty: Int): String {
        val prefix = ("$nonce:").toByteArray(Charsets.ISO_8859_1)
        val mask = 0xFFFFFFFFL
        fun rotl(value: Long, shift: Int) = ((value shl shift) or (value ushr (32 - shift))) and mask
        val buffer = LongArray(512)
        for (counter in 0..200000) {
            var s0 = 1779033703L
            var s1 = 3144134277L
            var s2 = 1013904242L
            var s3 = 2773480762L
            for (b in prefix + counter.toString().toByteArray(Charsets.ISO_8859_1)) {
                s0 = (s0 + (b.toInt() and 255)) and mask
                s0 = rotl(s0, 7)
                s0 = (s0 + s1) and mask
                s3 = rotl(s3 xor s0, 16)
                s2 = (s2 + s3) and mask
                s1 = rotl(s1 xor s2, 12)
                s0 = (s0 + s1) and mask
                s3 = rotl(s3 xor s0, 8)
                s2 = (s2 + s3) and mask
                s1 = rotl(s1 xor s2, 7)
            }
            repeat(8) {
                s0 = (s0 + s1) and mask
                s3 = rotl(s3 xor s0, 16)
                s2 = (s2 + s3) and mask
                s1 = rotl(s1 xor s2, 12)
                s0 = (s0 + s1) and mask
                s3 = rotl(s3 xor s0, 8)
                s2 = (s2 + s3) and mask
                s1 = rotl(s1 xor s2, 7)
            }
            for (i in buffer.indices) {
                s0 = (s0 + s1) and mask
                s3 = rotl(s3 xor s0, 16)
                s2 = (s2 + s3) and mask
                s1 = rotl(s1 xor s2, 12)
                s0 = (s0 + s1) and mask
                s3 = rotl(s3 xor s0, 8)
                s2 = (s2 + s3) and mask
                s1 = rotl(s1 xor s2, 7)
                buffer[i] = (s0 xor s2) and mask
            }
            repeat(2) {
                for (i in buffer.indices) {
                    val a = (buffer[i] and 511).toInt()
                    var c = (buffer[i] + buffer[a]) and mask
                    c = rotl(c, 13)
                    c = (c xor ((buffer[(i + 1) and 511] * 2654435761L) and mask)) and mask
                    buffer[i] = c
                    s0 = (s0 xor c) and mask
                }
            }
            var out = s0
            for (i in 0 until 64) {
                val d = buffer[i]
                out = (out + d) and mask
                out = rotl(out, 5)
                out = (out xor ((d * 2246822519L) and mask)) and mask
            }
            if ((out xor s2).countLeadingZeroBits() >= difficulty) return counter.toString()
        }
        error("PoW solution not found")
    }

    private fun decodeBase64Url(input: String): ByteArray {
        val base64 = input.replace('-', '+').replace('_', '/')
        val padding = when (base64.length % 4) {
            2 -> "=="
            3 -> "="
            else -> ""
        }
        return Base64.decode(base64 + padding, Base64.DEFAULT)
    }

    @Serializable
    data class PlaybackResponse(
        val sources: List<VideoSource>? = null,
        val playback: PlaybackData? = null,
    )

    @Serializable
    data class PlaybackData(
        val iv: String,
        val key_parts: List<String>,
        val payload: String,
        val version: String? = null,
    )

    @Serializable
    data class ChallengeResponse(@SerialName("challenge_id") val challengeId: String, val nonce: String)

    @Serializable
    data class AttestRequest(
        @SerialName("viewer_id") val viewerId: String,
        @SerialName("device_id") val deviceId: String,
        @SerialName("challenge_id") val challengeId: String,
        val nonce: String,
        val signature: String,
        @SerialName("public_key") val publicKey: EcJwk,
        val client: ClientFingerprint,
        val storage: Map<String, String>,
        val attributes: Map<String, String>,
    )

    @Serializable
    data class EcJwk(val alg: String, val crv: String, val ext: Boolean, @SerialName("key_ops") val keyOps: List<String>, val kty: String, val x: String, val y: String)

    @Serializable
    data class ClientFingerprint(
        @SerialName("user_agent") val userAgent: String,
        @SerialName("pixel_ratio") val pixelRatio: Int,
        @SerialName("screen_width") val screenWidth: Int,
        @SerialName("screen_height") val screenHeight: Int,
        @SerialName("color_depth") val colorDepth: Int,
        val languages: List<String>,
        val timezone: String,
        @SerialName("hardware_concurrency") val hardwareConcurrency: Int,
        @SerialName("touch_points") val touchPoints: Int,
        @SerialName("webgl_vendor") val webglVendor: String,
        @SerialName("webgl_renderer") val webglRenderer: String,
        @SerialName("canvas_hash") val canvasHash: String,
        @SerialName("audio_hash") val audioHash: String,
        @SerialName("webgl_params_hash") val webglParamsHash: String,
        @SerialName("fonts_hash") val fontsHash: String,
        @SerialName("codecs_hash") val codecsHash: String,
        @SerialName("media_devices") val mediaDevices: String,
        @SerialName("pointer_type") val pointerType: String,
        val extra: Map<String, String>,
    )

    @Serializable
    data class AttestResponse(val token: String, @SerialName("viewer_id") val viewerId: String, @SerialName("device_id") val deviceId: String, val confidence: Double)

    @Serializable
    data class FingerprintPayload(val fingerprint: FingerprintData)

    @Serializable
    data class FingerprintData(val token: String, @SerialName("viewer_id") val viewerId: String, @SerialName("device_id") val deviceId: String, val confidence: Double)

    @Serializable
    data class CaptchaResponse(@SerialName("pow_nonce") val powNonce: String, @SerialName("pow_difficulty") val powDifficulty: Int, @SerialName("pow_token") val powToken: String)

    @Serializable
    data class VerifyRequest(@SerialName("pow_token") val powToken: String, val solution: String, val fingerprint: FingerprintData)

    @Serializable
    data class VerifyResponse(val status: String, val token: String? = null)

    @Serializable
    data class VideoSource(
        val file: String? = null,
        val url: String? = null,
        val label: String? = "Default",
    )
}

fun String.encodeUrlPath(): String {
    val uri = URI(this)
    val encodedPath = uri.rawPath.split("/").joinToString("/") { segment ->
        if (segment.isEmpty()) "" else URLEncoder.encode(segment, StandardCharsets.UTF_8.toString()).replace("+", "%20")
    }
    return URI(uri.scheme, uri.rawAuthority, encodedPath, uri.rawQuery, uri.rawFragment).toString()
}

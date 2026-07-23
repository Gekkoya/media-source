package org.symera.mediasource.lib.fkplayer

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object FkPlayerDecoder {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(url: String): String? {
        if (!url.contains("fkplayer.xyz") || "/e/" !in url) return null
        val jwt = url.substringAfter("/e/").substringBefore("?").substringBefore("#")
        val payload = jwt.split(".").getOrNull(1) ?: return null
        return runCatching {
            val paddedPayload = payload + "=".repeat((4 - payload.length % 4) % 4)
            val decodedPayload = Base64.decode(paddedPayload, Base64.URL_SAFE or Base64.NO_WRAP)
            val encodedLink = json.parseToJsonElement(decodedPayload.toString(Charsets.UTF_8))
                .jsonObject["link"]?.jsonPrimitive?.content
                ?: return null
            String(Base64.decode(encodedLink, Base64.DEFAULT))
                .takeIf { it.startsWith("http") }
        }.getOrNull()
    }
}

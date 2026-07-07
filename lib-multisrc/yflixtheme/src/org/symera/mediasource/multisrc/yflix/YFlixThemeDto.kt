package org.symera.mediasource.multisrc.yflix

import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@Serializable
data class ResultResponse(
    val result: String,
) {
    fun toDocument(): Document = Jsoup.parseBodyFragment(result)
}

@Serializable
data class DecryptedIframeResponse(
    val result: DecryptedUrl,
)

@Serializable
data class DecryptedUrl(
    val url: String,
)

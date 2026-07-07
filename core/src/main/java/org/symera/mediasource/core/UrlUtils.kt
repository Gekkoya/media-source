package org.symera.mediasource.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object UrlUtils {
    private val firstHttpsRegex by lazy { Regex("""^.*(?=https?://)""") }

    fun fixUrl(url: String): String? = when {
        url.isEmpty() -> null
        url.startsWith("http") || url.startsWith("{\"") -> url
        url.startsWith("//") -> "https:$url"
        else -> url.replaceFirst(firstHttpsRegex, "")
    }

    fun fixUrl(url: String, baseUrl: String): String? {
        val baseHttpUrl = baseUrl.toHttpUrlOrNull() ?: return null
        return when {
            url.isEmpty() -> null
            url.startsWith("http") || url.startsWith("{\"") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> {
                baseHttpUrl.newBuilder().encodedPath("/").build().toString()
                    .substringBeforeLast("/") + url
            }
            else -> {
                val basePath = baseHttpUrl.newBuilder().apply {
                    removePathSegment(baseHttpUrl.pathSize - 1)
                    addPathSegment("")
                    query(null)
                    fragment(null)
                }.build().toString()
                basePath + url
            }
        }
    }
}

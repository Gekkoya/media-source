package org.symera.mediasource.core

import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.ByteString
import org.jsoup.nodes.Document
import org.symera.source.network.awaitSuccess
import org.symera.source.online.GET
import org.symera.source.online.POST
import org.symera.source.online.asJsoup
import java.util.concurrent.TimeUnit.MINUTES

private val DEFAULT_CACHE_CONTROL = CacheControl.Builder().maxAge(10, MINUTES).build()
private val DEFAULT_HEADERS = Headers.headersOf()
private val DEFAULT_BODY: RequestBody = FormBody.Builder().build()

fun OkHttpClient.get(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Response = newCall(GET(url, headers).withCache(cache)).execute()

fun OkHttpClient.get(
    url: HttpUrl,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Response = newCall(GET(url.toString(), headers).withCache(cache)).execute()

fun OkHttpClient.post(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Response = newCall(POST(url, headers, body).withCache(cache)).execute()

suspend fun OkHttpClient.getAwait(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Response = awaitSuccess(GET(url, headers).withCache(cache))

suspend fun OkHttpClient.postAwait(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Response = awaitSuccess(POST(url, headers, body).withCache(cache))

fun Response.useAsJsoup(): Document = use { it.asJsoup() }

fun Response.bodyString(): String = use { it.body.string() }

val commonEmptyHeaders: Headers = Headers.headersOf()

val commonEmptyRequestBody: RequestBody = ByteString.EMPTY.toRequestBody()

private fun Request.withCache(cache: CacheControl): Request = newBuilder().cacheControl(cache).build()

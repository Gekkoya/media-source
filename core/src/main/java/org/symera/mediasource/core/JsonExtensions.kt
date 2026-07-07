@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package org.symera.mediasource.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.buffer
import okio.source
import java.io.InputStream

val mediaSourceJson: Json = Json {
    isLenient = true
    ignoreUnknownKeys = true
    explicitNulls = false
}

val jsonInstance: Json = mediaSourceJson

val JSON_MEDIA_TYPE = "application/json".toMediaType()

inline fun <reified T> String.parseAs(json: Json = mediaSourceJson): T = json.decodeFromString(serializer(), this)

inline fun <reified T> String.parseAs(json: Json = mediaSourceJson, transform: (String) -> String): T = transform(this).parseAs(json)

inline fun <reified T> Response.parseAs(json: Json = mediaSourceJson): T = use {
    json.decodeFromBufferedSource(serializer(), it.body.source())
}

inline fun <reified T> Response.parseAs(json: Json = mediaSourceJson, transform: (String) -> String): T = use {
    it.body.string().parseAs(json, transform)
}

inline fun <reified T> JsonElement.parseAs(json: Json = mediaSourceJson): T = json.decodeFromJsonElement(serializer(), this)

inline fun <reified T> InputStream.parseAs(json: Json = mediaSourceJson): T = use {
    json.decodeFromBufferedSource(serializer(), it.source().buffer())
}

inline fun <reified T> T.toJsonString(json: Json = mediaSourceJson): String = json.encodeToString(serializer(), this)

fun String.toJsonBody(): RequestBody = toRequestBody(JSON_MEDIA_TYPE)

inline fun <reified T> T.toJsonRequestBody(json: Json = mediaSourceJson): RequestBody = toJsonString(json).toJsonBody()

inline fun <reified T> T.toJsonElement(json: Json = mediaSourceJson): JsonElement = json.encodeToJsonElement(serializer(), this)

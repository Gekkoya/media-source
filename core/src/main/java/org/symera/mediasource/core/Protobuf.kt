@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package org.symera.mediasource.core

import android.util.Base64
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody

val protoInstance: ProtoBuf = ProtoBuf
val PROTOBUF_MEDIA_TYPE = "application/protobuf".toMediaType()

inline fun <reified T> ByteArray.decodeProto(proto: ProtoBuf = protoInstance): T = proto.decodeFromByteArray<T>(this)

inline fun <reified T : Any> T.encodeProto(proto: ProtoBuf = protoInstance): ByteArray = proto.encodeToByteArray(this)

inline fun <reified T> Response.parseAsProto(proto: ProtoBuf = protoInstance): T = use { it.body.bytes().decodeProto(proto) }

inline fun <reified T> Response.parseAsProto(proto: ProtoBuf = protoInstance, transform: (ByteArray) -> ByteArray): T = use {
    transform(it.body.bytes()).decodeProto(proto)
}

inline fun <reified T> ResponseBody.parseAsProto(proto: ProtoBuf = protoInstance): T = bytes().decodeProto(proto)

inline fun <reified T : Any> T.toRequestBodyProto(
    proto: ProtoBuf = protoInstance,
    mediaType: MediaType = PROTOBUF_MEDIA_TYPE,
): RequestBody = encodeProto(proto).toRequestBody(mediaType)

inline fun <reified T> String.decodeProtoBase64(proto: ProtoBuf = protoInstance): T = Base64.decode(this, Base64.NO_WRAP).decodeProto(proto)

inline fun <reified T : Any> T.encodeProtoBase64(proto: ProtoBuf = protoInstance): String = Base64.encodeToString(encodeProto(proto), Base64.NO_WRAP)

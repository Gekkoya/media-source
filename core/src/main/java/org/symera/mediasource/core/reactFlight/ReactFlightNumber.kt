package org.symera.mediasource.core.reactFlight

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive

typealias ReactFlightNumber =
    @Serializable(with = ReactFlightNumberSerializer::class)
    Double

object ReactFlightNumberSerializer : KSerializer<Double> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ReactFlightNumber", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Double): Unit = throw SerializationException("Stub !")

    override fun deserialize(decoder: Decoder): Double {
        val raw = (decoder as JsonDecoder).decodeJsonElement().jsonPrimitive.content
        return when (raw) {
            "Infinity" -> Double.POSITIVE_INFINITY
            "-Infinity" -> Double.NEGATIVE_INFINITY
            "NaN" -> Double.NaN
            "-0" -> -0.0
            else -> raw.toDoubleOrNull() ?: throw IllegalArgumentException("Failed to parse Number: $raw")
        }
    }
}

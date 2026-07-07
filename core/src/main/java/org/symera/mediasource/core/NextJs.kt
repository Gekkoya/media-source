package org.symera.mediasource.core

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import okhttp3.Response
import org.jsoup.nodes.Document
import org.symera.source.online.asJsoup
import kotlin.reflect.typeOf

private val NEXT_F_REGEX = Regex("""self\.__next_f\.push\(\s*(\[.*])\s*\)\s*;?\s*$""", RegexOption.DOT_MATCHES_ALL)

private fun <T> extractValueNextJs(
    payload: JsonElement,
    predicate: (JsonElement) -> Boolean,
    deserializer: DeserializationStrategy<T>,
): T? {
    if (payload !is JsonObject && payload !is JsonArray) return null
    if (predicate(payload)) return jsonInstance.decodeFromJsonElement(deserializer, payload)
    val children: Iterable<JsonElement> = when (payload) {
        is JsonObject -> payload.values
        is JsonArray -> payload
    }
    for (child in children) {
        val result = extractValueNextJs(child, predicate, deserializer)
        if (result != null) return result
    }
    return null
}

private fun resolveNextJsRefs(
    element: JsonElement,
    chunkCache: Map<String, String>,
    modelCache: Map<String, JsonElement>,
    resolving: Set<String> = emptySet(),
): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.mapValues { resolveNextJsRefs(it.value, chunkCache, modelCache, resolving) })
    is JsonArray -> JsonArray(element.map { resolveNextJsRefs(it, chunkCache, modelCache, resolving) })
    is JsonPrimitive -> {
        if (element.isString && element.content.startsWith("$") && element.content.length >= 2) {
            val str = element.content
            when {
                str == "\$undefined" -> JsonNull
                str == "\$Infinity" || str == "\$-Infinity" || str == "\$NaN" || str == "\$-0" -> JsonPrimitive(str.substring(1))
                str[1] == '$' -> JsonPrimitive(str.substring(1))
                str[1] == 'D' -> JsonPrimitive(str.substring(2))
                str[1] == 'n' -> JsonPrimitive(str.substring(2))
                str[1] == 'Q' -> resolveMapRef(str.substring(2), chunkCache, modelCache, resolving) ?: element
                str[1] == 'W' -> resolveSetRef(str.substring(2), chunkCache, modelCache, resolving) ?: element
                else -> chunkCache[str.substring(1)]?.let { JsonPrimitive(it) } ?: element
            }
        } else {
            element
        }
    }
}

private fun resolveMapRef(
    id: String,
    chunkCache: Map<String, String>,
    modelCache: Map<String, JsonElement>,
    resolving: Set<String>,
): JsonElement? {
    if (id in resolving) return null
    val entries = modelCache[id] as? JsonArray ?: return null
    val resolved = resolveNextJsRefs(entries, chunkCache, modelCache, resolving + id) as? JsonArray ?: return null
    val pairs = resolved.mapNotNull { (it as? JsonArray)?.takeIf { pair -> pair.size == 2 } }
    return JsonObject(
        pairs.associate { (key, value) ->
            val jsonKey = (key as? JsonPrimitive)?.content ?: key.toString()
            jsonKey to value
        },
    )
}

private fun resolveSetRef(
    id: String,
    chunkCache: Map<String, String>,
    modelCache: Map<String, JsonElement>,
    resolving: Set<String>,
): JsonElement? {
    if (id in resolving) return null
    val values = modelCache[id] as? JsonArray ?: return null
    return resolveNextJsRefs(values, chunkCache, modelCache, resolving + id)
}

private fun Document.extractAppRouterPayloads(
    chunkCache: MutableMap<String, String>,
    modelCache: MutableMap<String, JsonElement>,
): List<JsonElement> = select("script:not([src])")
    .map { it.data() }
    .filter { "self.__next_f.push" in it }
    .flatMap { script ->
        try {
            val raw = NEXT_F_REGEX.find(script)?.groupValues?.get(1) ?: return@flatMap emptyList()
            val arr = jsonInstance.parseToJsonElement(raw).jsonArray
            val content = arr.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: return@flatMap emptyList()
            extractRscPayloads(content, chunkCache, modelCache)
        } catch (_: Exception) {
            emptyList()
        }
    }

private fun Document.extractPagesRouterPayloads(): List<JsonElement> {
    val data = selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
    return try {
        val root = jsonInstance.parseToJsonElement(data)
        val pageProps = root.jsonObject["props"]?.jsonObject?.get("pageProps")
        listOfNotNull(pageProps, root)
    } catch (_: Exception) {
        emptyList()
    }
}

private fun extractRscPayloads(
    body: String,
    chunkCache: MutableMap<String, String>,
    modelCache: MutableMap<String, JsonElement>,
): List<JsonElement> {
    val results = mutableListOf<JsonElement>()
    var pos = 0

    while (pos < body.length) {
        val colonIdx = body.indexOf(':', pos)
        if (colonIdx == -1) break

        val id = body.substring(pos, colonIdx)
        if (id.isEmpty() || !id.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            pos++
            continue
        }

        pos = colonIdx + 1
        if (pos >= body.length) break

        if (body[pos] == 'T') {
            pos++
            val commaIdx = body.indexOf(',', pos)
            if (commaIdx == -1) break
            val byteLen = body.substring(pos, commaIdx).toIntOrNull(16) ?: break
            pos = commaIdx + 1
            var bytes = 0
            val start = pos
            while (pos < body.length && bytes < byteLen) {
                when {
                    body[pos].code < 0x80 -> bytes += 1
                    body[pos].code < 0x800 -> bytes += 2
                    Character.isHighSurrogate(body[pos]) -> {
                        bytes += 4
                        pos++
                    }

                    else -> bytes += 3
                }
                pos++
            }

            val chunkContent = body.substring(start, pos)
            chunkCache[id] = chunkContent
            try {
                results.add(jsonInstance.parseToJsonElement(chunkContent))
            } catch (_: Exception) {
            }
        } else {
            val (element, end) = parseJsonAt(body, pos)
            if (element != null) {
                results.add(element)
                modelCache[id] = element
            }
            pos = end
        }
    }

    return results
}

private fun parseJsonAt(body: String, start: Int): Pair<JsonElement?, Int> {
    if (start >= body.length) return Pair(null, start)

    var depth = 0
    var inString = false
    var escape = false
    var i = start

    while (i < body.length) {
        val c = body[i++]
        if (escape) {
            escape = false
            continue
        }
        if (c == '\\' && inString) {
            escape = true
            continue
        }
        if (c == '"') {
            inString = !inString
            continue
        }
        if (inString) continue
        when (c) {
            '{', '[' -> depth++
            '}', ']' -> if (--depth == 0) {
                return try {
                    Pair(jsonInstance.parseToJsonElement(body.substring(start, i)), i)
                } catch (_: Exception) {
                    Pair(null, i)
                }
            }
        }
        if (depth == 0 && c.isWhitespace()) {
            return try {
                Pair(jsonInstance.parseToJsonElement(body.substring(start, i - 1)), i)
            } catch (_: Exception) {
                Pair(null, i)
            }
        }
    }
    return Pair(null, i)
}

@PublishedApi
internal inline fun <reified T> inferredNextJsPredicate(): (JsonElement) -> Boolean {
    val kType = typeOf<T>()
    val isList = kType.classifier == List::class

    val elementDescriptor = if (isList) {
        serializer<T>().descriptor.getElementDescriptor(0)
    } else {
        serializer<T>().descriptor
    }

    val requiredKeys = (0 until elementDescriptor.elementsCount)
        .filterNot { elementDescriptor.isElementOptional(it) || elementDescriptor.getElementDescriptor(it).isNullable }
        .map { elementDescriptor.getElementName(it) }
        .toSet()

    require(requiredKeys.isNotEmpty()) {
        "Cannot infer a predicate for ${elementDescriptor.serialName}: all fields are optional or nullable. Provide an explicit predicate instead."
    }

    return if (isList) {
        { element ->
            element is JsonArray &&
                element.isNotEmpty() &&
                element.first() is JsonObject &&
                requiredKeys.all { it in element.first().jsonObject }
        }
    } else {
        { element -> element is JsonObject && requiredKeys.all { it in element } }
    }
}

fun <T> Document.extractNextJs(
    predicate: (JsonElement) -> Boolean,
    deserializer: DeserializationStrategy<T>,
): T? {
    val chunkCache = mutableMapOf<String, String>()
    val modelCache = mutableMapOf<String, JsonElement>()
    val payloads = extractAppRouterPayloads(chunkCache, modelCache).ifEmpty { extractPagesRouterPayloads() }

    for (payload in payloads) {
        val resolvedPayload = resolveNextJsRefs(payload, chunkCache, modelCache)
        val result = extractValueNextJs(resolvedPayload, predicate, deserializer)
        if (result != null) return result
    }
    return null
}

inline fun <reified T> Document.extractNextJs(
    noinline predicate: (JsonElement) -> Boolean,
): T? = extractNextJs(predicate, serializer<T>())

inline fun <reified T> Document.extractNextJs(): T? = extractNextJs(inferredNextJsPredicate<T>(), serializer<T>())

fun <T> String.extractNextJsRsc(
    predicate: (JsonElement) -> Boolean,
    deserializer: DeserializationStrategy<T>,
): T? {
    val chunkCache = mutableMapOf<String, String>()
    val modelCache = mutableMapOf<String, JsonElement>()
    for (payload in extractRscPayloads(this, chunkCache, modelCache)) {
        val resolvedPayload = resolveNextJsRefs(payload, chunkCache, modelCache)
        val result = extractValueNextJs(resolvedPayload, predicate, deserializer)
        if (result != null) return result
    }
    return null
}

inline fun <reified T> String.extractNextJsRsc(
    noinline predicate: (JsonElement) -> Boolean,
): T? = extractNextJsRsc(predicate, serializer<T>())

inline fun <reified T> String.extractNextJsRsc(): T? = extractNextJsRsc(inferredNextJsPredicate<T>(), serializer<T>())

fun <T> Response.extractNextJs(
    predicate: (JsonElement) -> Boolean,
    deserializer: DeserializationStrategy<T>,
): T? {
    val contentType = header("Content-Type") ?: ""
    return when {
        "text/x-component" in contentType -> body.string().extractNextJsRsc(predicate, deserializer)
        "text/html" in contentType -> asJsoup().extractNextJs(predicate, deserializer)
        else -> error("Unsupported Content-Type for Next.js extraction: $contentType")
    }
}

inline fun <reified T> Response.extractNextJs(
    noinline predicate: (JsonElement) -> Boolean,
): T? = extractNextJs(predicate, serializer<T>())

inline fun <reified T> Response.extractNextJs(): T? = extractNextJs(inferredNextJsPredicate<T>(), serializer<T>())

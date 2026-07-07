package org.symera.mediasource.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Builder
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.symera.source.online.GET
import org.symera.source.online.POST

@Serializable
private class GraphQLRequest<V>(
    private val operationName: String? = null,
    private val query: String? = null,
    private val variables: V? = null,
    private val extensions: JsonElement? = null,
)

@PublishedApi
@Serializable
internal class GraphQLResponse<T>(
    val data: T? = null,
    val errors: List<GraphQLError>? = null,
) {
    @PublishedApi
    @Serializable
    internal class GraphQLError(
        val message: String,
    )
}

@Serializable
private class PersistedQueryExtension(
    private val persistedQuery: PersistedQuery,
) {
    @Serializable
    class PersistedQuery(
        private val version: Int,
        private val sha256Hash: String,
    )
}

class GraphQLErrorInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.isSuccessful) return response
        val body = response.peekBody(Long.MAX_VALUE).string()
        val errors = runCatching { body.parseAs<GraphQLResponse<Unit>>().errors }.getOrNull()
        if (!errors.isNullOrEmpty()) throw GraphQLException(errors.joinToString("\n") { it.message })
        return response
    }
}

fun graphQLBody(
    query: String? = null,
    operationName: String? = null,
    variables: JsonElement? = null,
    extensions: JsonElement? = null,
    json: Json = jsonInstance,
): RequestBody = GraphQLRequest(
    operationName = operationName,
    query = query,
    variables = variables,
    extensions = extensions,
).toJsonRequestBody(json)

inline fun <reified V : Any> graphQLBody(
    query: String? = null,
    operationName: String? = null,
    variables: V,
    extensions: JsonElement? = null,
    json: Json = jsonInstance,
): RequestBody = graphQLBody(query, operationName, variables.toJsonElement(json), extensions, json)

fun Builder.appendGraphQLParams(
    query: String? = null,
    operationName: String? = null,
    variables: JsonElement? = null,
    extensions: JsonElement? = null,
    json: Json = jsonInstance,
): Builder = apply {
    operationName?.let { addQueryParameter("operationName", it) }
    query?.let { addQueryParameter("query", it) }
    variables?.let { addQueryParameter("variables", it.toJsonString(json)) }
    extensions?.let { addQueryParameter("extensions", it.toJsonString(json)) }
}

inline fun <reified V : Any> Builder.appendGraphQLParams(
    query: String? = null,
    operationName: String? = null,
    variables: V,
    extensions: JsonElement? = null,
    json: Json = jsonInstance,
): Builder = appendGraphQLParams(query, operationName, variables.toJsonElement(json), extensions, json)

fun graphQLPost(
    url: String,
    headers: Headers,
    query: String? = null,
    operationName: String? = null,
    variables: JsonElement? = null,
    extensions: JsonElement? = null,
    cache: CacheControl? = null,
    json: Json = jsonInstance,
): Request {
    val request = POST(url, headers, graphQLBody(query, operationName, variables, extensions, json))
    return if (cache == null) request else request.newBuilder().cacheControl(cache).build()
}

inline fun <reified V : Any> graphQLPost(
    url: String,
    headers: Headers,
    query: String? = null,
    operationName: String? = null,
    variables: V,
    extensions: JsonElement? = null,
    cache: CacheControl? = null,
    json: Json = jsonInstance,
): Request = graphQLPost(url, headers, query, operationName, variables.toJsonElement(json), extensions, cache, json)

fun graphQLGet(
    url: String,
    headers: Headers,
    query: String? = null,
    operationName: String? = null,
    variables: JsonElement? = null,
    extensions: JsonElement? = null,
    cache: CacheControl? = null,
    json: Json = jsonInstance,
): Request {
    val graphqlUrl = url.toHttpUrl().newBuilder()
        .appendGraphQLParams(query, operationName, variables, extensions, json)
        .build()
    val request = GET(graphqlUrl.toString(), headers)
    return if (cache == null) request else request.newBuilder().cacheControl(cache).build()
}

inline fun <reified V : Any> graphQLGet(
    url: String,
    headers: Headers,
    query: String? = null,
    operationName: String? = null,
    variables: V,
    extensions: JsonElement? = null,
    cache: CacheControl? = null,
    json: Json = jsonInstance,
): Request = graphQLGet(url, headers, query, operationName, variables.toJsonElement(json), extensions, cache, json)

fun persistedQueryExtension(hash: String, version: Int = 1): JsonElement = PersistedQueryExtension(
    PersistedQueryExtension.PersistedQuery(version, hash),
).toJsonElement()

inline fun <reified T> Response.parseGraphQLAs(json: Json = jsonInstance): T {
    val envelope = parseAs<GraphQLResponse<T>>(json)
    val errors = envelope.errors
    if (!errors.isNullOrEmpty()) throw GraphQLException(errors.joinToString("\n") { it.message })
    return envelope.data ?: throw IllegalStateException("GraphQL response is missing the 'data' field")
}

inline fun <reified T> String.parseGraphQLAs(json: Json = jsonInstance): T {
    val envelope = parseAs<GraphQLResponse<T>>(json)
    val errors = envelope.errors
    if (!errors.isNullOrEmpty()) throw GraphQLException(errors.joinToString("\n") { it.message })
    return envelope.data ?: throw IllegalStateException("GraphQL response is missing the 'data' field")
}

class GraphQLException(message: String) : Exception(message)

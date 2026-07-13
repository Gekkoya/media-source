package org.symera.mediasource.es.doramasflix

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class PaginationInfo(
    val hasNextPage: Boolean = false,
    val itemCount: Int = 0,
    val pageCount: Int = 0,
)

@Serializable
data class DoramaPagination(
    val items: List<DoramaDto> = emptyList(),
    val pageInfo: PaginationInfo = PaginationInfo(),
)

@Serializable
data class MoviePagination(
    val items: List<MovieDto> = emptyList(),
    val pageInfo: PaginationInfo = PaginationInfo(),
)

@Serializable
data class EpisodePagination(
    val items: List<EpisodeDto> = emptyList(),
    val pageInfo: PaginationInfo = PaginationInfo(),
)

@Serializable
data class DoramaDto(
    @SerialName("_id") val id: String = "",
    val name: String = "",
    @SerialName("name_es") val nameEs: String? = null,
    val slug: String = "",
    val overview: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("number_of_seasons") val numberOfSeasons: Double? = null,
    @SerialName("number_of_episodes") val numberOfEpisodes: Double? = null,
    val type: String? = null,
    val status: String? = null,
    val languages: List<String>? = null,
    val genres: List<GenreDto>? = null,
    val seasons: List<SeasonRefDto>? = null,
)

@Serializable
data class MovieDto(
    @SerialName("_id") val id: String = "",
    val name: String = "",
    @SerialName("name_es") val nameEs: String? = null,
    val title: String? = null,
    val slug: String = "",
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val runtime: Double? = null,
    val type: String? = null,
    val status: String? = null,
    val languages: List<String>? = null,
    val genres: List<GenreDto>? = null,
)

@Serializable
data class EpisodeDto(
    @SerialName("_id") val id: String = "",
    val name: String = "",
    @SerialName("name_es") val nameEs: String? = null,
    @SerialName("episode_number") val episodeNumber: Double? = null,
    @SerialName("season_number") val seasonNumber: Double? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("serie_id") val serieId: String? = null,
    @SerialName("serie_slug") val serieSlug: String? = null,
    @SerialName("serie_name") val serieName: String? = null,
    @SerialName("season_id") val seasonId: String? = null,
    @SerialName("season_slug") val seasonSlug: String? = null,
    val languages: List<String>? = null,
    @SerialName("links_online") val linksOnline: List<LinkOnlineDto>? = null,
)

@Serializable
data class GenreDto(
    val name: String? = null,
    val slug: String? = null,
)

@Serializable
data class SeasonRefDto(
    val slug: String = "",
    @SerialName("season_number") val seasonNumber: Double? = null,
    @SerialName("number_of_episodes") val numberOfEpisodes: Double? = null,
    @SerialName("_id") val id: String = "",
    val ref: String? = null,
)

@Serializable
data class LinkOnlineDto(
    val link: String? = null,
    val lang: String? = null,
    @SerialName("language_code") val languageCode: String? = null,
    val server: String? = null,
    val embed: String? = null,
)

@Serializable
data class GqlResponse(
    val data: JsonElement? = null,
    val errors: List<JsonElement>? = null,
)

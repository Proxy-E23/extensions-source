package eu.kanade.tachiyomi.extension.es.capibarahub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── /api/manga-custom (lista paginada del hub) ───────────────────────────────
@Serializable
data class MangaListResponse(
    val status: Boolean,
    val data: MangaListData,
)

@Serializable
data class MangaListData(
    val items: List<MangaItemDto>,
    val maxPage: Int,
    val total: Int,
)

// ── Item individual de la lista (y también del detalle con chapters completos) ─
@Serializable
data class MangaItemDto(
    val id: Int, // mangaCustomId (id dentro del fansub)
    val mangaId: Int = 0, // id global del manga
    val organizationId: Int = 0,
    val title: String,
    val shortDescription: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val bannerUrl: String? = null,
    val status: String? = null, // "ongoing" | "completed" | "finished"
    val isNSFW: Boolean = false,
    val manga: MangaGlobalDto,
    val organization: OrganizationDto,
    val chapters: List<ChapterSummaryDto> = emptyList(),
    val genres: List<GenreDto> = emptyList(),
    val jointSlug: String? = null,
)

@Serializable
data class MangaGlobalDto(
    val id: Int,
    val slug: String,
    val title: String,
    val demography: DemographyDto? = null,
    val authors: List<AuthorDto> = emptyList(), // ← nuevo
)

@Serializable
data class AuthorDto(
    val name: String,
    val shortDescription: String? = null,
)

@Serializable
data class OrganizationDto(
    val id: Int,
    val name: String,
    val slug: String,
    val isNSFW: Boolean = false,
)

@Serializable
data class DemographyDto(
    val name: String,
    val slug: String,
)

@Serializable
data class GenreDto(
    val name: String,
    val slug: String,
)

// ── Capítulos en la lista de detalle ─────────────────────────────────────────
@Serializable
data class ChapterSummaryDto(
    val id: Int,
    val number: Float,
    val title: String? = null,
    val releasedAt: String? = null,
    val isUnreleased: Boolean = false,
)

// ── /api/manga-custom/{id} (detalle completo, misma estructura) ───────────────
@Serializable
data class MangaDetailResponse(
    val status: Boolean,
    val data: MangaItemDto,
)

// ── /api/manga-custom/{mangaSlug}/chapter/{chapterNumber}/pages ───────────────
@Serializable
data class PagesResponse(
    val status: Boolean,
    val data: List<PageDto>,
)

@Serializable
data class PageDto(
    @SerialName("imageUrl") val imageUrl: String? = null,
    @SerialName("url") val url: String? = null,
    val order: Int = 0,
) {
    val resolvedUrl get() = imageUrl ?: url ?: ""
}

@Serializable
data class ApiErrorResponse(
    val status: Boolean,
    val message: String? = null,
    val errorType: String? = null,
)

// ── /api/joint/{slug} (detalle de manga joint) ────────────────────────────────
@Serializable
data class JointDetailResponse(
    val status: Boolean,
    val data: JointItemDto,
)

@Serializable
data class JointItemDto(
    val id: Int,
    val slug: String,
    val title: String,
    val shortDescription: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val status: String? = null,
    val isNSFW: Boolean = false,
    val manga: MangaGlobalDto,
    val members: List<JointMemberDto> = emptyList(),
    val chapters: List<ChapterSummaryDto> = emptyList(),
)

@Serializable
data class JointMemberDto(
    val role: String,
    val organization: JointOrgDto,
)

@Serializable
data class JointOrgDto(
    val id: Int,
    val name: String,
    val slug: String,
)

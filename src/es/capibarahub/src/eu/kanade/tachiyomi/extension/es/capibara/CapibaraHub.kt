package eu.kanade.tachiyomi.extension.es.capibarahub

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class CapibaraHub :
    HttpSource(),
    ConfigurableSource {

    override val name = "Capibara Traductor Hub"
    override val baseUrl = "https://capibaratraductor.com"
    override val lang = "es"
    override val supportsLatest = true

    private val apiUrl = "https://capibaratraductor.com"

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimitHost(apiUrl.toHttpUrl(), 3)
        .build()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    private val json = Json { ignoreUnknownKeys = true }

    // ── Preferencias ─────────────────────────────────────────────────────────
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val nsfwMode: String
        get() = preferences.getString(PREF_NSFW_MODE, NSFW_HIDE)!!

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_NSFW_MODE
            title = "Contenido +18"
            summary = "%s"
            entries = arrayOf(
                "Ocultar contenido +18 (por defecto)",
                "Mostrar todo (SFW y +18)",
                "Mostrar solo contenido +18",
            )
            entryValues = arrayOf(NSFW_HIDE, NSFW_SHOW_ALL, NSFW_ONLY)
            setDefaultValue(NSFW_HIDE)
        }.also { screen.addPreference(it) }
    }

    // ── SManga.url formato normal:  {mangaGlobalSlug}/{orgSlug}/{mangaCustomId}
    // ── SManga.url formato joint:   {mangaSlug}/joint/{jointId}
    // ── SChapter.url formato:       {mangaSlug}/{orgSlug}/{id}/{chapterNumber}

    // ── URL visible en el navegador ───────────────────────────────────────────
    override fun getMangaUrl(manga: SManga): String {
        val parts = manga.url.split("/")
        val mangaSlug = parts[0]
        val orgSlug = parts[1]
        return if (orgSlug == "joint") {
            "$baseUrl/joint/manga/$mangaSlug"
        } else {
            "$baseUrl/$orgSlug/manga/$mangaSlug"
        }
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val parts = chapter.url.split("/")
        val mangaSlug = parts[0]
        val orgSlug = parts[1]
        val num = parts[3]
        return if (orgSlug == "joint") {
            "$baseUrl/joint/manga/$mangaSlug/chapters/$num"
        } else {
            "$baseUrl/$orgSlug/manga/$mangaSlug/chapters/$num"
        }
    }

    // ── Lista popular ─────────────────────────────────────────────────────────
    override fun popularMangaRequest(page: Int): Request = GET("$apiUrl/api/manga-custom?page=$page&limit=$PAGE_LIMIT&order=popular", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val result = response.parseAs<MangaListResponse>()
        val mangas = result.data.items
            .filter { filterNsfw(it.isNSFW || it.organization.isNSFW) }
            .map { it.toSManga() }
        val hasNext = extractPage(response) < result.data.maxPage
        return MangasPage(mangas, hasNext)
    }

    // ── Últimas actualizaciones ───────────────────────────────────────────────
    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/api/manga-custom?page=$page&limit=$PAGE_LIMIT&order=latest", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ── Búsqueda ──────────────────────────────────────────────────────────────
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/api/manga-custom".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_LIMIT.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("title", query)
        } else {
            filters.forEach { filter ->
                when (filter) {
                    is SortByFilter -> url.addQueryParameter("order", filter.toUriPart())
                    else -> {}
                }
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ── Filtros de búsqueda ───────────────────────────────────────────────────
    override fun getFilterList() = FilterList(
        SortByFilter(
            "Ordenar por",
            arrayOf(
                Pair("Popularidad", "popular"),
                Pair("Recientes", "latest"),
            ),
        ),
    )

    class SortByFilter(title: String, vals: Array<Pair<String, String>>) : UriPartFilter(title, vals)

    open class UriPartFilter(name: String, val vals: Array<Pair<String, String>>) : Filter.Select<String>(name, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // ── Detalle del manga ─────────────────────────────────────────────────────
    override fun mangaDetailsRequest(manga: SManga): Request {
        val parts = manga.url.split("/")
        val mangaSlug = parts[0]
        val orgSlug = parts[1]
        return if (orgSlug == "joint") {
            GET("$apiUrl/api/joint/$mangaSlug", headers)
        } else {
            val headersWithOrg = headersBuilder()
                .add("x-organization", orgSlug)
                .build()
            GET("$apiUrl/api/manga-custom/$mangaSlug", headersWithOrg)
        }
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body.string()
        return if (response.request.url.pathSegments.contains("joint")) {
            json.decodeFromString<JointDetailResponse>(body).data.toSManga()
        } else {
            val item = json.decodeFromString<MangaDetailResponse>(body).data
            // Si tiene jointSlug, actualizar url a formato joint para que las páginas funcionen
            if (item.jointSlug != null) {
                item.toSMangaAsJoint()
            } else {
                item.toSMangaDetails()
            }
        }
    }

    // ── Lista de capítulos ────────────────────────────────────────────────────
    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body.string()
        return if (response.request.url.pathSegments.contains("joint")) {
            val item = json.decodeFromString<JointDetailResponse>(body).data
            item.chapters
                .filter {
                    !it.isUnreleased &&
                        it.releasedAt != null &&
                        (runCatching { dateFormat.parse(it.releasedAt)?.time ?: 0L }.getOrNull() ?: 0L) <= System.currentTimeMillis()
                }
                .map { chapter ->
                    SChapter.create().apply {
                        url = "${item.slug}/joint/${item.id}/${chapter.number}"
                        name = buildString {
                            append("Cap. ${formatNumber(chapter.number)}")
                            if (!chapter.title.isNullOrBlank()) append(" - ${chapter.title}")
                        }
                        date_upload = chapter.releasedAt?.let {
                            runCatching { dateFormat.parse(it)?.time }.getOrNull() ?: 0L
                        } ?: 0L
                        chapter_number = chapter.number
                    }
                }
        } else {
            val result = json.decodeFromString<MangaDetailResponse>(body)
            val item = result.data
            val orgSlug = item.organization.slug
            val mangaSlug = item.manga.slug
            val isJoint = item.jointSlug != null
            item.chapters
                .filter {
                    !it.isUnreleased &&
                        it.releasedAt != null &&
                        (runCatching { dateFormat.parse(it.releasedAt)?.time ?: 0L }.getOrNull() ?: 0L) <= System.currentTimeMillis()
                }
                .map { chapter ->
                    SChapter.create().apply {
                        url = if (isJoint) {
                            "$mangaSlug/joint/${item.id}/${chapter.number}"
                        } else {
                            "$mangaSlug/$orgSlug/${item.id}/${chapter.number}"
                        }
                        name = buildString {
                            append("Cap. ${formatNumber(chapter.number)}")
                            if (!chapter.title.isNullOrBlank()) append(" - ${chapter.title}")
                        }
                        date_upload = chapter.releasedAt?.let {
                            runCatching { dateFormat.parse(it)?.time }.getOrNull() ?: 0L
                        } ?: 0L
                        chapter_number = chapter.number
                    }
                }
        }
    }

    // ── Páginas del capítulo ──────────────────────────────────────────────────
    override fun pageListRequest(chapter: SChapter): Request {
        val parts = chapter.url.split("/")
        val mangaSlug = parts[0]
        val orgSlug = parts[1]
        val chapterNumber = parts[3]
        return if (orgSlug == "joint") {
            GET("$apiUrl/api/joint/$mangaSlug/chapter/$chapterNumber/pages", headers)
        } else {
            val headersWithOrg = headersBuilder()
                .add("x-organization", orgSlug)
                .build()
            GET("$apiUrl/api/manga-custom/$mangaSlug/chapter/$chapterNumber/pages", headersWithOrg)
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body.string()
        val error = runCatching {
            json.decodeFromString<ApiErrorResponse>(body)
        }.getOrNull()

        if (error != null && !error.status) {
            throw Exception(error.message ?: "Error al cargar páginas")
        }

        val result = json.decodeFromString<PagesResponse>(body)
        return result.data.mapIndexed { i, page ->
            Page(i, imageUrl = page.resolvedUrl)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun filterNsfw(isNsfw: Boolean): Boolean = when (nsfwMode) {
        NSFW_ONLY -> isNsfw
        NSFW_SHOW_ALL -> true
        else -> !isNsfw
    }

    private fun extractPage(response: Response): Int = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1

    private fun formatNumber(n: Float): String = if (n == n.toLong().toFloat()) n.toLong().toString() else n.toString()

    // SManga.url normal = mangaGlobalSlug/orgSlug/mangaCustomId
    private fun MangaItemDto.toSManga() = SManga.create().apply {
        url = "${manga.slug}/${organization.slug}/$id"
        title = this@toSManga.title
        thumbnail_url = imageUrl
        description = (this@toSManga.description ?: shortDescription)?.trim()
        author = manga.authors
            .filter { it.shortDescription?.lowercase() == "autor" }
            .joinToString(", ") { it.name }
            .ifEmpty { manga.authors.joinToString(", ") { it.name } }
            .ifEmpty { null }
        artist = organization.name
        genre = buildList {
            manga.demography?.name?.let { add(it) }
            genres.forEach { add(it.name) }
        }.joinToString(", ").ifEmpty { null }
        status = when (this@toSManga.status) {
            "ongoing" -> SManga.ONGOING
            "completed", "finished" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    private fun MangaItemDto.toSMangaDetails() = toSManga()

    // Cuando manga-custom tiene jointSlug, actualizar url a formato joint
    private fun MangaItemDto.toSMangaAsJoint() = toSManga().apply {
        url = "${manga.slug}/joint/$id"
    }

    // SManga.url joint = mangaSlug/joint/jointId
    private fun JointItemDto.toSManga() = SManga.create().apply {
        url = "$slug/joint/$id"
        title = this@toSManga.title
        thumbnail_url = imageUrl
        description = (this@toSManga.description ?: shortDescription)?.trim()
        author = manga.authors
            .filter { it.shortDescription?.lowercase() == "autor" }
            .joinToString(", ") { it.name }
            .ifEmpty { manga.authors.joinToString(", ") { it.name } }
            .ifEmpty { null }
        artist = members.joinToString(", ") { it.organization.name }.ifEmpty { null }
        genre = buildList {
            manga.demography?.name?.let { add(it) }
        }.joinToString(", ").ifEmpty { null }
        status = when (this@toSManga.status) {
            "ongoing" -> SManga.ONGOING
            "completed", "finished" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    companion object {
        private const val PAGE_LIMIT = 36
        private const val PREF_NSFW_MODE = "nsfw_mode"
        private const val NSFW_HIDE = "hide"
        private const val NSFW_SHOW_ALL = "show_all"
        private const val NSFW_ONLY = "only"
    }
}

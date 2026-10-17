package eu.kanade.tachiyomi.extension.es.capibarahub

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

@Source
abstract class CapibaraHub :
    KeiSource(),
    ConfigurableSource {

    private val apiUrl = "https://capibaratraductor.com"

    private val json = Json { ignoreUnknownKeys = true }

    private val preferences by getPreferencesLazy()

    override fun OkHttpClient.Builder.configureClient() = connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .rateLimit(1, RATE_LIMIT_PERIOD)

    private fun orgHeaders(orgSlug: String): Headers = headers.newBuilder()
        .set("x-organization", orgSlug)
        .build()

    // ── Preferencias ─────────────────────────────────────────────────────────
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

    // ── Popular / Últimas ─────────────────────────────────────────────────────
    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaList(
        "$apiUrl/api/manga-custom?page=$page&limit=$PAGE_LIMIT&order=popular".toHttpUrl(),
        page,
        headers,
    )

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaList(
        "$apiUrl/api/manga-custom?page=$page&limit=$PAGE_LIMIT&order=latest".toHttpUrl(),
        page,
        headers,
    )

    // ── Búsqueda ──────────────────────────────────────────────────────────────
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$apiUrl/api/manga-custom".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_LIMIT.toString())

        val requestHeaders = headers.newBuilder()

        if (query.isNotBlank()) {
            url.addQueryParameter("title", query)
        } else {
            filters.forEach { filter ->
                when (filter) {
                    is SortByFilter -> url.addQueryParameter("order", filter.toUriPart())
                    is ScanlatorFilter -> if (filter.state != 0) requestHeaders["x-organization"] = filter.toUriPart()
                    else -> {}
                }
            }
        }

        return parseMangaList(url.build(), page, requestHeaders.build())
    }

    private suspend fun parseMangaList(url: HttpUrl, page: Int, requestHeaders: Headers): MangasPage {
        val result = client.get(url, requestHeaders).parseAs<MangaListResponse>(json)
        val mangas = result.data.items
            .filter { filterNsfw(it.isNSFW || it.organization.isNSFW) }
            .map { it.toSManga() }
        return MangasPage(mangas, page < result.data.maxPage)
    }

    // ── Filtros de búsqueda ───────────────────────────────────────────────────
    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement {
        val sfwScans = fetchAllScans(includeNsfw = false)
        val nsfwScans = fetchAllScans(includeNsfw = true)

        return buildList {
            add("Todos" to "")
            addAll(
                (sfwScans + nsfwScans)
                    .distinctBy { it.second }
                    .sortedBy { it.first },
            )
        }.toJsonElement()
    }

    private suspend fun fetchAllScans(includeNsfw: Boolean): List<Pair<String, String>> {
        val scans = mutableListOf<Pair<String, String>>()
        var page = 1

        while (true) {
            val url = "$apiUrl/api/landing/scans".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .addQueryParameter("sort", "name")
                .addQueryParameter("limit", "100")
                .apply { if (includeNsfw) addQueryParameter("includeNSFW", "true") }
                .build()

            val result = client.get(url, headers)
                .parseAs<ScanListResponse>(json)
                .data

            scans += result.items.map { it.name to it.id }

            if (page >= result.maxPage) break
            page++
        }

        return scans
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filters = mutableListOf<Filter<*>>(
            SortByFilter(
                "Ordenar por",
                arrayOf(
                    Pair("Popularidad", "popular"),
                    Pair("Recientes", "latest"),
                ),
            ),
        )

        data?.parseAs<List<Pair<String, String>>>()?.also {
            filters.add(ScanlatorFilter("Fansub", it.toTypedArray()))
        }

        return FilterList(filters)
    }

    class SortByFilter(title: String, vals: Array<Pair<String, String>>) : UriPartFilter(title, vals)

    class ScanlatorFilter(title: String, vals: Array<Pair<String, String>>) : UriPartFilter(title, vals)

    open class UriPartFilter(name: String, val vals: Array<Pair<String, String>>) : Filter.Select<String>(name, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // ── Detalle + capítulos ───────────────────────────────────────────────────
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val parts = manga.url.split("/")
        val mangaSlug = parts[0]
        val orgSlug = parts[1]

        if (orgSlug == "joint") {
            val item = client.get("$apiUrl/api/joint/$mangaSlug", headers)
                .parseAs<JointDetailResponse>(json)
                .data

            val chapterList = item.chapters
                .filter { it.isReleased() }
                .map { it.toSChapter("${item.slug}/joint/${item.id}/${it.number}") }

            return SMangaUpdate(item.toSManga(), chapterList)
        }

        val item = client.get("$apiUrl/api/manga-custom/$mangaSlug", orgHeaders(orgSlug))
            .parseAs<MangaDetailResponse>(json)
            .data

        // Si tiene jointSlug, actualizar url a formato joint para que las páginas funcionen
        val isJoint = item.jointSlug != null
        val details = if (isJoint) item.toSMangaAsJoint() else item.toSManga()

        val chapterList = item.chapters
            .filter { it.isReleased() }
            .map { chapter ->
                val chapterUrl = if (isJoint) {
                    "${item.manga.slug}/joint/${item.id}/${chapter.number}"
                } else {
                    "${item.manga.slug}/${item.organization.slug}/${item.id}/${chapter.number}"
                }
                chapter.toSChapter(chapterUrl)
            }

        return SMangaUpdate(details, chapterList)
    }

    // ── Páginas del capítulo ──────────────────────────────────────────────────
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val parts = chapter.url.split("/")
        val mangaSlug = parts[0]
        val orgSlug = parts[1]
        val chapterNumber = parts[3]

        val response = if (orgSlug == "joint") {
            client.get("$apiUrl/api/joint/$mangaSlug/chapter/$chapterNumber/pages", headers, ensureSuccess = false)
        } else {
            client.get("$apiUrl/api/manga-custom/$mangaSlug/chapter/$chapterNumber/pages", orgHeaders(orgSlug), ensureSuccess = false)
        }

        val code = response.code
        val body = response.use { it.body.string() }

        val error = runCatching { body.parseAs<ApiErrorResponse>(json) }.getOrNull()

        if (error != null && !error.status) {
            throw Exception(error.message ?: "Error al cargar páginas")
        }
        if (error == null && code !in 200..299) {
            throw Exception("HTTP $code")
        }

        val result = body.parseAs<PagesResponse>(json)
        return result.data
            .map { it.resolvedUrl }
            .filter { it.isNotBlank() && !isNoticePage(it) }
            .mapIndexed { i, url -> Page(i, imageUrl = url) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // Descarta las páginas de aviso que la API inyecta en los capítulos
    private fun isNoticePage(url: String): Boolean = NOTICE_MARKERS.any { url.contains(it, ignoreCase = true) }

    private fun filterNsfw(isNsfw: Boolean): Boolean = when (nsfwMode) {
        NSFW_ONLY -> isNsfw
        NSFW_SHOW_ALL -> true
        else -> !isNsfw
    }

    private fun parseDate(date: String): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(date)?.time
    }.getOrNull() ?: 0L

    private fun ChapterSummaryDto.isReleased(): Boolean {
        val released = releasedAt ?: return false
        return !isUnreleased && parseDate(released) <= System.currentTimeMillis()
    }

    private fun formatNumber(n: Float): String = if (n == n.toLong().toFloat()) n.toLong().toString() else n.toString()

    private fun ChapterSummaryDto.toSChapter(chapterUrl: String) = SChapter.create().apply {
        url = chapterUrl
        name = buildString {
            append("Cap. ${formatNumber(number)}")
            if (!title.isNullOrBlank()) append(" - $title")
        }
        date_upload = releasedAt?.let { parseDate(it) } ?: 0L
        chapter_number = number
    }

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
        private val NOTICE_MARKERS = listOf("/promo/aviso/")
        private val RATE_LIMIT_PERIOD = 350.milliseconds
        private const val PREF_NSFW_MODE = "nsfw_mode"
        private const val NSFW_HIDE = "hide"
        private const val NSFW_SHOW_ALL = "show_all"
        private const val NSFW_ONLY = "only"
    }
}

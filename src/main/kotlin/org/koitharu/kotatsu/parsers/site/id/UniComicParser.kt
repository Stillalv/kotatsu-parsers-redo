package org.koitharu.kotatsu.parsers.site.id

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.AbstractMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("UNICOMIC", "UniComic", "id")
internal class UniComicParser(context: MangaLoaderContext) :
    AbstractMangaParser(context, MangaParserSource.UNICOMIC) {

    override val configKeyDomain = ConfigKey.Domain("app.unimains.my.id")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", config[userAgentKey])
        .add("Referer", "https://$domain/")
        .build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val existingUa = request.header("User-Agent")
        if (existingUa != null && !existingUa.startsWith("okhttp/", ignoreCase = true)) {
            return chain.proceed(request)
        }
        return chain.proceed(
            request.newBuilder()
                .header("User-Agent", config[userAgentKey])
                .build(),
        )
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = true,
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
        ),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
        ),
    )

    private val tagsMutex = Mutex()

    @Volatile
    private var cachedTags: Set<MangaTag>? = null

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        cachedTags?.let { return it }
        return tagsMutex.withLock {
            cachedTags?.let { return@withLock it }
            val parsed = runCatchingCancellable {
                val doc = webClient.httpGet("https://$domain/", getRequestHeaders()).parseHtml()
                val tags = LinkedHashSet<MangaTag>()
                for (link in doc.select("a[href^=\"/search?genres=\"]")) {
                    val genre = link.attr("href")
                        .toAbsoluteUrl(domain)
                        .toHttpUrlOrNull()
                        ?.queryParameter("genres")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: link.selectFirst("span")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: continue
                    tags.add(buildTag(genre))
                }
                tags
            }.getOrNull()?.takeIf { it.isNotEmpty() }

            if (parsed != null) {
                cachedTags = parsed
                parsed
            } else {
                DEFAULT_GENRES.mapTo(LinkedHashSet(DEFAULT_GENRES.size), ::buildTag)
            }
        }
    }

    override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val requiresClientTagFilter = filter.tags.size > 1 || filter.tagsExclude.isNotEmpty()
        if (!requiresClientTagFilter) {
            val page = (offset / PAGE_SIZE) + 1
            val result = fetchSearchPage(page, PAGE_SIZE, order, filter)
            val sliceStart = offset % PAGE_SIZE
            return if (sliceStart == 0) result.items else result.items.drop(sliceStart)
        }

        val requiredTagKeys = filter.tags.mapTo(HashSet()) { it.key.lowercase(Locale.ROOT) }
        val excludedTagKeys = filter.tagsExclude.mapTo(HashSet()) { it.key.lowercase(Locale.ROOT) }
        val matched = ArrayList<Manga>()
        var page = 1
        while (true) {
            val result = fetchSearchPage(page, API_MAX_LIMIT, order, filter)
            if (result.items.isEmpty()) break
            for (manga in result.items) {
                val mangaTagKeys = manga.tags.mapTo(HashSet()) { it.key.lowercase(Locale.ROOT) }
                val matchesRequired = requiredTagKeys.all { it in mangaTagKeys }
                val matchesExcluded = excludedTagKeys.any { it in mangaTagKeys }
                if (matchesRequired && !matchesExcluded) {
                    matched.add(manga)
                }
            }
            if (matched.size >= offset + PAGE_SIZE) break
            if (result.rawCount < API_MAX_LIMIT || page >= result.totalPages) break
            page++
        }
        return matched.drop(offset).take(PAGE_SIZE)
    }

    private suspend fun fetchSearchPage(
        page: Int,
        limit: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): SearchPageResult {
        val url = buildSearchUrl(page, limit, order, filter)
        val headers = getRequestHeaders().newBuilder()
            .add("Accept", "application/json")
            .build()
        val response = webClient.httpGet(url, headers)
        val totalPages = response.header("X-Total-Pages")?.toIntOrNull()?.coerceAtLeast(1) ?: Int.MAX_VALUE
        val array = response.parseJsonArray()
        val items = array.mapJSONNotNull(::parseSearchComic)
        return SearchPageResult(
            items = items,
            totalPages = totalPages,
            rawCount = array.length(),
        )
    }

    private fun buildSearchUrl(
        page: Int,
        limit: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): HttpUrl {
        val builder = HttpUrl.Builder()
            .scheme("https")
            .host(domain)
            .addPathSegments("api/search")
            .addQueryParameter("sort", mapSortOrder(order))
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", limit.toString())

        filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let {
            builder.addQueryParameter("q", it)
        }
        if (filter.tags.isNotEmpty()) {
            builder.addQueryParameter("genres", filter.tags.joinToString(",") { it.key })
        }
        val typesParam = filter.types.mapNotNull(::mapContentType).joinToString(",")
        if (typesParam.isNotEmpty()) {
            builder.addQueryParameter("types", typesParam)
        }
        val statusesParam = filter.states.mapNotNull(::mapMangaState).joinToString(",")
        if (statusesParam.isNotEmpty()) {
            builder.addQueryParameter("statuses", statusesParam)
        }
        return builder.build()
    }

    private fun parseSearchComic(jo: JSONObject): Manga? {
        val slug = jo.getStringOrNull("slug")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val title = jo.getStringOrNull("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val relUrl = "/comic/$slug"
        val tags = parseGenresArray(jo.optJSONArray("genres"))
        val authors = parseAuthors(jo.getStringOrNull("writer"))
        return Manga(
            id = generateUid(relUrl),
            title = title,
            altTitles = parseAltTitles(jo.getStringOrNull("original_name"), title),
            url = relUrl,
            publicUrl = "https://$domain$relUrl",
            rating = normalizeRating(jo.getDoubleOrDefault("rating_avg", 0.0), jo.getIntOrDefault("rating_count", 0)),
            contentRating = ContentRating.SAFE,
            coverUrl = jo.getStringOrNull("cover_image")?.toAbsoluteUrl(domain),
            tags = tags,
            state = parseStatus(jo.getStringOrNull("status")),
            authors = authors,
            description = jo.getStringOrNull("synopsis")?.trim()?.takeIf { it.isNotEmpty() },
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = extractSlugFromMangaUrl(manga.url)
        val canonicalUrl = "/comic/$slug"
        val publicUrl = "https://$domain$canonicalUrl"
        val doc = webClient.httpGet(publicUrl, getRequestHeaders()).parseHtml()

        val ldBook = parseBookJsonLd(doc)
        val title = ldBook?.getStringOrNull("name")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: manga.title

        val rawAlt = ldBook?.getStringOrNull("alternateName")
            ?: doc.selectFirst("h1 + h2")?.text()?.trim()
        val altTitles = parseAltTitles(rawAlt, title).ifEmpty { manga.altTitles }

        val description = ldBook?.getStringOrNull("description")?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("h3:matchesOwn(^Synopsis$) + p")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: manga.description

        val coverUrl = ldBook?.getStringOrNull("image")?.toAbsoluteUrl(domain)
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
                ?.toAbsoluteUrl(domain)
            ?: manga.coverUrl

        val ldAuthor = ldBook?.optJSONObject("author")?.getStringOrNull("name")
        val authors = parseAuthors(ldAuthor ?: doc.selectFirst("div:matchesOwn(^Author$) + div")?.text())
            .ifEmpty { manga.authors }

        val state = doc.selectFirst("div:matchesOwn(^Status$) + div")?.text()?.let(::parseStatus)
            ?: manga.state

        val tags = parseDetailTags(doc, ldBook).ifEmpty { manga.tags }

        val aggRating = ldBook?.optJSONObject("aggregateRating")
        val rating = if (aggRating != null) {
            normalizeRating(
                aggRating.getDoubleOrDefault("ratingValue", 0.0),
                aggRating.getIntOrDefault("ratingCount", 0),
                aggRating.getDoubleOrDefault("bestRating", 5.0),
            )
        } else {
            manga.rating
        }

        val chapters = parseChaptersFromNextData(doc, slug).ifEmpty {
            parseChaptersFromDom(doc, slug)
        }
        chapters.sortBy { it.number }

        return manga.copy(
            id = generateUid(canonicalUrl),
            title = title,
            altTitles = altTitles,
            url = canonicalUrl,
            publicUrl = publicUrl,
            rating = rating,
            contentRating = manga.contentRating ?: ContentRating.SAFE,
            coverUrl = coverUrl,
            tags = tags,
            state = state,
            authors = authors,
            description = description,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val parts = chapter.url.trim('/').split('/')
        val comicIdx = parts.indexOf("comic")
        val chapterIdx = parts.indexOf("chapter")
        val slug = if (comicIdx >= 0 && comicIdx + 1 < parts.size) parts[comicIdx + 1] else parts.first()
        val chapterNumber = if (chapterIdx >= 0 && chapterIdx + 1 < parts.size) {
            parts[chapterIdx + 1]
        } else {
            chapter.numberString() ?: parts.last()
        }

        val pages = ArrayList<MangaPage>()
        val seenIds = HashSet<String>()
        val headers = getRequestHeaders().newBuilder()
            .add("Accept", "application/json")
            .add("Referer", "https://$domain${chapter.url}")
            .build()

        var skip = 0
        while (true) {
            val apiUrl = HttpUrl.Builder()
                .scheme("https")
                .host(domain)
                .addPathSegment("api")
                .addPathSegment("comics")
                .addPathSegment(slug)
                .addPathSegment("chapter")
                .addPathSegment(chapterNumber)
                .addPathSegment("images")
                .addQueryParameter("skip", skip.toString())
                .addQueryParameter("take", IMAGES_PAGE_SIZE.toString())
                .build()

            val json = webClient.httpGet(apiUrl, headers).parseJson()
            val images = json.optJSONArray("images") ?: break
            if (images.length() == 0) break

            for (i in 0 until images.length()) {
                val imgObj = images.optJSONObject(i) ?: continue
                val imgUrl = imgObj.getStringOrNull("image_url")?.toAbsoluteUrl(domain) ?: continue
                val rawPageId = imgObj.getStringOrNull("id") ?: "$slug:$chapterNumber:${skip + i}"
                if (!seenIds.add(rawPageId)) continue
                pages.add(
                    MangaPage(
                        id = generateUid(rawPageId),
                        url = imgUrl,
                        preview = null,
                        source = source,
                    ),
                )
            }
            if (images.length() < IMAGES_PAGE_SIZE) break
            skip += images.length()
        }
        return pages
    }

    private fun parseBookJsonLd(doc: Document): JSONObject? {
        for (script in doc.select("script[type=\"application/ld+json\"]")) {
            val raw = script.data().trim()
            val obj = raw.toJSONObjectOrNull() ?: continue
            if (obj.optString("@type").equals("Book", ignoreCase = true)) {
                return obj
            }
        }
        return null
    }

    private fun parseDetailTags(doc: Document, ldBook: JSONObject?): Set<MangaTag> {
        val tags = LinkedHashSet<MangaTag>()
        ldBook?.optJSONArray("genre")?.let { arr ->
            for (i in 0 until arr.length()) {
                val name = arr.optString(i, "").trim()
                if (name.isNotEmpty()) {
                    tags.add(buildTag(name))
                }
            }
        }
        if (tags.isEmpty()) {
            for (link in doc.select("a[href^=\"/library?genres=\"], a[href^=\"/search?genres=\"]")) {
                val genre = link.attr("href")
                    .toAbsoluteUrl(domain)
                    .toHttpUrlOrNull()
                    ?.queryParameter("genres")
                    ?.trim()
                    ?: link.text().trim()
                if (genre.isNotEmpty()) {
                    tags.add(buildTag(genre))
                }
            }
        }
        return tags
    }

    private fun parseChaptersFromNextData(doc: Document, slug: String): MutableList<MangaChapter> {
        for (script in doc.select("script")) {
            val data = script.data()
            var searchFrom = 0
            while (true) {
                val pushIdx = data.indexOf(NEXT_F_PUSH_PREFIX, searchFrom)
                if (pushIdx < 0) break
                val arrayStart = pushIdx + NEXT_F_PUSH_PREFIX.length
                val payload = runCatching {
                    val parsed = JSONTokener(data.substring(arrayStart)).nextValue()
                    (parsed as? JSONArray)?.optString(1)
                }.getOrNull()
                if (payload != null && payload.contains("\"chapters\":[")) {
                    val chaptersJson = extractJsonArrayAfter(payload, "\"chapters\":")
                    if (chaptersJson != null) {
                        val arr = chaptersJson.toJSONArrayOrNull()
                        if (arr != null && arr.length() > 0) {
                            return mapChaptersJsonArray(arr, slug)
                        }
                    }
                }
                searchFrom = arrayStart + 1
            }
        }
        return ArrayList()
    }

    private fun mapChaptersJsonArray(arr: JSONArray, slug: String): MutableList<MangaChapter> {
        val chapters = ArrayList<MangaChapter>(arr.length())
        for (i in 0 until arr.length()) {
            val ch = arr.optJSONObject(i) ?: continue
            val numDouble = ch.optDouble("chapter_number", Double.NaN)
            if (numDouble.isNaN()) continue
            val numStr = numDouble.formatSimple()
            val chapterUrl = "/comic/$slug/chapter/$numStr"
            val rawTitle = ch.getStringOrNull("title")?.takeUnless { it == "null" }
            chapters.add(
                MangaChapter(
                    id = generateUid(chapterUrl),
                    title = cleanChapterTitle(rawTitle, numStr),
                    number = numDouble.toFloat(),
                    volume = 0,
                    url = chapterUrl,
                    scanlator = null,
                    uploadDate = parseIsoDate(ch.getStringOrNull("created_at")),
                    branch = null,
                    source = source,
                ),
            )
        }
        return chapters
    }

    private fun parseChaptersFromDom(doc: Document, slug: String): MutableList<MangaChapter> {
        val prefix = "/comic/$slug/chapter/"
        val chapters = ArrayList<MangaChapter>()
        val seenUrls = HashSet<String>()
        for (a in doc.select("a[href^=\"$prefix\"]")) {
            val href = a.attr("href").substringBefore('?').trim()
            if (!href.startsWith(prefix)) continue
            val text = a.text().trim()
            if (text.equals("Read First", ignoreCase = true)) continue
            if (!seenUrls.add(href)) continue
            val numStr = href.removePrefix(prefix).trim('/')
            val numFloat = numStr.toFloatOrNull() ?: continue
            val dashTitle = text.substringAfter('—', "").trim().ifEmpty { null }
            chapters.add(
                MangaChapter(
                    id = generateUid(href),
                    title = cleanChapterTitle(dashTitle, numStr),
                    number = numFloat,
                    volume = 0,
                    url = href,
                    scanlator = null,
                    uploadDate = 0L,
                    branch = null,
                    source = source,
                ),
            )
        }
        return chapters
    }

    private fun extractJsonArrayAfter(text: String, marker: String): String? {
        val markerIdx = text.indexOf(marker)
        if (markerIdx < 0) return null
        val start = text.indexOf('[', markerIdx + marker.length)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (inString) {
                when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun cleanChapterTitle(rawTitle: String?, numberStr: String): String? {
        val trimmed = rawTitle?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalizedPrefix = "Chapter $numberStr"
        return when {
            trimmed.equals(normalizedPrefix, ignoreCase = true) -> null
            trimmed.startsWith("$normalizedPrefix - ", ignoreCase = true) -> {
                trimmed.substring(normalizedPrefix.length + 3).trim().ifEmpty { null }
            }
            trimmed.startsWith("$normalizedPrefix:", ignoreCase = true) -> {
                trimmed.substring(normalizedPrefix.length + 1).trim().ifEmpty { null }
            }
            else -> trimmed
        }
    }

    private fun parseGenresArray(arr: JSONArray?): Set<MangaTag> {
        if (arr == null || arr.length() == 0) return emptySet()
        val tags = LinkedHashSet<MangaTag>(arr.length())
        for (i in 0 until arr.length()) {
            val name = arr.optJSONObject(i)?.getStringOrNull("name")?.trim() ?: continue
            tags.add(buildTag(name))
        }
        return tags
    }

    private fun buildTag(name: String): MangaTag = MangaTag(
        title = name.toTitleCase(sourceLocale),
        key = name,
        source = source,
    )

    private fun parseAltTitles(raw: String?, primaryTitle: String): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        val result = LinkedHashSet<String>()
        for (part in raw.split(',')) {
            val cleaned = part.trim()
            if (cleaned.isEmpty()) continue
            if (cleaned.equals(primaryTitle, ignoreCase = true)) continue
            if (cleaned.lowercase(Locale.ROOT) in PLACEHOLDER_VALUES) continue
            result.add(cleaned)
        }
        return result
    }

    private fun parseAuthors(raw: String?): Set<String> {
        val cleaned = raw?.trim()?.takeIf {
            it.isNotEmpty() && it.lowercase(Locale.ROOT) !in PLACEHOLDER_VALUES
        } ?: return emptySet()
        return setOf(cleaned)
    }

    private fun parseStatus(raw: String?): MangaState? = when (raw?.trim()?.uppercase(Locale.ROOT)) {
        "ONGOING" -> MangaState.ONGOING
        "COMPLETED" -> MangaState.FINISHED
        "HIATUS" -> MangaState.PAUSED
        else -> null
    }

    private fun normalizeRating(avg: Double, count: Int, bestRating: Double = 5.0): Float {
        if (count <= 0 || avg <= 0.0 || bestRating <= 0.0) return RATING_UNKNOWN
        return (avg / bestRating).toFloat().coerceIn(0f, 1f)
    }

    private fun parseIsoDate(raw: String?): Long {
        val str = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return 0L
        synchronized(ISO_DATE_FORMATS) {
            for (format in ISO_DATE_FORMATS) {
                val parsed = runCatching { format.parse(str)?.time }.getOrNull()
                if (parsed != null) return parsed
            }
        }
        return 0L
    }

    private fun extractSlugFromMangaUrl(url: String): String {
        val rel = url.toRelativeUrl(domain).trim('/')
        val afterPrefix = if (rel.startsWith("comic/")) rel.removePrefix("comic/") else rel
        return afterPrefix.substringBefore('/').substringBefore('?').substringBefore('#')
    }

    private fun mapSortOrder(order: SortOrder): String = when (order) {
        SortOrder.POPULARITY -> "Popular"
        SortOrder.ALPHABETICAL -> "Title A-Z"
        SortOrder.ALPHABETICAL_DESC -> "Title Z-A"
        else -> "Latest Update"
    }

    private fun mapContentType(type: ContentType): String? = when (type) {
        ContentType.MANGA -> "Manga"
        ContentType.MANHWA -> "Manhwa"
        ContentType.MANHUA -> "Manhua"
        else -> null
    }

    private fun mapMangaState(state: MangaState): String? = when (state) {
        MangaState.ONGOING -> "ONGOING"
        MangaState.FINISHED -> "COMPLETED"
        MangaState.PAUSED -> "HIATUS"
        else -> null
    }

    private class SearchPageResult(
        val items: List<Manga>,
        val totalPages: Int,
        val rawCount: Int,
    )

    private companion object {
        const val PAGE_SIZE = 6
        const val API_MAX_LIMIT = 50
        const val IMAGES_PAGE_SIZE = 50
        const val NEXT_F_PUSH_PREFIX = "self.__next_f.push("

        val PLACEHOLDER_VALUES = setOf("-", "null", "n/a", "unknown", "original")

        val DEFAULT_GENRES = listOf(
            "Action",
            "Adventure",
            "Comedy",
            "Drama",
            "Fantasy",
            "Horror",
            "Mystery",
            "Romance",
            "Slice of Life",
            "Supernatural",
            "Suspense",
        )

        val ISO_DATE_FORMATS = arrayOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            },
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            },
        )
    }
}

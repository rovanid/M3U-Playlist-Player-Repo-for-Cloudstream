package com.xr3ed.nontonanimeidxr

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class NontonAnimeIDProvider : MainAPI() {
    override var mainUrl = "https://s13.nontonanimeid.boats"
    override var name = "#Anime NontonAnimeIDXR"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        var context: Context? = null
        const val LYNK_MASK_PREFIX = "https://lynk.id/xr3ed#"

        fun maskUrl(url: String): String {
            if (url.isBlank() || url.contains("lynk.id")) return url
            return "$LYNK_MASK_PREFIX$url"
        }

        fun unmaskUrl(url: String): String {
            return if (url.contains("lynk.id")) url.substringAfterLast("#", "") else url
        }

        fun getType(t: String): TvType {
            return when {
                t.contains("Movie", true) -> TvType.AnimeMovie
                t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
                else -> TvType.Anime
            }
        }

        fun getStatus(t: String): ShowStatus {
            return when {
                t.contains("Currently", true) || t.contains("Ongoing", true) -> ShowStatus.Ongoing
                t.contains("Finished", true) || t.contains("Completed", true) || t.contains("Tamat", true) -> ShowStatus.Completed
                else -> ShowStatus.Completed
            }
        }
    }

    private val turnstileInterceptor = TurnstileInterceptor("cf_clearance")

    private suspend fun request(url: String, ref: String? = null): NiceResponse {
        return app.get(
            url,
            interceptor = turnstileInterceptor,
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "User-Agent" to TurnstileInterceptor.USER_AGENT,
            ),
            referer = ref
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Episode Terbaru",
        "$mainUrl/anime/?mode=&sort=series_tahun_newest&status=Currently+Airing&type=" to "Ongoing Anime",
        "$mainUrl/anime/?mode=&sort=series_tahun_newest&status=Finished+Airing&type=" to "Complete Anime",
        "$mainUrl/anime/?mode=&sort=series_popularity&status=&type=" to "Most Popular",
        "$mainUrl/anime/?mode=&sort=series_skor&status=&type=" to "Top Rating",
        "$mainUrl/anime/?mode=&sort=series_tahun_newest&status=&type=Movie" to "Anime Movie",
        "$mainUrl/anime/?mode=&sort=series_tahun_newest&status=&type=Live+Action" to "Live Action",
        "$mainUrl/anime/?genre%5B0%5D=action&sort=series_tahun_newest" to "Action",
        "$mainUrl/anime/?genre%5B0%5D=adventure&sort=series_tahun_newest" to "Adventure",
        "$mainUrl/anime/?genre%5B0%5D=comedy&sort=series_tahun_newest" to "Comedy",
        "$mainUrl/anime/?genre%5B0%5D=drama&sort=series_tahun_newest" to "Drama",
        "$mainUrl/anime/?genre%5B0%5D=fantasy&sort=series_tahun_newest" to "Fantasy",
        "$mainUrl/anime/?genre%5B0%5D=isekai&sort=series_tahun_newest" to "Isekai",
        "$mainUrl/anime/?genre%5B0%5D=romance&sort=series_tahun_newest" to "Romance",
        "$mainUrl/anime/?genre%5B0%5D=sci-fi&sort=series_tahun_newest" to "Sci-Fi",
        "$mainUrl/anime/?genre%5B0%5D=shounen&sort=series_tahun_newest" to "Shounen",
        "$mainUrl/anime/?genre%5B0%5D=slice-of-life&sort=series_tahun_newest" to "Slice of Life",
        "$mainUrl/anime/?genre%5B0%5D=supernatural&sort=series_tahun_newest" to "Supernatural"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) {
            request.data
        } else {
            if (request.data.contains("?")) {
                val parts = request.data.split("?", limit = 2)
                "${parts[0].trimEnd('/')}/page/$page/?${parts[1]}"
            } else {
                "${request.data.trimEnd('/')}/page/$page/"
            }
        }

        val req = runCatching { request(pageUrl) }.getOrNull()
        if (req == null) return newHomePageResponse(request.name, emptyList())

        mainUrl = getBaseUrl(req.url)
        val document = req.document

        val home = document.select("article.animeseries, .animeseries, a.as-anime-card, .result > ul > li, .result > li, .animposx, .animepost, .bsx, .bs, .listupd article, .listupd .bsx, .styles_itemContainer__GZ_z0, .as-anime-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = home.isNotEmpty()
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = if (this.tagName() == "a") this else this.selectFirst("a") ?: return null
        val rawHref = fixUrlNull(a.attr("href")) ?: return null
        val maskedHref = maskUrl(rawHref)

        val title = this.selectFirst("h3.title span, .title span, .as-anime-title, h2, .tt, h4, .entry-title, .title")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: a.attr("title").trim()

        if (title.isBlank()) return null

        val imgEl = this.selectFirst("img")
        val posterUrl = fixUrlNull(imgEl?.getImageAttr())
        val isDub = title.contains("dub", true)

        return newAnimeSearchResponse(title, maskedHref, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(dubExist = isDub, subExist = true)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        val req = runCatching { request(link) }.getOrNull() ?: return emptyList()
        mainUrl = getBaseUrl(req.url)
        val document = req.document

        return document.select("article.animeseries, .animeseries, .result > ul > li, .result > li, .animposx, .animepost, .bsx, .bs, .listupd article, .listupd .bsx, .styles_itemContainer__GZ_z0, .as-anime-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = unmaskUrl(url)
        val targetUrl = if (cleanUrl.contains("/anime/")) {
            cleanUrl
        } else {
            runCatching {
                request(cleanUrl).document.selectFirst("div.nvs.nvsc a, .breadcrumb a:contains(Anime)")?.attr("href")
            }.getOrNull() ?: cleanUrl
        }

        val req = runCatching { request(targetUrl) }.getOrNull() ?: return null
        mainUrl = getBaseUrl(req.url)
        val document = req.document

        val title = document.selectFirst("h1.entry-title.cs, h1.entry-title, .entry-title")?.text()
            ?.replace(Regex("^Nonton\\s+", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("\\s+Sub\\s+Indo$", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?: return null

        val poster = document.selectFirst(".anime-card__sidebar img, .poster > img, .thumb > img, .single-info img")?.getImageAttr()
        val tags = document.select(".anime-card__genres .genre-tag, .tagline > a, .genxed a, .genre-info a").map { it.text() }

        val year = Regex("(19|20)\\d{2}").find(
            document.select(".details-list li:contains(Aired), .info-item.season, .bottomtitle, .info-content, .spe").text()
        )?.value?.toIntOrNull()

        val statusText = document.selectFirst(".info-item.status-finish, .info-item.status-airing, span.statusseries, .status, .spe span:contains(Status)")
            ?.text()
            ?.trim()
            .orEmpty()
        val status = getStatus(statusText)

        val typeText = document.select("span.typeseries, .anime-card__score, .details-list, .bottomtitle, .spe span:contains(Type)")
            .text()
            .trim()
        val type = getType(typeText)

        val rating = Regex("(\\d+(?:\\.\\d+)?)").find(
            document.selectFirst(".anime-card__score")?.text().orEmpty()
        )?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            ?: document.select("span.nilaiseries, .rating strong, .score").text().trim().toDoubleOrNull()

        val description = document.select(".synopsis-prose > p, .entry-content.seriesdesc > p, .desc > p, .entry-content > p")
            .text()
            .trim()
        val trailer = document.selectFirst("a.trailerbutton, .trailer a")?.attr("href")

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        val ids = resolveAnimeIds(listOf(title), type, year, tracker?.malId, tracker?.aniId?.toIntOrNull())
        val malId = ids.malId
        val aniId = ids.aniId

        var animeMetaData: MetaAnimeData? = null
        var tmdbid: Int? = null
        var kitsuid: String? = null

        if (malId != null || aniId != null) {
            try {
                animeMetaData = fetchAniZipMeta(malId, aniId)
                tmdbid = animeMetaData?.mappings?.themoviedbId
                kitsuid = animeMetaData?.mappings?.kitsuId
            } catch (_: Exception) {}
        }

        val logoUrl = fetchTmdbLogoUrl(
            tmdbAPI = "https://api.themoviedb.org/3",
            apiKey = "98ae14df2b8d8f8f8136499daf79f0e0",
            type = type,
            tmdbId = tmdbid,
            appLangCode = "en"
        )

        val backgroundposter = animeMetaData?.images?.find { it.coverType == "Fanart" }?.url ?: tracker?.cover

        val rawEpisodes = if (document.select(".episode-list-items a.episode-item").isNotEmpty()) {
            document.select(".episode-list-items a.episode-item")
                .mapNotNull {
                    val epText = it.selectFirst(".ep-title, .episode-title, span")?.text()?.trim()
                        ?: it.text().trim()
                    val episode = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
                        .find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("(\\d+)").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val link = it.attr("href").ifBlank { it.attr("data-episode-url") }
                        .takeIf { v -> v.isNotBlank() }
                        ?.let { v -> fixUrl(v) }
                        ?: return@mapNotNull null

                    newEpisode(maskUrl(link)) { this.episode = episode }
                }
                .sortedBy { it.episode ?: Int.MAX_VALUE }
        } else if (document.select("button.buttfilter").isNotEmpty()) {
            val id = document.select("input[name=series_id]").attr("value")
            val numEp = document.selectFirst(".latestepisode > a")?.text()?.replace(Regex("\\D"), "")
                .let { if (it.isNullOrBlank()) "1000" else it }
            val ajaxHtml = runCatching {
                app.post(
                    url = "$mainUrl/wp-admin/admin-ajax.php",
                    interceptor = turnstileInterceptor,
                    data = mapOf(
                        "misha_number_of_results" to numEp,
                        "misha_order_by" to "date-DESC",
                        "action" to "mishafilter",
                        "series_id" to id
                    ),
                    referer = targetUrl,
                    headers = mapOf(
                        "User-Agent" to TurnstileInterceptor.USER_AGENT,
                    )
                ).parsed<EpResponse>().content
            }.getOrNull()

            if (ajaxHtml != null) {
                Jsoup.parse(ajaxHtml).select("li").mapNotNull {
                    val link = it.selectFirst("a")?.attr("href")?.let { v -> fixUrl(v) } ?: return@mapNotNull null
                    val epText = it.selectFirst("a")?.text().orEmpty()
                    val episode = Regex("Episode\\s?(\\d+)").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    newEpisode(maskUrl(link)) { this.episode = episode }
                }.reversed()
            } else {
                emptyList()
            }
        } else {
            document.select("ul.misha_posts_wrap2 > li, .lstepi > li, .episodelist > ul > li, .episodelist li").mapNotNull {
                val a = it.selectFirst("a") ?: return@mapNotNull null
                val link = fixUrl(a.attr("href"))
                val epText = a.text()
                val episode = Regex("Episode\\s?(\\d+)").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(maskUrl(link)) { this.episode = episode }
            }.reversed()
        }

        val extractedEpisodes = if (rawEpisodes.isEmpty()) {
            listOf(
                newEpisode(maskUrl(cleanUrl)) {
                    this.name = title
                    this.episode = 1
                }
            )
        } else {
            rawEpisodes
        }

        val episodes = extractedEpisodes.amap { ep ->
            var episodeNum = ep.episode

            if (type == TvType.AnimeMovie && episodeNum == null) {
                episodeNum = 1
            }

            val episodeKey = episodeNum?.toString()
            val metaEp = if (episodeKey != null) animeMetaData?.episodes?.get(episodeKey) else null

            val epOverview = metaEp?.overview
            val finalOverview = epOverview?.takeIf { it.isNotBlank() }

            ep.apply {
                this.name = if (type == TvType.AnimeMovie) {
                    animeMetaData?.titles?.get("en") ?: animeMetaData?.titles?.get("ja") ?: title
                } else {
                    metaEp?.title?.get("en") ?: metaEp?.title?.get("ja") ?: ep.name
                }
                this.episode = episodeNum
                this.score = Score.from10(metaEp?.rating)
                this.posterUrl = metaEp?.image?.takeIf { it.isNotBlank() }
                    ?: animeMetaData?.images?.firstOrNull()?.url
                    ?: backgroundposter
                    ?: tracker?.image
                    ?: poster
                    ?: ""
                this.description = finalOverview
                this.addDate(metaEp?.airDateUtc)
                this.runTime = metaEp?.runtime
            }
        }

        val recommendations = document.select(".related a.as-anime-card, .result > li, .animeseries, .animposx, .bsx, .bs")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val apiDescription = animeMetaData?.description?.replace(Regex("<.*?>"), "")
        val rawPlot = apiDescription?.takeIf { it.isNotBlank() }
            ?: animeMetaData?.episodes?.get("1")?.overview?.takeIf { it.isNotBlank() }
            ?: fetchAniListPlot(malId, aniId)

        val finalPlot = if (!rawPlot.isNullOrBlank()) {
            rawPlot
        } else {
            description
        }

        return newAnimeLoadResponse(title, maskUrl(cleanUrl), type) {
            this.engName = animeMetaData?.titles?.get("en") ?: title
            this.japName = animeMetaData?.titles?.get("ja") ?: animeMetaData?.titles?.get("x-jat")
            this.posterUrl = tracker?.image ?: poster
            this.backgroundPosterUrl = backgroundposter
            try { this.logoUrl = logoUrl } catch (_: Throwable) {}
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            this.showStatus = status
            this.score = rating?.let { Score.from10(it) } ?: Score.from10(animeMetaData?.episodes?.get("1")?.rating)
            this.plot = finalPlot
            addTrailer(trailer)
            this.tags = tags
            this.recommendations = recommendations
            addMalId(malId)
            addAniListId(aniId)
            try { addKitsuId(kitsuid) } catch (_: Throwable) {}
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanData = unmaskUrl(data)
        val req = runCatching { request(cleanData) }.getOrNull() ?: return false
        val document = req.document
        val iframeLinks = linkedSetOf<String>()

        fun normalizeUrl(value: String?): String? {
            val raw = value
                ?.trim()
                ?.replace("\\/", "/")
                ?.replace("&amp;", "&")
                ?.takeIf { it.isNotBlank() }
                ?: return null
            return when {
                raw.startsWith("about:") -> null
                raw.startsWith("http://") || raw.startsWith("https://") -> raw
                else -> fixUrl(raw)
            }
        }

        fun collectIframes(doc: Document) {
            doc.select("iframe").forEach { iframe ->
                normalizeUrl(iframe.attr("data-src").ifEmpty { iframe.attr("data-litespeed-src") }.ifEmpty { iframe.attr("src") })
                    ?.let { iframeLinks.add(it) }
            }
        }

        collectIframes(document)

        val extraScriptElement = document.selectFirst("script#ajax_video-js-extra")
        val ajaxConfigScript = when {
            extraScriptElement == null -> ""
            extraScriptElement.hasAttr("src") && extraScriptElement.attr("src").contains("base64,") -> {
                base64Decode(extraScriptElement.attr("src").substringAfter("base64,"))
            }
            else -> extraScriptElement.data().ifBlank { extraScriptElement.html() }
        }

        val ajaxUrl = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
            .find(ajaxConfigScript)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.let { fixUrl(it) }
            ?: "$mainUrl/wp-admin/admin-ajax.php"

        val nonce = Regex("\"nonce\"\\s*:\\s*\"([^\"]+)\"")
            .find(ajaxConfigScript)
            ?.groupValues
            ?.getOrNull(1)

        if (!nonce.isNullOrBlank()) {
            document.select(".serverplayer[data-post][data-type][data-nume], .container1 > ul > li[data-post][data-type][data-nume], ul#server li[data-post][data-type][data-nume]")
                .forEach { serverItem ->
                    val dataPost = serverItem.attr("data-post")
                    val dataNume = serverItem.attr("data-nume")
                    val serverName = serverItem.attr("data-type").lowercase()
                    if (dataPost.isBlank() || dataNume.isBlank() || serverName.isBlank()) return@forEach

                    val response = runCatching {
                        app.post(
                            url = ajaxUrl,
                            interceptor = turnstileInterceptor,
                            data = mapOf(
                                "action" to "player_ajax",
                                "nonce" to nonce,
                                "serverName" to serverName,
                                "nume" to dataNume,
                                "post" to dataPost,
                            ),
                            referer = cleanData,
                            headers = mapOf(
                                "X-Requested-With" to "XMLHttpRequest",
                                "Origin" to mainUrl,
                                "User-Agent" to TurnstileInterceptor.USER_AGENT,
                            )
                        ).text
                    }.getOrNull()

                    if (!response.isNullOrBlank()) {
                        collectIframes(Jsoup.parse(response))
                    }
                }
        }

        iframeLinks.toList().amap { link ->
            val nestedLink = if (link.contains("/video-frame/") || link.contains("/video-embed/")) {
                runCatching {
                    request(link, ref = cleanData).document.selectFirst("iframe")?.let { iframe ->
                        normalizeUrl(iframe.attr("data-src").ifEmpty { iframe.attr("src") })
                    }
                }.getOrNull()
            } else {
                null
            }
            loadExtractor(nestedLink ?: link, "$mainUrl/", subtitleCallback, callback)
        }

        document.select(".listlink a, .download-link a, .dlx a, .soraurlx a").amap { a ->
            val href = normalizeUrl(a.attr("href"))
            if (href != null && !href.contains("javascript", true)) {
                try {
                    val realUrl = app.get(href, allowRedirects = true).url
                    if (realUrl != href && realUrl.isNotBlank()) {
                        loadExtractor(realUrl, "$mainUrl/", subtitleCallback, callback)
                    } else {
                        loadExtractor(href, "$mainUrl/", subtitleCallback, callback)
                    }
                } catch (_: Exception) {
                    loadExtractor(href, "$mainUrl/", subtitleCallback, callback)
                }
            }
        }

        return true
    }

    private fun getBaseUrl(url: String): String {
        return runCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrDefault(mainUrl)
    }

    private fun Element.getImageAttr(): String? {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("data-original") -> this.attr("abs:data-original")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private data class EpResponse(
        @JsonProperty("posts") val posts: String? = null,
        @JsonProperty("max_page") val max_page: Int? = null,
        @JsonProperty("found_posts") val found_posts: Int? = null,
        @JsonProperty("content") val content: String = ""
    )
}

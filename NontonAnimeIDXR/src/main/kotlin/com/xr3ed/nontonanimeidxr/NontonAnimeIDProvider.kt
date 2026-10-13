package com.xr3ed.nontonanimeidxr

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.runBlocking

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

    private val turnstileInterceptor = TurnstileInterceptor()

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

        val home = document.select("article.animeseries, .animeseries, a.as-anime-card, .result > ul > li, .result > li, .animposx, .animepost, .bsx, .bs, .listupd article, .listupd .bsx, .styles_itemContainer__GZ_z0, .as-anime-item, .ml-item, .latest-release article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = home.isNotEmpty()
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a = if (this.tagName() == "a") this else this.selectFirst("a") ?: return null
        val rawHref = fixUrlNull(a.attr("href")) ?: return null
        val maskedHref = maskUrl(rawHref)

        val rawTitle = this.selectFirst("h3.title span, .title span, .as-anime-title, h2, .tt, h4, .entry-title, .title")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: a.attr("title").trim()

        if (rawTitle.isBlank()) return null

        val title = rawTitle
            .replace(Regex("(?i)^Nonton\\s+(?:Anime\\s+)?"), "")
            .replace(Regex("(?i)\\s+Sub(?:title)?\\s+Indo(?:nesia)?"), "")
            .trim()

        val imgEl = this.selectFirst("img")
        val posterUrl = imgEl?.getImageAttr()
        val isDub = rawTitle.contains("dub", true)

        val epText = this.selectFirst(".epx, .eps, .dtla, .episode, span.episode, .as-anime-episode, .as-episode-title, span.ep, .bt .ep, .typez")?.text()
        val epNum = Regex("(?i)(?:episode|eps|ep)\\s*(\\d+)").find(epText.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?i)(?:episode|eps|ep)\\s*(\\d+)").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(\\d+)").find(epText.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()

        val typeText = this.selectFirst(".type, .typez, .typeseries")?.text().orEmpty()
        val type = if (typeText.contains("Movie", true) || rawTitle.contains("Movie", true)) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(title, maskedHref, type) {
            this.posterUrl = posterUrl
            this.quality = SearchQuality.HD
            if (epNum != null) {
                addSub(epNum)
            } else {
                addDubStatus(dubExist = isDub, subExist = true)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        val req = runCatching { request(link) }.getOrNull() ?: return emptyList()
        mainUrl = getBaseUrl(req.url)
        val document = req.document

        return document.select("article.animeseries, .animeseries, a.as-anime-card, .result > ul > li, .result > li, .animposx, .animepost, .bsx, .bs, .listupd article, .listupd .bsx, .styles_itemContainer__GZ_z0, .as-anime-item, .ml-item, .latest-release article")
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

        val rawTitle = document.selectFirst("h1.entry-title.cs, h1.entry-title, .entry-title, h1")?.text()
            ?.replace(Regex("(?i)^Nonton\\s+(?:Anime\\s+)?"), "")
            ?.replace(Regex("(?i)\\s+Sub(?:title)?\\s+Indo(?:nesia)?$"), "")
            ?.trim()
            ?: return null
        val title = rawTitle

        // Poster situs
        val sitePoster = document.selectFirst(
            ".anime-card__sidebar img, .poster > img, .thumb > img, .single-info img, .infox .thumb img, meta[property='og:image']"
        )?.getImageAttr()

        // Backdrop / Background poster situs
        val siteBackdrop = document.selectFirst(
            ".bigcover img, .banner img, .anime-cover img, .cover img, .backdrop img, .single-info .backdrop img, div.bigcover"
        )?.let { el ->
            el.getImageAttr() ?: Regex("url\\(['\"]?(.*?)['\"]?\\)").find(el.attr("style"))?.groupValues?.getOrNull(1)
        }

        val tags = document.select(".anime-card__genres .genre-tag, .tagline > a, .genxed a, .genre-info a, a[rel='tag']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

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

        // Sinopsis patokan dari situs
        val siteDescription = document.select(".synopsis-prose > p, .entry-content.seriesdesc > p, .desc > p, .entry-content > p, .synopsis-prose, .entry-content.seriesdesc, .entry-content, .desc")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.startsWith("Nonton anime", ignoreCase = true) && !it.contains("Streaming Anime", ignoreCase = true) }
            .distinct()
            .joinToString("\n\n")

        // Trailer situs
        val siteTrailer = document.selectFirst("a.trailerbutton, .trailer a, a[href*='youtube.com/watch'], a[href*='youtu.be'], iframe[src*='youtube.com/embed']")
            ?.let { el ->
                val link = el.attr("href").ifBlank { el.attr("src") }
                if (link.contains("youtube.com") || link.contains("youtu.be")) fixUrl(link) else null
            }

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
        val logo = logoUrl ?: animeMetaData?.images?.find { it.coverType.equals("Clearlogo", true) || it.coverType.equals("Logo", true) }?.url

        val finalPoster = sitePoster?.takeIf { it.isNotBlank() }
            ?: tracker?.image
            ?: animeMetaData?.images?.find { it.coverType == "Poster" }?.url
            ?: tracker?.cover

        val backgroundposter = siteBackdrop?.takeIf { it.isNotBlank() }
            ?: ids.bannerImage?.takeIf { it.isNotBlank() }
            ?: animeMetaData?.images?.find { it.coverType == "Fanart" || it.coverType == "Banner" }?.url
            ?: tracker?.cover
            ?: finalPoster

        val trailer = siteTrailer ?: ids.trailerUrl

        // Daftar episode: patokan jumlah episode dari situs
        val rawEpisodes = mutableListOf<Episode>()

        val domEpElements = document.select(".episode-list-items a.episode-item, ul.misha_posts_wrap2 > li, .misha_posts_wrap > li, .lstepi > li, .episodelist > ul > li, .episodelist li, .singl-ep li")
        if (domEpElements.isNotEmpty()) {
            domEpElements.mapNotNullTo(rawEpisodes) { el ->
                val a = if (el.tagName() == "a") el else el.selectFirst("a") ?: return@mapNotNullTo null
                val link = fixUrlNull(a.attr("href").ifBlank { a.attr("data-episode-url") }) ?: return@mapNotNullTo null
                val epText = a.selectFirst(".ep-title, .episode-title, span")?.text()?.trim() ?: a.text().trim()
                val epNum = Regex("(?i)Episode\\s*(\\d+)").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("(?i)Ep\\s*(\\d+)").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("(\\d+)").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val cleanEpName = epText.replace(Regex("(?i)\\s+Sub(?:title)?\\s+Indo(?:nesia)?"), "").trim()
                newEpisode(maskUrl(link)) {
                    this.name = cleanEpName.ifBlank { "Episode ${epNum ?: 1}" }
                    this.episode = epNum
                }
            }
        }

        if (rawEpisodes.isEmpty() && document.select("button.buttfilter, input[name=series_id]").isNotEmpty()) {
            val id = document.select("input[name=series_id]").attr("value")
            val numEp = document.selectFirst(".latestepisode > a")?.text()?.replace(Regex("\\D"), "")
                .let { if (it.isNullOrBlank()) "9999" else it }
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

            if (!ajaxHtml.isNullOrBlank()) {
                Jsoup.parse(ajaxHtml).select("li").mapNotNullTo(rawEpisodes) { el ->
                    val a = el.selectFirst("a") ?: return@mapNotNullTo null
                    val link = fixUrlNull(a.attr("href")) ?: return@mapNotNullTo null
                    val epText = a.text().trim()
                    val epNum = Regex("(?i)Episode\\s*(\\d+)").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("(?i)Ep\\s*(\\d+)").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("(\\d+)").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val cleanEpName = epText.replace(Regex("(?i)\\s+Sub(?:title)?\\s+Indo(?:nesia)?"), "").trim()
                    newEpisode(maskUrl(link)) {
                        this.name = cleanEpName.ifBlank { "Episode ${epNum ?: 1}" }
                        this.episode = epNum
                    }
                }
            }
        }

        val sortedEpisodes = if (rawEpisodes.isNotEmpty()) {
            rawEpisodes.distinctBy { it.data }.sortedBy { it.episode ?: Int.MAX_VALUE }
        } else {
            listOf(
                newEpisode(maskUrl(cleanUrl)) {
                    this.name = title
                    this.episode = 1
                }
            )
        }

        val episodes = sortedEpisodes.amap { ep ->
            var episodeNum = ep.episode

            if (type == TvType.AnimeMovie && episodeNum == null) {
                episodeNum = 1
            }

            val episodeKey = episodeNum?.toString()
            val metaEp = if (episodeKey != null) animeMetaData?.episodes?.get(episodeKey) else null
            val epOverview = metaEp?.overview?.takeIf { it.isNotBlank() }

            ep.apply {
                this.name = if (type == TvType.AnimeMovie) {
                    animeMetaData?.titles?.get("en") ?: animeMetaData?.titles?.get("ja") ?: title
                } else {
                    ep.name?.takeIf { it.isNotBlank() } ?: metaEp?.title?.get("en") ?: metaEp?.title?.get("ja") ?: "Episode $episodeNum"
                }
                this.episode = episodeNum
                this.score = Score.from10(metaEp?.rating)
                this.posterUrl = metaEp?.image?.takeIf { it.isNotBlank() }
                    ?: animeMetaData?.images?.firstOrNull()?.url
                    ?: backgroundposter
                    ?: tracker?.image
                    ?: finalPoster
                    ?: ""
                this.description = epOverview
                this.addDate(metaEp?.airDateUtc)
                this.runTime = metaEp?.runtime
            }
        }

        // Rekomendasi
        val recommendations = document.select(
            ".related article, .related .animeseries, .related .bsx, .related a.as-anime-card, .relat article, .relat .bsx, .rekomendasi article, .rekomendasi .bsx, .similars article, div.relat article, aside#sidebar article"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .ifEmpty {
                document.select("article.animeseries, .animeseries, .animposx, .bsx").take(15)
                    .mapNotNull { it.toSearchResult() }
                    .filter { it.url != maskUrl(cleanUrl) }
                    .distinctBy { it.url }
            }

        val apiDescription = animeMetaData?.description?.replace(Regex("<.*?>"), "")?.trim()
        val rawPlot = apiDescription?.takeIf { it.isNotBlank() }
            ?: animeMetaData?.episodes?.get("1")?.overview?.takeIf { it.isNotBlank() }
            ?: fetchAniListPlot(malId, aniId)

        val finalPlot = siteDescription.ifBlank { rawPlot.orEmpty() }

        return newAnimeLoadResponse(title, maskUrl(cleanUrl), type) {
            this.engName = animeMetaData?.titles?.get("en") ?: title
            this.japName = animeMetaData?.titles?.get("ja") ?: animeMetaData?.titles?.get("x-jat")
            this.posterUrl = finalPoster
            this.backgroundPosterUrl = backgroundposter
            try { this.logoUrl = logo } catch (_: Throwable) {}
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
        val htmlText = req.text

        // 1. Collect all server buttons from the web page
        val serverButtons = document.select(
            ".serverplayer, " +
            ".container1 > ul > li, " +
            "ul#server li, " +
            "ul.player li, " +
            ".player > li, " +
            ".servers li, " +
            ".servervideo li, " +
            ".servervideo a, " +
            ".server-item, " +
            "[data-post][data-nume], " +
            "[data-post][data-type]"
        )

        data class ServerItem(
            val post: String,
            val nume: String,
            val type: String,
            val label: String,
            val isActive: Boolean
        )

        val parsedServers = mutableListOf<ServerItem>()
        val seenServerKeys = mutableSetOf<String>()

        serverButtons.forEach { el ->
            val post = el.attr("data-post").ifBlank { el.attr("data-id") }
            val nume = el.attr("data-nume").ifBlank { el.attr("data-num") }
            val type = el.attr("data-type").ifBlank { el.attr("data-server") }.ifBlank { el.attr("data-name") }
            val label = el.text().trim().replace(Regex("\\s+"), " ").removeSuffix(":").trim().ifBlank { type }
            val isActive = el.hasClass("on") || el.hasClass("active") || el.hasClass("selected")

            val key = "$post-$nume-${type.lowercase()}-${label.lowercase()}"
            if (post.isNotBlank() && nume.isNotBlank() && seenServerKeys.add(key)) {
                parsedServers.add(ServerItem(post, nume, type, label, isActive))
            }
        }

        // Active server label on initial load (matches the active button or first button)
        val activeServer = parsedServers.firstOrNull { it.isActive } ?: parsedServers.firstOrNull()
        val defaultActiveLabel = activeServer?.label ?: "Server Video"

        // Collect initial iframes from main document
        val initialIframes = mutableListOf<String>()
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("data-litespeed-src") }.ifEmpty { iframe.attr("src") }
            normalizeUrl(src)?.let { initialIframes.add(it) }
        }

        // Map of serverLabel -> Set of embed URLs
        val serverToUrls = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

        // Associate initial iframes with the active server
        if (initialIframes.isNotEmpty()) {
            serverToUrls.getOrPut(defaultActiveLabel) { mutableSetOf() }.addAll(initialIframes)
        }

        // Find AJAX config (nonce & ajaxUrl)
        val dataScripts = document.select("script[src*=\"base64,\"]").mapNotNull { el ->
            val src = el.attr("src")
            val b64 = src.substringAfter("base64,")
            runCatching {
                String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()
        }.joinToString("\n")

        val allScripts = (document.select("script").map { it.data().ifBlank { it.html() } } + dataScripts).joinToString("\n")
        var ajaxUrl = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
            .find(allScripts)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.let { fixUrl(it) }
            ?: "$mainUrl/wp-admin/admin-ajax.php"

        var nonce = Regex("\"nonce\"\\s*:\\s*\"([^\"]+)\"")
            .find(allScripts)
            ?.groupValues
            ?.getOrNull(1)

        if (nonce.isNullOrBlank()) {
            nonce = Regex("""(?:nonce|ajax_nonce)["']?\s*:\s*["']([^"']+)["']""").find(htmlText)?.groupValues?.getOrNull(1)
        }

        // If nonce found, query AJAX for all server buttons concurrently
        if (!nonce.isNullOrBlank() && parsedServers.isNotEmpty()) {
            parsedServers.amap { server ->
                val response = runCatching {
                    app.post(
                        url = ajaxUrl,
                        interceptor = turnstileInterceptor,
                        data = mapOf(
                            "action" to "player_ajax",
                            "nonce" to nonce,
                            "serverName" to server.type,
                            "type" to server.type,
                            "nume" to server.nume,
                            "post" to server.post,
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
                    val urls = extractEmbedUrls(response)
                    if (urls.isNotEmpty()) {
                        serverToUrls.getOrPut(server.label) { mutableSetOf() }.addAll(urls)
                    }
                }
            }
        }

        // Resolve each server's streams
        serverToUrls.entries.toList().amap { (label, urls) ->
            urls.toList().amap { url ->
                resolveServerStream(url, label, cleanData, subtitleCallback, callback)
            }
        }

        // Extract download section links as well
        document.select(".listlink a, .download-link a, .dlx a, .soraurlx a").amap { a ->
            val href = normalizeUrl(a.attr("href"))
            val dlLabel = a.text().trim().ifBlank { "Download" }
            if (href != null && !href.contains("javascript", true)) {
                val realUrl = runCatching {
                    val r = app.get(href, allowRedirects = true).url
                    if (r.isNotBlank() && r != href) r else href
                }.getOrDefault(href)

                resolveServerStream(realUrl, dlLabel, "$mainUrl/", subtitleCallback, callback)
            }
        }

        return true
    }

    private fun normalizeUrl(value: String?): String? {
        val raw = value
            ?.trim()
            ?.replace("\\/", "/")
            ?.replace("&amp;", "&")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return when {
            raw.startsWith("about:") -> null
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("//") -> "https:$raw"
            else -> fixUrl(raw)
        }
    }

    private fun extractEmbedUrls(rawText: String): List<String> {
        val results = mutableListOf<String>()
        val cleanRaw = rawText.replace("\\/", "/").replace("&amp;", "&")

        if (cleanRaw.trim().startsWith("{") && cleanRaw.trim().endsWith("}")) {
            Regex(""""(?:html|embed_url|url|src|file|data)":\s*"([^"]+)"""")
                .findAll(cleanRaw)
                .forEach { match ->
                    val v = match.groupValues[1].replace("\\/", "/").replace("&amp;", "&")
                    normalizeUrl(v)?.let { results.add(it) }
                }
        }

        val doc = Jsoup.parse(cleanRaw)
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("data-litespeed-src") }.ifEmpty { iframe.attr("src") }
            normalizeUrl(src)?.let { results.add(it) }
        }
        doc.select("source, video").forEach { v ->
            val src = v.attr("src").ifEmpty { v.attr("data-src") }
            normalizeUrl(src)?.let { results.add(it) }
        }

        if (results.isEmpty()) {
            Regex("""https?://[^\s"'<>\\]+""").findAll(cleanRaw).forEach { match ->
                val u = match.value
                if (u.contains(".m3u8") || u.contains(".mp4") || u.contains("/embed") || u.contains("/v/") || u.contains("/e/") || u.contains("video")) {
                    normalizeUrl(u)?.let { results.add(it) }
                }
            }
        }
        return results.distinct()
    }

    private suspend fun unwrapIntermediateUrl(url: String, referer: String): String {
        var currentUrl = url
        for (i in 0 until 3) {
            if (!currentUrl.contains("/video-frame/") && !currentUrl.contains("/video-embed/") && !currentUrl.contains("/embed/")) {
                break
            }
            val nextUrl = runCatching {
                val resp = app.get(
                    url = currentUrl,
                    referer = referer,
                    headers = mapOf("User-Agent" to TurnstileInterceptor.USER_AGENT)
                )
                val doc = resp.document
                val innerIframe = doc.selectFirst("iframe")?.let {
                    normalizeUrl(it.attr("data-src").ifEmpty { it.attr("data-litespeed-src") }.ifEmpty { it.attr("src") })
                }
                innerIframe
            }.getOrNull()

            if (!nextUrl.isNullOrBlank() && nextUrl != currentUrl) {
                currentUrl = nextUrl
            } else {
                break
            }
        }
        return currentUrl
    }

    private suspend fun resolveServerStream(
        initialEmbedUrl: String,
        serverLabel: String,
        pageReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var foundAny = false
        val seenStreamUrls = HashSet<String>()

        val proxyCallback: (ExtractorLink) -> Unit = { rawLink ->
            val cleanStreamUrl = rawLink.url.trim()
            if (cleanStreamUrl.isNotBlank() && seenStreamUrls.add(cleanStreamUrl)) {
                foundAny = true
                val isM3u8 = rawLink.type == ExtractorLinkType.M3U8 || cleanStreamUrl.contains(".m3u8", ignoreCase = true)
                var handledMulti = false
                if (isM3u8) {
                    val multiTracks = runCatching {
                        runBlocking {
                            M3u8Helper.generateM3u8(
                                source = serverLabel,
                                streamUrl = cleanStreamUrl,
                                referer = if (rawLink.referer.isNotBlank()) rawLink.referer else pageReferer,
                                headers = rawLink.headers
                            )
                        }
                    }.getOrNull()

                    if (!multiTracks.isNullOrEmpty()) {
                        handledMulti = true
                        multiTracks.forEach { track ->
                            val trackQualityStr = Qualities.getStringByInt(track.quality)
                            runBlocking {
                                callback.invoke(
                                    newExtractorLink(
                                        source = serverLabel,
                                        name = "$serverLabel $trackQualityStr",
                                        url = track.url,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = track.referer
                                        this.quality = track.quality
                                        this.headers = track.headers
                                    }
                                )
                            }
                        }
                    }
                }

                if (!handledMulti) {
                    val qualityInt = if (rawLink.quality > 0) rawLink.quality else detectQuality(serverLabel)
                    val qualityLabel = if (qualityInt > 0) " " + Qualities.getStringByInt(qualityInt) else ""
                    runBlocking {
                        callback.invoke(
                            newExtractorLink(
                                source = serverLabel,
                                name = "$serverLabel$qualityLabel",
                                url = cleanStreamUrl,
                                type = rawLink.type
                            ) {
                                this.referer = if (rawLink.referer.isNotBlank()) rawLink.referer else pageReferer
                                this.quality = qualityInt
                                this.headers = rawLink.headers
                                this.extractorData = rawLink.extractorData
                            }
                        )
                    }
                }
            }
        }

        val resolvedUrl = unwrapIntermediateUrl(initialEmbedUrl, pageReferer)

        runCatching {
            loadExtractor(resolvedUrl, pageReferer, subtitleCallback, proxyCallback)
        }

        if (foundAny) return

        extractDeepVideo(resolvedUrl, serverLabel, pageReferer, subtitleCallback, proxyCallback)
    }

    private suspend fun extractDeepVideo(
        url: String,
        serverLabel: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (url.endsWith(".m3u8", ignoreCase = true) || url.contains(".m3u8?", ignoreCase = true)) {
            val tracks = runCatching {
                M3u8Helper.generateM3u8(serverLabel, url, referer)
            }.getOrNull()
            if (!tracks.isNullOrEmpty()) {
                tracks.forEach(callback)
                return
            }
            callback.invoke(
                newExtractorLink(serverLabel, serverLabel, url, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.quality = detectQuality(serverLabel)
                }
            )
            return
        }

        if (url.endsWith(".mp4", ignoreCase = true) || url.contains(".mp4?", ignoreCase = true)) {
            callback.invoke(
                newExtractorLink(serverLabel, serverLabel, url, ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.quality = detectQuality(serverLabel)
                }
            )
            return
        }

        val res = runCatching {
            app.get(
                url = url,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to TurnstileInterceptor.USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            )
        }.getOrNull() ?: return

        val html = res.text
        val doc = res.document

        // Try decrypting Kotakanime encrypted script if present
        val kotakM3u8 = KotakanimeidLink.decryptKotakScript(html)
        if (!kotakM3u8.isNullOrBlank()) {
            val tracks = runCatching { M3u8Helper.generateM3u8(serverLabel, kotakM3u8, url) }.getOrNull()
            if (!tracks.isNullOrEmpty()) {
                tracks.forEach(callback)
                return
            }
            callback.invoke(
                newExtractorLink(serverLabel, serverLabel, kotakM3u8, ExtractorLinkType.M3U8) {
                    this.referer = url
                    this.quality = detectQuality(serverLabel)
                }
            )
            return
        }

        doc.selectFirst("iframe")?.let { iframe ->
            val innerSrc = normalizeUrl(iframe.attr("data-src").ifEmpty { iframe.attr("src") })
            if (!innerSrc.isNullOrBlank() && innerSrc != url) {
                runCatching {
                    loadExtractor(innerSrc, url, subtitleCallback, callback)
                }
            }
        }

        for (el in doc.select("video source[src], video[src], source[src]")) {
            val src = normalizeUrl(el.attr("src"))
            if (!src.isNullOrBlank()) {
                val isM3u8 = src.contains(".m3u8", ignoreCase = true)
                val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                if (isM3u8) {
                    val tracks = runCatching { M3u8Helper.generateM3u8(serverLabel, src, url) }.getOrNull()
                    if (!tracks.isNullOrEmpty()) {
                        tracks.forEach(callback)
                        continue
                    }
                }
                callback.invoke(
                    newExtractorLink(serverLabel, serverLabel, src, type) {
                        this.referer = url
                        this.quality = detectQuality(serverLabel)
                    }
                )
            }
        }

        val scriptContents = mutableListOf<String>()
        doc.select("script").forEach { s ->
            val d = s.data().ifBlank { s.html() }
            if (d.isNotBlank()) {
                scriptContents.add(d)
                if (d.contains("eval(function(p,a,c,k,e,d)")) {
                    runCatching { getAndUnpack(d) }.getOrNull()?.let { scriptContents.add(it) }
                }
            }
        }
        if (html.contains("eval(function(p,a,c,k,e,d)")) {
            runCatching { getAndUnpack(html) }.getOrNull()?.let { scriptContents.add(it) }
        }

        for (content in scriptContents) {
            val matches = Regex("""(?:file|source|videoURL|video_url|src)\s*:\s*["'](https?://[^"']+)["']""").findAll(content)
            for (m in matches) {
                val candidate = m.groupValues[1].replace("\\/", "/")
                if (candidate.isNotBlank() && (candidate.contains(".m3u8") || candidate.contains(".mp4") || candidate.contains("/stream/") || candidate.contains("/hls/"))) {
                    val isM3u8 = candidate.contains(".m3u8", ignoreCase = true)
                    val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    if (isM3u8) {
                        val tracks = runCatching { M3u8Helper.generateM3u8(serverLabel, candidate, url) }.getOrNull()
                        if (!tracks.isNullOrEmpty()) {
                            tracks.forEach(callback)
                            continue
                        }
                    }
                    callback.invoke(
                        newExtractorLink(serverLabel, serverLabel, candidate, type) {
                            this.referer = url
                            this.quality = detectQuality(serverLabel)
                        }
                    )
                }
            }

            val sourcesJsonMatch = Regex("""sources\s*:\s*(\[[^\]]+\])""").find(content)?.groupValues?.getOrNull(1)
            if (!sourcesJsonMatch.isNullOrBlank()) {
                val fileMatches = Regex("""(?:file|src)\s*:\s*["']([^"']+)["']""").findAll(sourcesJsonMatch)
                for (fm in fileMatches) {
                    val f = fm.groupValues[1].replace("\\/", "/")
                    if (f.startsWith("http")) {
                        val isM3u8 = f.contains(".m3u8", ignoreCase = true)
                        val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        if (isM3u8) {
                            val tracks = runCatching { M3u8Helper.generateM3u8(serverLabel, f, url) }.getOrNull()
                            if (!tracks.isNullOrEmpty()) {
                                tracks.forEach(callback)
                                continue
                            }
                        }
                        callback.invoke(
                            newExtractorLink(serverLabel, serverLabel, f, type) {
                                this.referer = url
                                this.quality = detectQuality(serverLabel)
                            }
                        )
                    }
                }
            }
        }
    }

    private fun detectQuality(str: String?): Int {
        if (str.isNullOrBlank()) return Qualities.Unknown.value
        val numMatch = Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (numMatch != null) {
            return when {
                numMatch >= 2160 -> Qualities.P2160.value
                numMatch >= 1080 -> Qualities.P1080.value
                numMatch >= 720 -> Qualities.P720.value
                numMatch >= 480 -> Qualities.P480.value
                numMatch >= 360 -> Qualities.P360.value
                else -> numMatch
            }
        }
        return when {
            str.contains("4k", ignoreCase = true) || str.contains("2160", ignoreCase = true) || str.contains("uhd", ignoreCase = true) -> Qualities.P2160.value
            str.contains("1080", ignoreCase = true) || str.contains("fhd", ignoreCase = true) -> Qualities.P1080.value
            str.contains("720", ignoreCase = true) || str.contains("hd", ignoreCase = true) -> Qualities.P720.value
            str.contains("480", ignoreCase = true) || str.contains("sd", ignoreCase = true) -> Qualities.P480.value
            str.contains("360", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun getBaseUrl(url: String): String {
        return runCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrDefault(mainUrl)
    }

    private fun Element.getImageAttr(): String? {
        val src = when {
            this.hasAttr("data-src") -> this.attr("abs:data-src").ifBlank { this.attr("data-src") }
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src").ifBlank { this.attr("data-lazy-src") }
            this.hasAttr("data-original") -> this.attr("abs:data-original").ifBlank { this.attr("data-original") }
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src").ifBlank { this.attr("src") }
        }
        return fixUrlNull(src)
    }

    private data class EpResponse(
        @JsonProperty("posts") val posts: String? = null,
        @JsonProperty("max_page") val max_page: Int? = null,
        @JsonProperty("found_posts") val found_posts: Int? = null,
        @JsonProperty("content") val content: String = ""
    )
}

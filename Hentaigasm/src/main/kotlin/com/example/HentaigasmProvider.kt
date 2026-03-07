package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

@Suppress("unused")
class HentaigasmProvider : MainAPI() {

    override var mainUrl = "https://hentaiplay.net"
    override var name = "HentaiPlay"
    override var lang = "en"

    override val supportedTypes = setOf(TvType.NSFW, TvType.Anime)
    override val hasMainPage = true
    override val mainPage = mainPageOf(
        "$mainUrl/hentai/episodes/" to "Latest",
        "$mainUrl/hentai/episodes/new-release/" to "New Release",
        "$mainUrl/hentai/episodes/hentai-english-subbed/" to "English Subbed",
        "$mainUrl/hentai/episodes/hentai-uncensored/" to "Uncensored",
        "$mainUrl/hentai/episodes/hentai-raw/" to "RAW",
        "$mainUrl/hentai/episodes/3d-hentai/" to "3D"
    )

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.trim().replace("\\s+".toRegex(), "+")}"
        val doc = app.get(searchUrl, headers = browserHeaders, referer = mainUrl).document
        return parseItems(doc)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data.ifBlank { "$mainUrl/hentai/episodes/" }.trimEnd('/')
        val pageUrl = if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/"
        val doc = app.get(pageUrl, headers = browserHeaders, referer = mainUrl).document
        val items = parseItems(doc)

        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = browserHeaders, referer = mainUrl).document
        val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim().orEmpty().ifBlank { doc.title().trim() }
        val description = doc.selectFirst(".entry-content p, .post-content p")?.text()?.trim().orEmpty()
        val poster = doc.selectFirst("meta[property=og:image], .entry-content img, article img")
            ?.let { element ->
                element.attr("content").ifBlank { element.attr("data-src") }.ifBlank { element.attr("src") }
            }
            ?.let { toAbsoluteUrl(it) }

        return newAnimeLoadResponse(
            name = title,
            url = url,
            type = TvType.NSFW
        ) {
            plot = description
            posterUrl = poster
            addEpisodes(
                DubStatus.Subbed,
                listOf(
                    newEpisode(url) {
                        name = title
                        episode = 1
                    }
                )
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = browserHeaders, referer = mainUrl).document
        val links = extractVideoLinks(doc)
        if (links.isEmpty()) return false

        var found = false
        for (link in links) {
            val normalized = sanitizeUrl(link) ?: continue
            if (isDirectVideo(normalized)) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = normalized
                    ) {
                        quality = Qualities.Unknown.value
                        headers = browserHeaders + mapOf("Referer" to data)
                    }
                )
                found = true
            } else {
                if (loadExtractor(normalized, data, subtitleCallback, callback)) {
                    found = true
                }
            }
        }
        return found
    }

    private fun parseItems(doc: Document): List<SearchResponse> {
        val results = linkedSetOf<SearchResponse>()

        fun addAnchorResult(anchor: Element) {
            val url = toAbsoluteUrl(anchor.attr("href")) ?: return
            if (!isLikelyPostUrl(url)) return

            val container = anchor.closest("article, .clip-link, .post, li, div")
            val title = anchor.attr("title").ifBlank { anchor.text() }.trim()
                .ifBlank { container?.selectFirst("h1, h2, h3")?.text()?.trim().orEmpty() }
            if (title.length < 3) return

            val poster = (container?.selectFirst("img[data-src], img[src]") ?: anchor.selectFirst("img[data-src], img[src]"))
                ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                ?.let { toAbsoluteUrl(it) }

            results += newMovieSearchResponse(
                name = title,
                url = url,
            ) {
                type = TvType.NSFW
                posterUrl = poster
            }
        }

        for (anchor in doc.select("a.clip-link[href], h1 a[href], h2 a[href], h3 a[href]")) {
            addAnchorResult(anchor)
        }

        if (results.isEmpty()) {
            for (anchor in doc.select("a[href]")) {
                addAnchorResult(anchor)
            }
        }

        return results.toList()
    }

    private fun extractVideoLinks(doc: Document): List<String> {
        val links = linkedSetOf<String>()

        fun addLink(value: String?) {
            val normalized = toAbsoluteUrl(value) ?: return
            if (isLikelyPostUrl(normalized)) return
            links += normalized
        }

        for (node in doc.select("video source[src], video[src], iframe[src], a[href], [data-src], [data-href]")) {
            addLink(node.attr("src"))
            addLink(node.attr("data-src"))
            addLink(node.attr("href"))
            addLink(node.attr("data-href"))
        }

        val rawHtml = doc.html().replace("\\/", "/")
        Regex("""https?://[^\s"'<>\\]+""")
            .findAll(rawHtml)
            .forEach { match ->
                addLink(match.value)
            }

        return links.toList()
    }

    private fun isDirectVideo(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".webm")
    }

    private fun isLikelyPostUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (host != "hentaiplay.net" && host != "www.hentaiplay.net") return false

        val path = (uri.path ?: "/")
            .substringBefore("#")
            .substringBefore("?")
        if (path.isBlank() || path == "/") return false

        val blockedPrefixes = listOf(
            "/hentai/episodes", "/genre/", "/tag/", "/category/", "/author/", "/page/", "/feed", "/wp-", "/search", "/comments", "/advanced-", "/hentai-list", "/upcoming"
        )
        if (blockedPrefixes.any { path.startsWith(it) }) return false
        if (path.contains("/comment-page-")) return false
        return true
    }

    private fun sanitizeUrl(url: String?): String? {
        val value = url
            ?.trim()
            .orEmpty()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace(" ", "%20")
        if (value.isBlank()) return null
        return value
    }

    private fun toAbsoluteUrl(url: String?): String? {
        val value = sanitizeUrl(url) ?: return null
        return when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> mainUrl + value
            else -> "$mainUrl/$value"
        }
    }
}

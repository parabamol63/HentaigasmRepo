package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Suppress("unused")
class HentaigasmProvider : MainAPI() {

    override var mainUrl = "https://hentaigasm.com"
    override var name = "Hentaigasm"
    override var lang = "en"

    override val supportedTypes = setOf(TvType.NSFW, TvType.Anime)
    override val hasMainPage = true
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest"
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
        val baseUrl = request.data.ifBlank { "$mainUrl/" }.trimEnd('/')
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
        val title = doc.selectFirst("h1, h1.entry-title")?.text()?.trim().orEmpty().ifBlank {
            doc.title().trim()
        }
        val description = doc.selectFirst("div.entry-content p, .entry-content p, .post-content p")
            ?.text()
            ?.trim()
            .orEmpty()
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
        for (rawLink in links) {
            val link = sanitizeUrl(rawLink) ?: continue
            if (isDirectVideo(link)) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = link
                    ) {
                        quality = Qualities.Unknown.value
                        headers = browserHeaders + mapOf("Referer" to mainUrl)
                    }
                )
                found = true
            } else {
                if (loadExtractor(link, mainUrl, subtitleCallback, callback)) {
                    found = true
                }
            }
        }
        return found
    }

    private fun parseItems(doc: Document): List<SearchResponse> {
        val results = linkedSetOf<SearchResponse>()
        val ignoredTitles = setOf("date", "title", "views", "likes", "random", "upcoming", "new")

        fun addAnchorResult(anchor: Element) {
            val url = toAbsoluteUrl(anchor.attr("href")) ?: return
            if (!isLikelyPostUrl(url)) return

            val container = anchor.closest("article, .video-list .item, .post, li, div")
            val title = anchor.attr("title").ifBlank { anchor.text() }.trim()
                .ifBlank { container?.selectFirst("h1, h2, h3")?.text()?.trim().orEmpty() }
            if (title.length < 3) return
            if (ignoredTitles.contains(title.lowercase())) return

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

        for (anchor in doc.select("article h1 a[href], article h2 a[href], article h3 a[href], h1 a[href], h2 a[href], h3 a[href]")) {
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
            if (normalized.contains(mainUrl) && isLikelyPostUrl(normalized)) return
            if (!isLikelyVideoOrExtractor(normalized)) return
            links += normalized
        }

        for (node in doc.select("video[src], video source[src], source[src], iframe[src], a[href], [data-src], [data-href]")) {
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

    private fun isLikelyVideoOrExtractor(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("mailto:")) return false
        if (isDirectVideo(lower)) return true

        val extractorHints = listOf(
            "streamwish", "filemoon", "dood", "voe", "mixdrop", "streamtape",
            "mp4upload", "ok.ru", "zlinkp", "embed", "/e/", "player",
            "hgasm", "hentaigasm", "download"
        )
        return extractorHints.any { lower.contains(it) }
    }

    private fun isLikelyPostUrl(url: String): Boolean {
        if (!url.startsWith(mainUrl) && !url.startsWith("https://www.hentaigasm.com")) return false
        val path = url
            .removePrefix(mainUrl)
            .removePrefix("https://www.hentaigasm.com")
            .substringBefore("#")
            .substringBefore("?")
        if (path.isBlank() || path == "/") return false

        val blockedPrefixes = listOf(
            "/page/", "/category/", "/tag/", "/author/", "/feed", "/wp-", "/search", "/comments"
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

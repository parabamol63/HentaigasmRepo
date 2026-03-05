package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

@Suppress("unused")
class HentaigasmProvider : MainAPI() {

    override var mainUrl = "https://hentaigasm.com"
    override var name = "Hentaigasm"
    override var lang = "en"

    override val supportedTypes = setOf(TvType.NSFW, TvType.Anime)
    override val hasMainPage = true

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val doc = app.get(searchUrl, referer = mainUrl).document
        return parseItems(doc)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        val doc = app.get(pageUrl, referer = mainUrl).document
        val items = parseItems(doc)

        return newHomePageResponse(
            listOf(HomePageList("Latest Videos", items)),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = mainUrl).document
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
        val doc = app.get(data, referer = mainUrl).document
        val links = extractVideoLinks(doc)
        if (links.isEmpty()) return false

        var found = false
        links.forEach { link ->
            if (isDirectVideo(link)) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = link
                    ) {
                        quality = Qualities.Unknown.value
                        headers = mapOf("Referer" to data)
                    }
                )
                found = true
            } else {
                if (loadExtractor(link, data, subtitleCallback, callback)) {
                    found = true
                }
            }
        }
        return found
    }

    private fun parseItems(doc: Document): List<SearchResponse> {
        val results = linkedSetOf<SearchResponse>()

        doc.select("article, .video-list .item, .post-info, .post").forEach { container ->
            val anchor = container.selectFirst("h1 a[href], h2 a[href], h3 a[href], a[href]") ?: return@forEach
            val url = toAbsoluteUrl(anchor.attr("href")) ?: return@forEach
            if (!isLikelyPostUrl(url)) return@forEach

            val title = anchor.attr("title").ifBlank { anchor.text() }.trim()
                .ifBlank { container.selectFirst("h1, h2, h3")?.text()?.trim().orEmpty() }
            if (title.length < 3) return@forEach

            val poster = container.selectFirst("img")
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

        if (results.isNotEmpty()) return results.toList()

        doc.select("a[href]").forEach { anchor ->
            val url = toAbsoluteUrl(anchor.attr("href")) ?: return@forEach
            if (!isLikelyPostUrl(url)) return@forEach

            val title = anchor.attr("title").ifBlank { anchor.text() }.trim()
            if (title.length < 3) return@forEach

            val poster = anchor.selectFirst("img")
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

        doc.select("video[src], video source[src], source[src], iframe[src], a[href]").forEach { node ->
            addLink(node.attr("src"))
            addLink(node.attr("href"))
        }

        val rawHtml = doc.html().replace("\\/", "/")
        Regex("""https?://[^\s"'<>\\]+""")
            .findAll(rawHtml)
            .forEach { match -> addLink(match.value) }

        return links.toList()
    }

    private fun isDirectVideo(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4")
    }

    private fun isLikelyVideoOrExtractor(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("mailto:")) return false
        if (isDirectVideo(lower)) return true

        val extractorHints = listOf(
            "streamwish", "filemoon", "dood", "voe", "mixdrop", "streamtape",
            "mp4upload", "ok.ru", "zlinkp", "embed", "/e/", "player"
        )
        return extractorHints.any { lower.contains(it) }
    }

    private fun isLikelyPostUrl(url: String): Boolean {
        if (!url.startsWith(mainUrl)) return false
        val path = url.removePrefix(mainUrl).substringBefore("#").substringBefore("?")
        if (path.isBlank() || path == "/") return false

        val blockedPrefixes = listOf(
            "/page/", "/category/", "/tag/", "/author/", "/feed", "/wp-", "/search"
        )
        if (blockedPrefixes.any { path.startsWith(it) }) return false
        if (path.contains("/comment-page-")) return false
        return true
    }

    private fun toAbsoluteUrl(url: String?): String? {
        val value = url?.trim().orEmpty()
        if (value.isBlank()) return null
        return when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> mainUrl + value
            else -> "$mainUrl/$value"
        }
    }
}

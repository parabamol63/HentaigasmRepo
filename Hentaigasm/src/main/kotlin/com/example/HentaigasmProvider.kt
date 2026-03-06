package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder

@Suppress("unused")
class HentaigasmProvider : MainAPI() {

    override var mainUrl = "https://hentaigasm.tv"
    override var name = "Hentaigasm"
    override var lang = "en"

    override val supportedTypes = setOf(TvType.NSFW, TvType.Anime)
    override val hasMainPage = true
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/old-videos/" to "Old Upload",
        "$mainUrl/most-views/" to "Most Views",
        "$mainUrl/most-likes/" to "Most Likes",
        "$mainUrl/alphabetical-a-z/" to "A-Z",
        "$mainUrl/series/" to "Series"
    )

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )
    private val mirrorUrls = listOf(
        "https://hentaigasm.tv",
        "https://hentaigasm.com",
        "https://www.hentaigasm.com"
    )
    private val supportedHosts = mirrorUrls.mapNotNull { runCatching { URI(it).host?.lowercase() }.getOrNull() }.toSet()

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.trim().replace("\\s+".toRegex(), "+")}"
        val doc = getDocumentWithMirrors(searchUrl)
        return parseItems(doc)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data.ifBlank { "$mainUrl/" }.trimEnd('/')
        val pageUrl = if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/"
        val doc = getDocumentWithMirrors(pageUrl)
        val items = parseItems(doc)

        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = getDocumentWithMirrors(url)
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
        val doc = getDocumentWithMirrors(data)
        val links = extractVideoLinks(doc).mapNotNull { sanitizeUrl(it) }
        if (links.isEmpty()) return false

        val expandedLinks = linkedSetOf<String>()
        for (link in links) {
            expandedLinks += link

            if (isNhPlayerWatch(link)) {
                val payload = extractNhPlayerPayload(link, data)
                expandedLinks += payload.links
                payload.subtitles.forEach { subtitleUrl ->
                    subtitleCallback.invoke(SubtitleFile("English", subtitleUrl))
                }
            }
        }

        var found = false
        for (link in expandedLinks) {
            if (isDirectVideo(link)) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = link
                    ) {
                        quality = Qualities.Unknown.value
                        headers = browserHeaders + mapOf("Referer" to data)
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

    private data class NhPlayerPayload(
        val links: List<String>,
        val subtitles: List<String>
    )

    private suspend fun extractNhPlayerPayload(url: String, referer: String): NhPlayerPayload {
        return runCatching {
            val doc = app.get(
                url,
                headers = browserHeaders + mapOf("Referer" to referer),
                referer = referer
            ).document

            val dataIds = linkedSetOf<String>()
            doc.select("[data-id]").forEach { node ->
                val value = node.attr("data-id").trim()
                if (value.isNotBlank()) dataIds += value
            }
            Regex("""data-id="([^"]+)"""")
                .findAll(doc.html())
                .forEach { match -> dataIds += match.groupValues[1] }

            val links = linkedSetOf<String>()
            val subtitles = linkedSetOf<String>()

            for (dataId in dataIds) {
                val absoluteDataId = toAbsoluteUrl(dataId, "https://nhplayer.com") ?: continue
                links += absoluteDataId

                val parsed = parseNhPlayerDataId(absoluteDataId)
                subtitles += parsed.subtitles

                // Try to resolve direct links from the nhplayer data-id page.
                val resolved = resolveNhPlayerLinks(absoluteDataId, url)
                links += resolved
            }

            NhPlayerPayload(
                links = links.toList(),
                subtitles = subtitles.toList()
            )
        }.getOrDefault(NhPlayerPayload(emptyList(), emptyList()))
    }

    private data class NhPlayerParseResult(
        val subtitles: List<String>
    )

    private fun parseNhPlayerDataId(dataIdUrl: String): NhPlayerParseResult {
        val subtitles = linkedSetOf<String>()

        val rawSub = getQueryParam(dataIdUrl, "s")
        val sub = rawSub
            ?.let { decodeBase64Text(it) }
            ?.let { sanitizeUrl(it) }
            ?.let { toAbsoluteUrl(it, "https://nhplayer.com") }
        if (sub != null) subtitles += sub

        return NhPlayerParseResult(subtitles.toList())
    }

    private suspend fun resolveNhPlayerLinks(dataIdUrl: String, nhPlayerWatchUrl: String): List<String> {
        return runCatching {
            val response = app.get(
                dataIdUrl,
                headers = browserHeaders + mapOf(
                    "Referer" to nhPlayerWatchUrl,
                    "Origin" to "https://nhplayer.com"
                ),
                referer = nhPlayerWatchUrl
            )

            val html = response.text
            extractDirectLinksFromHtml(html)
        }.getOrDefault(emptyList())
    }

    private fun extractDirectLinksFromHtml(html: String): List<String> {
        val links = linkedSetOf<String>()
        Regex("""https?://[^\s"'<>\\]+""")
            .findAll(html.replace("\\/", "/"))
            .forEach { match ->
                val url = sanitizeUrl(match.value) ?: return@forEach
                if (isDirectVideo(url)) links += url
            }
        return links.toList()
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
            if (isLikelyPostUrl(normalized)) return
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
            "hgasm", "hentaigasm", "download", "nhplayer", "1hanime"
        )
        return extractorHints.any { lower.contains(it) }
    }

    private fun isLikelyPostUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (host !in supportedHosts) return false

        val path = (uri.path ?: "/")
            .substringBefore("#")
            .substringBefore("?")
        if (path.isBlank() || path == "/") return false
        if (!path.startsWith("/watch/")) return false

        val blockedPrefixes = listOf(
            "/page/", "/category/", "/tag/", "/author/", "/feed", "/wp-", "/search", "/comments"
        )
        if (blockedPrefixes.any { path.startsWith(it) }) return false
        if (path.contains("/comment-page-")) return false
        return true
    }

    private fun isNhPlayerWatch(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("nhplayer.com/v/")
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

    private fun getMirrorCandidates(url: String): List<String> {
        val normalized = sanitizeUrl(url) ?: return emptyList()
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            return listOf(toAbsoluteUrl(normalized) ?: return emptyList())
        }

        val host = runCatching { URI(normalized).host?.lowercase() }.getOrNull().orEmpty()
        if (host !in supportedHosts) {
            return listOf(normalized)
        }

        val suffix = runCatching {
            val uri = URI(normalized)
            buildString {
                append(uri.rawPath ?: "/")
                if (!uri.rawQuery.isNullOrBlank()) {
                    append("?")
                    append(uri.rawQuery)
                }
            }
        }.getOrDefault("/")

        return mirrorUrls.map { mirror ->
            mirror.trimEnd('/') + if (suffix.startsWith("/")) suffix else "/$suffix"
        }
    }

    private suspend fun getDocumentWithMirrors(url: String): Document {
        val candidates = getMirrorCandidates(url)
        var lastError: Throwable? = null

        for (candidate in candidates) {
            try {
                val doc = app.get(candidate, headers = browserHeaders, referer = mainUrl).document
                val matchedMirror = mirrorUrls.firstOrNull { candidate.startsWith(it) }
                if (matchedMirror != null) mainUrl = matchedMirror
                return doc
            } catch (t: Throwable) {
                lastError = t
            }
        }

        throw lastError ?: IllegalStateException("Failed to fetch document")
    }

    private fun getQueryParam(url: String, key: String): String? {
        val rawQuery = runCatching { URI(url).rawQuery }.getOrNull().orEmpty()
        if (rawQuery.isBlank()) return null

        return rawQuery
            .split("&")
            .asSequence()
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator < 0) return@mapNotNull null
                val name = part.substring(0, separator)
                val value = part.substring(separator + 1)
                Pair(name, value)
            }
            .firstOrNull { (name, _) -> name == key }
            ?.second
            ?.let { encoded -> runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: encoded }
    }

    private fun decodeBase64Text(value: String): String? {
        val normalized = value
            .replace('-', '+')
            .replace('_', '/')
            .let { raw ->
                val padding = (4 - raw.length % 4) % 4
                raw + "=".repeat(padding)
            }

        return runCatching {
            String(java.util.Base64.getDecoder().decode(normalized), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun toAbsoluteUrl(url: String?, baseUrl: String = mainUrl): String? {
        val value = sanitizeUrl(url) ?: return null
        val base = baseUrl.trimEnd('/')
        return when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> base + value
            else -> "$base/$value"
        }
    }
}

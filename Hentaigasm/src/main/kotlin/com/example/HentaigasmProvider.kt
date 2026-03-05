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

    // ---------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = app.get(url).document

        return doc.select(".video-list .item, .post-info, article").mapNotNull { item ->
            val link = item.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = item.selectFirst("a")?.text()?.trim() ?: return@mapNotNull null

            newMovieSearchResponse(
                name = title,
                url = link,
            ) {
                this.type = TvType.NSFW
                this.posterUrl = null
            }
        }
    }

    // ---------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document

        val items = doc.select(".video-list .item, .post-info, article").mapNotNull { item ->
            val link = item.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = item.selectFirst("a")?.text()?.trim() ?: return@mapNotNull null

            newMovieSearchResponse(
                name = title,
                url = link,
            ) {
                type = TvType.NSFW
                posterUrl = null
            }
        }

        return newHomePageResponse(
            listOf(HomePageList("Latest Videos", items)),
            hasNext = false
        )
    }

    // ---------------------------------------------------------
    // LOAD DETAILS + EPISODE
    // ---------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.title().trim()

        val description = doc.selectFirst("div.entry-content p")?.text() ?: ""

        // Extract MP4 direct link
        val mp4 = extractMp4(doc)

        val episodes = if (mp4 != null) {
            listOf(
                newEpisode(mp4) {
                    name = title
                    episode = 1
                }
            )
        } else emptyList()

        return newAnimeLoadResponse(
            name = title,
            url = url,
            type = TvType.NSFW
        ) {
            plot = description
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ---------------------------------------------------------
    // MP4 Extraction
    // ---------------------------------------------------------
    private fun extractMp4(doc: Document): String? {
        val regex = Regex("https?://[^\"']+\\.mp4")
        return regex.find(doc.toString())?.value
    }

    // ---------------------------------------------------------
    // STREAM EXTRACTOR (Final Correct Version)
    // ---------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "Hentaigasm",
                url = data
            ) {
                quality = Qualities.Unknown.value
                headers = mapOf("Referer" to mainUrl)
            }
        )

        return true
    }
}
package com.alex.mochidownload

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Mochi's public mobile API (https://api.mochi.tv).
 *
 * These read endpoints need NO login and NO auth header — they work for a
 * guest, which is exactly what the overlay is. Verified live 2026-11-19
 * (all HTTP 200, no token):
 *
 *  POST /api/mobile/for-you  (body `{}`) -> the For You feed. Each episode
 *       already carries a SIGNED playbackUrl (a Mux .m3u8 URL with a
 *       `?token=` query param) plus id, number, seriesSlug, seriesTitle
 *       and seriesCreator.username. This is the fastest way to resolve
 *       the video Alex is watching: match the on-screen series title
 *       against the feed and take that episode's playbackUrl.
 *
 *  GET /api/mobile/search?q=<query> -> series list with id, slug, title,
 *       creator.username and totalEpisodes.
 *
 *  GET /api/mobile/lookup-episode/<seriesSlug>/<episodeNumber>
 *       -> { seriesId, episodeId, episodeNumber }. Resolves a series +
 *       episode number to the episode id.
 *
 *  GET /api/mobile/episodes/<episodeId>/play
 *       -> { id, number, seriesId, playbackUrl, playbackExpiresAt, ... }.
 *       playbackUrl is a SIGNED Mux .m3u8 URL (with `?token=`). It is the
 *       only working download source: a bare
 *       `https://stream.mux.com/<playbackId>/highest.mp4` returns 404,
 *       and even `.../highest.mp4?token=<signed JWT>` returns 403 for
 *       these signed playback ids. The signed .m3u8 from /play (or from
 *       the feed) is the one Mochi's own player uses, and it works.
 *
 *  GET /api/mobile/for-you/episode/<episodeId> -> { episode: {...} },
 *       the same episode object as the feed, also with playbackUrl.
 *
 * The playbackUrl is an HLS master playlist. See HlsDownloader for how
 * it is turned into a single .mp4 (video + audio renditions, remuxed
 * with MediaMuxer). Mux does NOT serve a static .mp4 for these videos,
 * so there is no simpler URL to use.
 *
 * No Play Integrity, no Mochi modification, no root, no MITM: this is
 * just the app's own public read API, the same endpoints the Mochi app
 * itself calls to play a video.
 */
object MochiApi {

    private const val BASE = "https://api.mochi.tv/api/mobile"
    private const val TIMEOUT_MS = 12000
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; SM-S938U) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /** One episode from the For You feed (or from /for-you/episode). */
    data class FeedEpisode(
        val id: String,
        val number: Int,
        val seriesId: String,
        val seriesSlug: String,
        val seriesTitle: String,
        val creatorUsername: String?,
        val playbackUrl: String?
    )

    /** One series from /search. */
    data class SeriesHit(
        val id: String,
        val slug: String,
        val title: String,
        val creatorUsername: String?,
        val totalEpisodes: Int
    )

    // ----- low level -----

    private fun getText(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            if (conn.responseCode !in 200..299) return null
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                .use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun postJson(url: String, body: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(body)
            }
            if (conn.responseCode !in 200..299) return null
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                .use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseFeedEpisode(obj: JSONObject): FeedEpisode? {
        val id = obj.optString("id", "")
        val seriesTitle = obj.optString("seriesTitle", "")
        if (id.isEmpty() || seriesTitle.isEmpty()) return null
        val creator = obj.optJSONObject("seriesCreator")
        return FeedEpisode(
            id = id,
            number = obj.optInt("number", 0),
            seriesId = obj.optString("seriesId", ""),
            seriesSlug = obj.optString("seriesSlug", ""),
            seriesTitle = seriesTitle,
            creatorUsername = creator?.optString("username")
                ?.takeIf { it.isNotEmpty() },
            playbackUrl = obj.optString("playbackUrl", "")
                .takeIf { it.isNotEmpty() }
        )
    }

    // ----- endpoints -----

    /** The For You feed (guest). Returns the first page of episodes,
     *  each already carrying its signed playbackUrl. Null on network /
     *  parse failure. */
    fun fetchForYouFeed(): List<FeedEpisode>? {
        val text = postJson("$BASE/for-you", "{}") ?: return null
        return try {
            val data = JSONObject(text).optJSONObject("data") ?: return null
            val arr: JSONArray = data.optJSONArray("episodes") ?: return emptyList()
            val out = ArrayList<FeedEpisode>(arr.length())
            for (i in 0 until arr.length()) {
                parseFeedEpisode(arr.optJSONObject(i) ?: continue)?.let { out.add(it) }
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    /** Search series by title. Returns the matching series, best first.
     *  Null on network / parse failure. */
    fun searchSeries(query: String): List<SeriesHit>? {
        val q = URLEncoder.encode(query, "UTF-8")
        val text = getText("$BASE/search?q=$q") ?: return null
        return try {
            val data = JSONObject(text).optJSONObject("data") ?: return null
            val arr: JSONArray = data.optJSONArray("series") ?: return emptyList()
            val out = ArrayList<SeriesHit>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val id = s.optString("id", "")
                val slug = s.optString("slug", "")
                val title = s.optString("title", "")
                if (id.isEmpty() || slug.isEmpty() || title.isEmpty()) continue
                val creator = s.optJSONObject("creator")
                out.add(
                    SeriesHit(
                        id = id,
                        slug = slug,
                        title = title,
                        creatorUsername = creator?.optString("username")
                            ?.takeIf { it.isNotEmpty() },
                        totalEpisodes = s.optInt("totalEpisodes", 0)
                    )
                )
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    /** Resolve (series slug, episode number) -> episode id.
     *  Null if the lookup fails or the episode does not exist. */
    fun lookupEpisode(seriesSlug: String, episodeNumber: Int): String? {
        val text = getText("$BASE/lookup-episode/$seriesSlug/$episodeNumber")
            ?: return null
        return try {
            val data = JSONObject(text).optJSONObject("data") ?: return null
            data.optString("episodeId", "").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /** The signed playback URL for an episode (the .m3u8 Mochi's own
     *  player uses). Null if the episode is not found or the call fails. */
    fun fetchPlaybackUrl(episodeId: String): String? {
        val text = getText("$BASE/episodes/$episodeId/play") ?: return null
        return try {
            val data = JSONObject(text).optJSONObject("data") ?: return null
            data.optString("playbackUrl", "").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /** One episode thumbnail from a series detail. */
    data class EpisodeThumb(
        val number: Int,
        val thumbnailUrl: String?
    )

    /** A series' poster + its episodes' thumbnails, from the series
     *  detail endpoint. The poster is the series' cover art
     *  (mochi.imgix.net/ugc/series-posters/...); the episode
     *  thumbnails are the per-episode stills (image.mux.com/...
     *  /thumbnail.jpg, signed with a token) — the character art. */
    data class SeriesDetail(
        val id: String,
        val slug: String,
        val title: String,
        val posterUrl: String?,
        val episodes: List<EpisodeThumb>
    )

    /** Full series detail: poster + every episode's thumbnail.
     *  GET /api/mobile/series/<seriesId> (the slug 404s; the id works).
     *  Null if the series is not found or the call fails. */
    fun fetchSeriesDetail(seriesId: String): SeriesDetail? {
        val text = getText("$BASE/series/$seriesId") ?: return null
        return try {
            val data = JSONObject(text).optJSONObject("data") ?: return null
            val id = data.optString("id", "")
            val title = data.optString("title", "")
            if (id.isEmpty() || title.isEmpty()) return null
            val eps = ArrayList<EpisodeThumb>()
            val arr: JSONArray = data.optJSONArray("episodes") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                eps.add(
                    EpisodeThumb(
                        number = e.optInt("number", 0),
                        thumbnailUrl = e.optString("thumbnailUrl", "")
                            .takeIf { it.isNotEmpty() }
                    )
                )
            }
            SeriesDetail(
                id = id,
                slug = data.optString("slug", ""),
                title = title,
                posterUrl = data.optString("thumbnailUrl", "")
                    .takeIf { it.isNotEmpty() },
                episodes = eps
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Full episode object for one id (same shape as a feed episode).
     *  Null if not found. */
    fun fetchForYouEpisode(episodeId: String): FeedEpisode? {
        val text = getText("$BASE/for-you/episode/$episodeId") ?: return null
        return try {
            val data = JSONObject(text).optJSONObject("data") ?: return null
            val ep = data.optJSONObject("episode") ?: return null
            parseFeedEpisode(ep)
        } catch (e: Exception) {
            null
        }
    }
}

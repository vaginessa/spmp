package com.toasterofbread.spmp.youtubeapi.impl.youtubemusic.endpoint

import com.toasterofbread.spmp.model.mediaitem.MediaItemData
import com.toasterofbread.spmp.model.mediaitem.playlist.RemotePlaylistData
import com.toasterofbread.spmp.platform.getDataLanguage
import com.toasterofbread.spmp.youtubeapi.endpoint.LikedPlaylistsEndpoint
import com.toasterofbread.spmp.youtubeapi.impl.youtubemusic.YoutubeMusicAuthInfo
import com.toasterofbread.spmp.youtubeapi.model.YoutubeiBrowseResponse
import com.toasterofbread.spmp.youtubeapi.model.YoutubeiShelfContentsItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response

class YTMLikedPlaylistsEndpoint(override val auth: YoutubeMusicAuthInfo): LikedPlaylistsEndpoint() {
    override suspend fun getLikedPlaylists(): Result<List<RemotePlaylistData>> {
        val result: Result<List<RemotePlaylistData>> = withContext(Dispatchers.IO) {
            val hl: String = api.context.getDataLanguage()
            val request: Request = Request.Builder()
                .endpointUrl("/youtubei/v1/browse")
                .addAuthApiHeaders()
                .postWithBody(mapOf("browseId" to "FEmusic_liked_playlists"))
                .build()

            val result: Result<Response> = api.performRequest(request)
            val data: YoutubeiBrowseResponse = result.parseJsonResponse {
                return@withContext Result.failure(it)
            }

            val playlist_data: List<YoutubeiShelfContentsItem> = try {
                data.contents!!
                    .singleColumnBrowseResultsRenderer!!
                    .tabs
                    .first()
                    .tabRenderer
                    .content!!
                    .sectionListRenderer!!
                    .contents!!
                    .first()
                    .gridRenderer!!
                    .items
            }
            catch (e: Throwable) {
                return@withContext Result.failure(e)
            }

            val playlists: List<RemotePlaylistData> = playlist_data.mapNotNull {
                // Skip 'New playlist' item
                if (it.musicTwoRowItemRenderer?.navigationEndpoint?.browseEndpoint == null) {
                    return@mapNotNull null
                }

                var item: MediaItemData? = it.toMediaItemData(hl)?.first
                if (item !is RemotePlaylistData) {
                    return@mapNotNull null
                }

                for (menu_item in it.musicTwoRowItemRenderer.menu?.menuRenderer?.items?.asReversed() ?: emptyList()) {
                    if (menu_item.menuNavigationItemRenderer?.icon?.iconType == "DELETE") {
                        item = null
                        break
                    }
                }

                return@mapNotNull item as? RemotePlaylistData
            }

            return@withContext Result.success(playlists)
        }

        result.onSuccess { playlists ->
            withContext(Dispatchers.IO) {
                with(api.context.database) {
                    transaction {
                        playlistQueries.clearOwners()
                        for (playlist in playlists.asReversed()) {
                            playlist.saveToDatabase(this@with)
                        }
                    }
                }
            }
        }

        return result
    }
}
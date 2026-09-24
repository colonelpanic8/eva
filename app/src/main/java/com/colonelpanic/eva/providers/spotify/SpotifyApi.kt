package com.colonelpanic.eva.providers.spotify

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class SpotifyTrack(
    val uri: String,
    val name: String,
    val artists: List<String>,
    val album: String?,
)

data class SpotifyDevice(
    val id: String,
    val name: String,
    val isActive: Boolean,
    val type: String,
)

/** Something Spotify can start: a track, or an artist, album, or playlist it plays as a context. */
data class SpotifyPlayable(
    val uri: String,
    val name: String,
    val kind: String,
    val artists: List<String> = emptyList(),
)

interface SpotifyPlaybackApi {
    suspend fun searchPlayable(query: String): SpotifyPlayable?

    suspend fun devices(): List<SpotifyDevice>

    suspend fun play(
        target: SpotifyPlayable,
        deviceId: String,
    )
}

interface SpotifyQueueApi {
    suspend fun searchTrack(query: String): SpotifyTrack?

    suspend fun devices(): List<SpotifyDevice>

    suspend fun queue(
        uri: String,
        deviceId: String?,
    )
}

class SpotifyApi(
    private val tokens: suspend () -> String,
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SpotifyQueueApi,
    SpotifyPlaybackApi {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun searchTrack(query: String): SpotifyTrack? {
        val url =
            apiUrl("search")
                .addQueryParameter("q", query)
                .addQueryParameter("type", "track")
                .addQueryParameter("limit", "5")
                .build()
        val body = request(Request.Builder().url(url).build())
        val item =
            body
                .objectValue("tracks")
                ?.arrayValue("items")
                ?.firstOrNull()
                ?.jsonObject ?: return null
        return SpotifyTrack(
            uri = item.text("uri") ?: return null,
            name = item.text("name") ?: return null,
            artists = item.arrayValue("artists").orEmpty().mapNotNull { it.jsonObject.text("name") },
            album = item.objectValue("album")?.text("name"),
        )
    }

    /**
     * An artist, playlist, or album whose name is exactly what was asked for plays as a whole, in
     * that order; anything else plays Spotify's top track for the words.
     */
    override suspend fun searchPlayable(query: String): SpotifyPlayable? {
        val url =
            apiUrl("search")
                .addQueryParameter("q", query)
                .addQueryParameter("type", "artist,playlist,album,track")
                .addQueryParameter("limit", "1")
                .build()
        val body = request(Request.Builder().url(url).build())

        fun first(group: String) =
            body
                .objectValue(group)
                ?.arrayValue("items")
                ?.firstOrNull { it is JsonObject }
                ?.jsonObject
        for ((group, kind) in listOf("artists" to "artist", "playlists" to "playlist", "albums" to "album")) {
            val item = first(group) ?: continue
            val name = item.text("name") ?: continue
            if (name.trim().equals(query.trim(), ignoreCase = true)) {
                return SpotifyPlayable(item.text("uri") ?: continue, name, kind)
            }
        }
        val track = first("tracks") ?: return null
        return SpotifyPlayable(
            uri = track.text("uri") ?: return null,
            name = track.text("name") ?: return null,
            kind = "track",
            artists = track.arrayValue("artists").orEmpty().mapNotNull { it.jsonObject.text("name") },
        )
    }

    override suspend fun play(
        target: SpotifyPlayable,
        deviceId: String,
    ) {
        val url = apiUrl("me/player/play").addQueryParameter("device_id", deviceId).build()
        val body =
            if (target.kind == "track") {
                JsonObject(mapOf("uris" to JsonArray(listOf(JsonPrimitive(target.uri)))))
            } else {
                JsonObject(mapOf("context_uri" to JsonPrimitive(target.uri)))
            }
        request(
            Request
                .Builder()
                .url(url)
                .put(body.toString().toRequestBody("application/json".toMediaType()))
                .build(),
            expectJson = false,
        )
    }

    override suspend fun devices(): List<SpotifyDevice> {
        val body = request(Request.Builder().url(apiUrl("me/player/devices").build()).build())
        return body.arrayValue("devices").orEmpty().mapNotNull { element ->
            val device = element.jsonObject
            SpotifyDevice(
                id = device.text("id") ?: return@mapNotNull null,
                name = device.text("name") ?: return@mapNotNull null,
                isActive = device.text("is_active")?.toBooleanStrictOrNull() ?: false,
                type = device.text("type").orEmpty(),
            )
        }
    }

    override suspend fun queue(
        uri: String,
        deviceId: String?,
    ) {
        val url =
            apiUrl("me/player/queue")
                .addQueryParameter("uri", uri)
                .apply { deviceId?.let { addQueryParameter("device_id", it) } }
                .build()
        request(
            Request
                .Builder()
                .url(url)
                .post(ByteArray(0).toRequestBody())
                .build(),
            expectJson = false,
        )
    }

    private fun apiUrl(path: String): HttpUrl.Builder = "${Spotify.API_URL}/$path".toHttpUrl().newBuilder()

    private suspend fun request(
        request: Request,
        expectJson: Boolean = true,
    ): JsonObject =
        withContext(ioDispatcher) {
            val authorized = request.newBuilder().header("Authorization", "Bearer ${tokens()}").build()
            client.newCall(authorized).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) throw spotifyApiError(response.code, body)
                if (!expectJson) return@use JsonObject(emptyMap())
                runCatching { json.parseToJsonElement(body).jsonObject }
                    .getOrElse { error("Spotify returned an unreadable response.") }
            }
        }
}

internal fun spotifyApiError(
    code: Int,
    body: String,
): IllegalStateException {
    val parsed = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
    val error = parsed?.objectValue("error")
    val reason = error?.text("reason")
    val message = error?.text("message")
    val readable =
        when {
            code == 401 -> {
                "That Spotify connection has expired. Connect Spotify again."
            }

            code == 403 && reason == "PREMIUM_REQUIRED" -> {
                "Spotify only lets Premium accounts queue songs."
            }

            code == 404 && reason == "NO_ACTIVE_DEVICE" -> {
                "Spotify is not playing on any device, so there is nothing to queue onto. Play something first."
            }

            code == 429 -> {
                "Spotify is rate limiting EVA. Try again in a moment."
            }

            else -> {
                "Spotify answered $code: ${(message ?: body).trim().take(200)}"
            }
        }
    return IllegalStateException(readable)
}

private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content

private fun JsonObject.objectValue(key: String): JsonObject? = get(key)?.let { runCatching { it.jsonObject }.getOrNull() }

private fun JsonObject.arrayValue(key: String) = get(key)?.let { runCatching { it.jsonArray }.getOrNull() }

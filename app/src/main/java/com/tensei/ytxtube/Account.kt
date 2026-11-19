package com.tensei.ytxtube

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import kotlin.random.Random

@Immutable
data class Profile(val name: String, val handle: String?, val avatar: String?)

data class PlaylistRef(val id: String, val title: String, val thumbnail: String?, val subtitle: String?)

fun highResMusicThumbnail(url: String?): String? {
    if (url.isNullOrBlank()) return null
    var u = url
    if (u.contains("googleusercontent.com")) {
        u = u.replace(Regex("=w\\d+-h\\d+[^&?]*"), "=w800-h800-l90-rj")
             .replace(Regex("=s\\d+[^&?]*"), "=s800-c")
    } else if (u.contains("ytimg.com/vi/")) {
        u = u.replace("/default.jpg", "/maxresdefault.jpg")
             .replace("/mqdefault.jpg", "/maxresdefault.jpg")
             .replace("/hqdefault.jpg", "/maxresdefault.jpg")
             .replace("/sddefault.jpg", "/maxresdefault.jpg")
    }
    return u
}

@Immutable
data class MusicShelfItem(
    val title: String,
    val subtitle: String,
    val thumbnail: String?,
    val videoId: String?,
    val browseId: String?,
    val playlistId: String? = null,
) {
    fun toVideoItem(): VideoItem? = videoId?.let { vid ->
        VideoItem(
            id = vid,
            title = title,
            channel = subtitle,
            channelUrl = null,
            thumbnail = highResMusicThumbnail(thumbnail),
            avatar = null,
            durationSec = 0L,
            views = -1L,
            uploaded = "YouTube Music",
        )
    }
}

@Immutable
data class MusicShelf(
    val title: String,
    val subtitle: String? = null,
    val items: List<MusicShelfItem>,
)

@Immutable
data class ChannelRef(val id: String, val name: String, val avatar: String?) {
    val url: String get() = "https://www.youtube.com/channel/$id"
}


@Immutable
data class FeedPage(val videos: List<VideoItem>, val next: String?, val channelHeader: ChannelHeaderMeta? = null)


@Immutable
data class LyricLine(val timeMs: Long, val text: String)


@Immutable
data class LyricsDoc(val lines: List<LyricLine>) {
    val synced: Boolean get() = lines.any { it.timeMs >= 0 }
    val plain: String get() = lines.joinToString("\n") { it.text }
}

@Immutable
data class ChannelHeaderMeta(val handle: String?, val subscribers: Long, val videoCount: Long, val verified: Boolean = false)

enum class LikeStatus { Like, Dislike, None }

@Immutable
data class VideoAccountState(val like: LikeStatus, val subscribed: Boolean?, val channelId: String?)

class NotSignedInException : IOException("Not signed in")

@Immutable
data class AccountSummary(
    val id: String,
    val name: String,
    val handle: String?,
    val avatar: String?,
    val email: String? = null,
)

object Account {
    var profile by mutableStateOf<Profile?>(null)
        private set
    val signedIn get() = profile != null

    /** Every saved session, in the order the user added them. */
    var accounts by mutableStateOf<List<AccountSummary>>(emptyList())
        private set

    var activeAccountId by mutableStateOf<String?>(null)
        private set

    private data class StoredAccount(
        val id: String,
        val name: String,
        val handle: String?,
        val avatar: String?,
        val cookies: String,
        val clientVersion: String,
        val visitor: String?,
        val email: String? = null,
    )

    private var store: SecureStore? = null
    private val entries = mutableListOf<StoredAccount>()
    private val cookies = linkedMapOf<String, String>()
    private var clientVersion = FALLBACK_CLIENT_VERSION
    private var visitorData: String? = null

    fun init(context: Context) {
        val s = SecureStore(context, "account")
        store = s
        entries.clear()
        var accountsSanitized = false
        parseAccounts(s.get(KEY_ACCOUNTS)).let { list ->
            entries.addAll(list.map { entry ->
                val nameClean = isCleanAccountName(entry.name)
                val handleClean = isCleanAccountName(entry.handle)
                if (!nameClean || !handleClean) {
                    accountsSanitized = true
                    val cleanHandle = entry.handle?.takeIf { isCleanAccountName(it) }
                    val cleanName = when {
                        nameClean -> entry.name
                        !entry.email.isNullOrBlank() -> entry.email
                        cleanHandle != null -> cleanHandle
                        else -> "Google Account"
                    }
                    entry.copy(name = cleanName, handle = cleanHandle ?: entry.email)
                } else entry
            })
        }
        if (accountsSanitized) {
            persistAccounts()
        }

        // Migrate the single-session format used before multi-account support.
        if (entries.isEmpty()) {
            val legacy = s.get(KEY_COOKIES)
            val name = s.get(KEY_NAME)
            if (legacy != null && name != null && looksSignedIn(legacy)) {
                entries += StoredAccount(
                    id = accountId(legacy),
                    name = name,
                    handle = s.get(KEY_HANDLE),
                    avatar = s.get(KEY_AVATAR),
                    cookies = legacy,
                    clientVersion = s.get(KEY_CLIENT_VERSION) ?: FALLBACK_CLIENT_VERSION,
                    visitor = s.get(KEY_VISITOR),
                )
            }
        }

        val wanted = s.get(KEY_ACTIVE) ?: entries.firstOrNull()?.id
        val active = entries.firstOrNull { it.id == wanted } ?: entries.firstOrNull()
        if (active != null) {
            load(active)
            activeAccountId = active.id
            profile = Profile(active.name, active.handle, active.avatar)
        } else {
            activeAccountId = null
            profile = null
        }
        publish()

        if (entries.isNotEmpty()) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                val emailRegex = Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""")
                entries.forEachIndexed { i, e ->
                    if (!isCleanAccountName(e.name) || e.email.isNullOrBlank()) {
                        runCatching {
                            val req = Request.Builder().url("https://www.youtube.com/?hl=en")
                                .header("User-Agent", Net.USER_AGENT)
                                .header("Cookie", e.cookies)
                                .build()
                            Net.client.newCall(req).execute().use { r ->
                                val html = r.body?.string().orEmpty()
                                val emailMatch = emailRegex

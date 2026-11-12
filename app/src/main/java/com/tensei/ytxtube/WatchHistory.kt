package com.tensei.ytxtube

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

internal data class WatchEntry(
    val video: VideoItem,
    val watchedAt: Long,
    val resumePositionMs: Long,
)

internal object WatchHistory {
    private val worker = Executors.newSingleThreadExecutor()
    private val entries = MutableStateFlow(emptyList<WatchEntry>())
    val videos = entries.asStateFlow()
    private val days = MutableStateFlow(WatchHistoryPolicy.defaultRetentionDays)
    val retention = days.asStateFlow()
    private var observing = false
    private var retentionDays = WatchHistoryPolicy.defaultRetentionDays
    private lateinit var database: SQLiteOpenHelper
    private lateinit var preferences: SharedPreferences

    fun init(context: Context) {
        preferences = context.getSharedPreferences("watch_history_settings", Context.MODE_PRIVATE)
        database = object : SQLiteOpenHelper(context, "watch_history.db", null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE history (
                        id TEXT PRIMARY KEY, title TEXT NOT NULL, channel TEXT NOT NULL,
                        thumbnail TEXT, local_uri TEXT, watched INTEGER NOT NULL, position INTEGER NOT NULL
                    )""",
                )
                db.execSQL("CREATE INDEX history_watched ON history(watched)")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        update {
            val default = WatchHistoryPolicy.defaultRetentionDays
            retentionDays = preferences.getInt("days", default)
                .takeIf { it in WatchHistoryPolicy.retentionOptions } ?: default
        }
    }

    fun observe(active: Boolean) = update {
        observing = active
        if (!active) entries.value = emptyList()
    }

    fun setRetention(value: Int) {
        require(value in WatchHistoryPolicy.retentionOptions)
        update {
            retentionDays = value
            preferences.edit().putInt("days", value).apply()
        }
    }

    fun refresh() = update {}

    fun remove(id: String) = update { delete("history", "id = ?", arrayOf(id)) }

    fun clear() = update { delete("history", null, null) }

    fun save(entry: WatchEntry, firstSaveAt: Long) = update {
        val values = ContentValues().apply {
            put("id", entry.video.id)
            put("title", entry.video.title)
            put("channel", entry.video.channel)
            put("thumbnail", entry.video.thumbnail)
            put("local_uri", entry.video.localUri)
            put("watched", entry.watchedAt)
            put("position", entry.resumePositionMs)
        }
        if (firstSaveAt == 0L) {
            insertWithOnConflict("history", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } else {
            // A later pause must not restore an entry deleted during the same viewing.
            update("history", values, "id = ? AND watched >= ?", arrayOf(entry.video.id, firstSaveAt.toString()))
        }
    }

    private fun update(action: SQLiteDatabase.() -> Unit) {
        worker.execute {
            runCatching {
                val db = database.writableDatabase
                db.beginTransaction()
                try {
                    db.prune()
                    db.action()
                    db.prune()
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                days.value = retentionDays
                if (observing) entries.value = read(db)
            }.onFailure { Log.e("WatchHistory", "Local history operation failed", it) }
        }
    }

    private fun SQLiteDatabase.prune() {
        val cutoff = WatchHistoryPolicy.cutoff(System.currentTimeMillis(), retentionDays)
        delete("history", "watched <= ?", arrayOf(cutoff.toString()))
    }

    private fun read(db: SQLiteDatabase): List<WatchEntry> = db.query(
        "history", arrayOf("id", "title", "channel", "thumbnail", "local_uri", "watched", "position"),
        null, null, null, null, "watched DESC, id ASC",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val item = VideoItem(
                    id = cursor.getString(0),
                    title = cursor.getString(1),
                    channel = cursor.getString(2),
                    thumbnail = cursor.getString(3),
                    localUri = cursor.getString(4),
                    channelUrl = null,
                    avatar = null,
                    durationSec = 0,
                    views = -1,
                    uploaded = null,
                )
                add(WatchEntry(item, cursor.getLong(5), cursor.getLong(6)))
            }
        }
    }
}

@OptIn(UnstableApi::class)
internal class WatchHistoryTracker(
    private val player: Player,
    private val lookup: (MediaItem) -> VideoItem? = { item ->
        val metadata = item.mediaMetadata
        VideoItem(
            id = item.mediaId,
            title = metadata.title?.toString().orEmpty(),
            channel = metadata.artist?.toString().orEmpty(),
            thumbnail = metadata.artworkUri?.toString(),
            localUri = metadata.extras?.getString("local_uri"),
            channelUrl = null,
            avatar = null,
            durationSec = 0,
            views = -1,
            uploaded = null,
        )
    },
) : Player.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private var id: String? = null
    private var playedMs = 0L
    private var playingSince: Long? = null
    private var firstSaveAt = 0L
    private var lastSaveAt = 0L
    private var duration = 0L
    private var isLive = false
    private val sample = object : Runnable {
        override fun run() {
            update()
            if (player.isPlaying) handler.postDelayed(this, if (firstSaveAt == 0L) 1_000 else 10_000)
        }
    }

    init {
        player.addListener(this)
        syncPlayback()
    }

    override fun onEvents(player: Player, events: Player.Events) = syncPlayback()

    private fun syncPlayback() {
        val currentId = player.currentMediaItem?.mediaId
        if (id == currentId && playingSince != null && !player.isPlaying) update(flush = true)
        if (id != currentId) {
            id = currentId
            playedMs = 0
            firstSaveAt = 0
            lastSaveAt = 0
            playingSince = null
        }
        duration = player.duration
        isLive = player.isCurrentMediaItemLive
        if (player.isPlaying && playingSince == null) {
            playingSince = SystemClock.elapsedRealtime()
            handler.removeCallbacks(sample)
            handler.postDelayed(sample, 1_000)
        } else if (!player.isPlaying) {
            playingSince = null
            handler.removeCallbacks(sample)
        }
    }

    override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
        val previous = old.mediaItem ?: return
        if (previous.mediaId != new.mediaItem?.mediaId && previous.mediaId == id && playingSince != null) {
            update(flush = true, item = previous, position = old.positionMs, live = isLive, durationMs = duration)
        }
    }

    fun release() {
        if (playingSince != null) update(flush = true)
        handler.removeCallbacks(sample)
        player.removeListener(this)
    }

    private fun update(
        flush: Boolean = false,
        item: MediaItem? = player.currentMediaItem,
        position: Long = player.currentPosition,
        live: Boolean = player.isCurrentMediaItemLive,
        durationMs: Long = player.duration,
    ) {
        val started = playingSince ?: return
        val now = SystemClock.elapsedRealtime()
        playedMs += now - started
        playingSince = now
        if (playedMs < WatchHistoryPolicy.minimumPlaybackMs || (!flush && firstSaveAt != 0L && now - lastSaveAt < 10_000)) return
        val media = item?.takeIf { it.mediaId.isNotBlank() } ?: return
        val video = lookup(media) ?: return
        val watchedAt = System.currentTimeMillis()
        val resume = WatchHistoryPolicy.resumePosition(position, durationMs, live)
        WatchHistory.save(WatchEntry(video, watchedAt, resume), firstSaveAt)
        if (firstSaveAt == 0L) firstSaveAt = watchedAt
        lastSaveAt = now
    }
}

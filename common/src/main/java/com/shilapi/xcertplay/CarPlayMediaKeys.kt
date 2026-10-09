package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.graphics.drawable.toBitmap
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.compat.AudioFocusRequestCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/**
 * CarPlay 方向盘及硬件媒体按键处理中心。
 *
 * 工作原理：
 * - Android 将媒体按键分发给当前持有音频焦点的 MediaSession。
 * - 比亚迪车机也会把方向盘媒体键交给「当前音频焦点所有者」的会话。
 * - 当 CarPlay 开始播放音乐后，DiPlay 会持续持有音频焦点并维护一个活跃的 MediaSession，
 *   直到 CarPlay 会话结束，这样即使暂停后也能继续响应播放键。
 * - 按键最终以 CarPlay 媒体 HID 事件形式发送到 iPhone（见 [CarPlayMediaButton]）。
 */
internal object CarPlayMediaKeys {
    private const val TAG = "DiPlay-MediaKeys"

    /** MediaSession 支持的操作：播放、暂停、播放/暂停切换、上一曲、下一曲 */
    private const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 专辑封面解码与发布队列。
     * 使用独立后台线程解码图片，主线程发布，避免阻塞 UI。
     */
    private val artworkQueue = NowPlayingArtworkQueue(
        worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "diplay-now-playing-artwork").apply { isDaemon = true }
        },
        main = Executor { mainHandler.post(it) },
        decode = ::decodeArtwork,
        publish = ::onArtworkDecoded,
        discard = Bitmap::recycle,
    )

    /** 当前封面队列所属的会话标识，用于防止旧会话的封面被错误发布 */
    private var artworkOwner: Any? = null

    /** 当前绑定的 CarPlay 控制器 */
    private var controller: CarPlayController? = null

    /** Android 系统 MediaSession，用于接收方向盘媒体键 */
    private var session: MediaSession? = null

    /** 音频焦点请求对象 */
    private var focusRequest: AudioFocusRequestCompat? = null

    /** 当前焦点请求的所有者标识 */
    private var focusOwner: Any? = null

    /** 焦点事件版本号，用于丢弃过期的焦点回调 */
    private var focusEventRevision = 0L

    /** 当前是否真正持有音频焦点 */
    private var focusHeld = false

    private var appContext: Context? = null

    /** CarPlay 媒体音频是否处于活跃状态 */
    private var mediaAudioActive = false

    /** 当前正在播放的歌曲元数据 */
    private var nowPlaying = CarPlayNowPlaying()

    /** 上次更新播放进度的系统时间（用于外推当前位置） */
    private var elapsedUpdatedAt = 0L

    /** 当前显示的专辑封面 */
    private var artwork: Bitmap? = null

    /** 封面缓存（最多保留 MAX_CACHED_ARTWORK 张） */
    private val artworkCache = LinkedHashMap<Int, Bitmap?>()

    /** 无封面时使用的占位图 */
    private var placeholder: Bitmap? = null

    /**
     * 绑定新的 CarPlay 控制器。
     * 如果控制器发生变化，会释放旧资源并开启新的封面会话。
     */
    @Synchronized
    fun attach(context: Context, next: CarPlayController) {
        if (controller !== next) {
            releaseLocked()
            artworkOwner = artworkQueue.newSession()
        }
        appContext = context.applicationContext
        controller = next
        // 注册播放状态、元数据、封面变化的回调
        next.playbackListener = { playing -> onIphonePlaying(next, playing) }
        next.nowPlayingListener = { update -> onNowPlayingChanged(next, update) }
        next.artworkListener = { id, bytes -> onArtworkChanged(next, id, bytes) }
    }

    /**
     * 解除与指定控制器的绑定。
     * 只有当传入的控制器与当前绑定的一致时才会真正释放资源，
     * 防止新控制器的状态被旧的 detach 调用误清。
     */
    @Synchronized
    fun detach(expected: CarPlayController?) {
        if (expected == null || controller !== expected) return
        expected.playbackListener = null
        expected.nowPlayingListener = null
        expected.artworkListener = null
        controller = null
        releaseLocked()
    }

    /**
     * CarPlay 媒体音频开始或停止时调用（可在任意线程）。
     * 会切换到主线程并更新内部状态。
     */
    fun onMediaAudioChanged(active: Boolean) {
        mainHandler.post { synchronized(this) { updateLocked(active) } }
    }

    /** iPhone 端开始或停止播放时的回调（可在任意线程） */
    private fun onIphonePlaying(expected: CarPlayController, playing: Boolean) {
        if (playing) mainHandler.post {
            synchronized(this) {
                if (controller === expected) regainFocusLocked()
            }
        }
    }

    /**
     * 将 iPhone 保留的元数据发布到 Android 系统 MediaSession。
     *
     * 重要优化：
     * iPhone 大约每秒会发送两次 NowPlayingUpdate（仅更新进度），
     * 如果每次都重新发布完整元数据（含封面），会在 system_server 中复制大量位图，
     * 在 DiLink 5.0 Tang 上几分钟内就会导致内存耗尽。
     * 因此进度信息只放在 PlaybackState 中，元数据仅在真正变化时才更新。
     */
    private fun onNowPlayingChanged(expected: CarPlayController, update: CarPlayNowPlaying) {
        mainHandler.post {
            synchronized(this) {
                if (controller !== expected) return@synchronized
                val previousArtwork = artwork
                if (nowPlaying.artworkTransferId != update.artworkTransferId) {
                    artwork = nextArtwork(update.artworkTransferId, artworkCache, artwork)
                }
                if (nowPlaying.elapsedMillis != update.elapsedMillis) {
                    elapsedUpdatedAt = SystemClock.elapsedRealtime()
                }
                val metadataChanged = metadataChanged(nowPlaying, update) || artwork !== previousArtwork
                nowPlaying = update
                if (metadataChanged) {
                    session?.setMetadata(androidMetadata(update, shownArtworkLocked()))
                }
                publishPlaybackStateLocked()
            }
        }
    }

    /** 收到新的封面数据时提交到解码队列 */
    @Synchronized
    private fun onArtworkChanged(expected: CarPlayController, id: Int, bytes: ByteArray) {
        if (controller !== expected) return
        artworkOwner?.let { artworkQueue.submit(it, id, bytes) }
    }

    /** 封面解码完成后的回调，更新缓存并在需要时刷新 MediaSession 元数据 */
    @Synchronized
    private fun onArtworkDecoded(expected: Any, id: Int, decoded: Bitmap?) {
        if (artworkOwner !== expected) {
            decoded?.recycle()
            return
        }
        artworkCache.remove(id)
        artworkCache[id] = decoded
        // 限制缓存大小，移除最旧的条目
        while (artworkCache.size > MAX_CACHED_ARTWORK) {
            artworkCache.remove(artworkCache.keys.first())
        }
        if (nowPlaying.artworkTransferId == id) {
            artwork = decoded
            session?.setMetadata(androidMetadata(nowPlaying, shownArtworkLocked()))
        }
    }

    /**
     * 尝试重新获取音频焦点。
     *
     * 场景：其他车机应用（自带 Spotify、电台等）抢占了音频焦点，导致方向盘按键被转走。
     * 当 CarPlay 再次开始播放时，重新申请焦点，让自己重新成为车机的媒体源。
     */
    private fun regainFocusLocked() {
        val request = focusRequest ?: return
        if (focusHeld) return
        val audio = appContext?.getSystemService(AudioManager::class.java) ?: return
        focusHeld = request.request(audio) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (focusHeld) forwardGrantedFocusLocked()
        Log.i(TAG, "audio focus regained=$focusHeld")
    }

    /** 根据媒体音频活跃状态更新内部逻辑（启动或恢复焦点） */
    private fun updateLocked(active: Boolean) {
        val context = appContext ?: return
        if (controller == null) return
        mediaAudioActive = active
        if (active && session == null) {
            start(context)
        } else if (active) {
            regainFocusLocked()
        }
        publishPlaybackStateLocked()
    }

    /** 创建 MediaSession 并申请音频焦点 */
    private fun start(context: Context) {
        val expectedController = controller ?: return
        val owner = Any().also { focusOwner = it }
        focusEventRevision = 0L
        val audio = context.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequestCompat(
            AudioManager.AUDIOFOCUS_GAIN,
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            { change -> onFocusChanged(expectedController, owner, change) },
            mainHandler,
        )
        val granted = audio?.let(request::request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusRequest = request
        focusHeld = granted
        if (granted) forwardGrantedFocusLocked()
        session = MediaSession(context, "DiPlay CarPlay").apply {
            setCallback(callback, mainHandler)
            setMetadata(androidMetadata(nowPlaying, shownArtworkLocked()))
            isActive = true
        }
        Log.i(TAG, "media keys active focusGranted=$granted")
    }

    /**
     * 将「已获得焦点」事件转发到后续处理。
     * 立即获得的焦点并不保证会有后续回调，因此主动 post 一次，
     * 并带上版本号防止过期事件干扰新的焦点请求。
     */
    private fun forwardGrantedFocusLocked() {
        val expectedController = controller ?: return
        val owner = focusOwner ?: return
        val revision = focusEventRevision
        mainHandler.post {
            onFocusChanged(expectedController, owner, AudioManager.AUDIOFOCUS_GAIN, revision)
        }
    }

    /**
     * 处理音频焦点变化。
     * 只有永久丢失（AUDIOFOCUS_LOSS）才会真正释放按键控制权，
     * 临时丢失会自动恢复。
     */
    private fun onFocusChanged(
        expectedController: CarPlayController,
        owner: Any,
        change: Int,
        grantedRevision: Long? = null,
    ) {
        val current = synchronized(this) {
            if (controller !== expectedController || focusOwner !== owner ||
                (grantedRevision != null && grantedRevision != focusEventRevision)
            ) {
                false
            } else {
                focusEventRevision += 1
                if (change == AudioManager.AUDIOFOCUS_LOSS) focusHeld = false
                else if (change == AudioManager.AUDIOFOCUS_GAIN) focusHeld = true
                true
            }
        }
        if (!current) return
        Log.i(TAG, "audio focus change=$change")
        // 在锁外调用 sink，避免嵌套锁
        val background = CarPlayBackgroundSession.snapshot()
        if (background?.controller === expectedController) {
            background.sink.onMediaAudioFocusChanged(change)
        }
    }

    /** 释放所有资源：MediaSession、音频焦点、封面缓存等 */
    private fun releaseLocked() {
        focusOwner = null
        artworkOwner = null
        artworkQueue.clear()
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
        mediaAudioActive = false
        nowPlaying = CarPlayNowPlaying()
        artwork = null
        artworkCache.clear()
        focusRequest?.let { request ->
            appContext?.getSystemService(AudioManager::class.java)?.let(request::abandon)
        }
        focusRequest = null
        focusHeld = false
    }

    /** 向 MediaSession 发布当前播放状态（播放/暂停 + 进度） */
    private fun publishPlaybackStateLocked() {
        val playing = if (nowPlaying.elapsedMillis != null || nowPlaying.title != null) {
            nowPlaying.playing
        } else {
            mediaAudioActive
        }
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    nowPlaying.elapsedMillis ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (playing) 1f else 0f,
                    // iPhone 只在播放、暂停或 seek 时发送进度，因此 Android 需要根据接收时间外推当前位置
                    elapsedUpdatedAt,
                )
                .build(),
        )
    }

    /**
     * 将媒体按键转发给 CarPlay 或车机视频播放器。
     * 如果当前车机视频播放器在前台，则把按键交给它，避免 iPhone 误结束视频会话。
     */
    private fun send(index: Int, source: String) {
        if (CarPlayVideo.onMediaKey(index)) {
            Log.i(TAG, "media key $source -> car video player $index")
            return
        }
        val sent = synchronized(this) { controller }?.sendMediaButton(index) ?: false
        Log.i(TAG, "media key $source -> CarPlay $index sent=$sent")
    }

    private val callback = CarPlayMediaCallback(
        experimentalDiLink3Keys = { appContext?.let(BydOutputSettings::carPlayCallControls) == true },
        send = ::send,
    )

    /**
     * 判断元数据是否发生了「可见变化」（忽略进度和播放状态）。
     * 用于决定是否需要重新 setMetadata。
     */
    internal fun metadataChanged(previous: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean =
        previous.copy(elapsedMillis = null, playing = false) != next.copy(elapsedMillis = null, playing = false)

    /** 将 CarPlayNowPlaying 转换为 Android MediaMetadata */
    internal fun androidMetadata(info: CarPlayNowPlaying, artwork: Bitmap? = null): MediaMetadata =
        MediaMetadata.Builder().apply {
            info.title?.let {
                putString(MediaMetadata.METADATA_KEY_TITLE, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, it)
            }
            info.artist?.let {
                putString(MediaMetadata.METADATA_KEY_ARTIST, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it)
            }
            info.album?.let { putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
            info.durationMillis?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) }
            info.sourceApp?.let { putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, it) }
            artwork?.let {
                putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
            }
        }.build()

    /** 当前应显示的封面（优先真实封面，否则使用占位图） */
    private fun shownArtworkLocked(): Bitmap? =
        artwork ?: placeholder ?: appContext?.let(::placeholderArt)?.also { placeholder = it }

    /** 生成无封面时的占位图 */
    internal fun placeholderArt(context: Context): Bitmap? = context
        .getDrawable(R.drawable.art_now_playing_placeholder)
        ?.toBitmap(MAX_ARTWORK_DIMENSION, MAX_ARTWORK_DIMENSION)

    /**
     * 根据 iPhone 指定的 transfer id 决定显示哪张封面。
     * 若该 id 仍在传输中，则继续显示旧封面，避免曲目切换时出现占位图闪烁。
     */
    internal fun nextArtwork(id: Int?, cache: Map<Int, Bitmap?>, current: Bitmap?): Bitmap? = when {
        id == null -> null
        cache.containsKey(id) -> cache[id]
        else -> current
    }

    /** 解码封面字节数组，自动采样并裁剪为正方形 */
    private fun decodeArtwork(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_ARTWORK_SOURCE_DIMENSION ||
            bounds.outHeight !in 1..MAX_ARTWORK_SOURCE_DIMENSION
        ) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_ARTWORK_DIMENSION * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        return squareArtwork(decoded).also { square -> if (square !== decoded) decoded.recycle() }
    }

    /**
     * 将图片适配到不超过 [MAX_ARTWORK_DIMENSION] 的透明正方形中。
     * 车机仪表盘以正方形显示封面并会拉伸，因此 16:9 视频缩略图需要居中放置避免变形。
     */
    internal fun squareArtwork(source: Bitmap): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (source.width == source.height && largest <= MAX_ARTWORK_DIMENSION) return source
        val side = minOf(largest, MAX_ARTWORK_DIMENSION)
        val scale = side.toFloat() / largest
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val left = (side - width) / 2
        val top = (side - height) / 2
        return Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { square ->
            Canvas(square).drawBitmap(
                source,
                null,
                Rect(left, top, left + width, top + height),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }
    }

    private const val MAX_ARTWORK_DIMENSION = 384
    private const val MAX_ARTWORK_SOURCE_DIMENSION = 8_192
    private const val MAX_CACHED_ARTWORK = 4
}

/**
 * MediaSession 回调 → CarPlay 媒体按键转发。
 *
 * - 硬件按键以 button event 形式到达，保留 toggle 语义。
 * - 媒体控制器（非硬件键）会调用 onPlay / onPause，带有明确意图。
 */
internal class CarPlayMediaCallback(
    private val experimentalDiLink3Keys: () -> Boolean = { false },
    private val send: (index: Int, source: String) -> Unit,
) : MediaSession.Callback() {

    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
        @Suppress("DEPRECATION")
        val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
        val index = CarPlayMediaButton.forKeyCode(event.keyCode, experimentalDiLink3Keys())
            ?: return super.onMediaButtonEvent(mediaButtonIntent)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            send(index, KeyEvent.keyCodeToString(event.keyCode))
        }
        return true
    }

    override fun onPlay() = send(CarPlayMediaButton.PLAY, "play")
    override fun onPause() = send(CarPlayMediaButton.PAUSE, "pause")
    override fun onSkipToNext() = send(CarPlayMediaButton.NEXT, "next")
    override fun onSkipToPrevious() = send(CarPlayMediaButton.PREVIOUS, "previous")
}

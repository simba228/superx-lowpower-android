package com.myAllVideoBrowser.ui.main.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.ui.main.home.MainActivity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.math.roundToInt

@UnstableApi
class FloatingVideoService : Service() {

    companion object {
        const val ACTION_START = "floating_video_start"
        const val ACTION_STOP = "floating_video_stop"
        const val EXTRA_POSITION = "video_position"
        const val EXTRA_PLAY_WHEN_READY = "video_play_when_ready"
        const val ACTION_DOWNLOAD = "floating_video_download"
        private const val CHANNEL_ID = "floating_video"
        private const val NOTIFICATION_ID = 4107
        private const val MAX_SWIPE_SEEK_MS = 120_000L
        private const val MIN_SWIPE_SEEK_MS = 5_000L
    }

    private lateinit var windowManager: WindowManager
    private var overlay: View? = null
    private var player: ExoPlayer? = null
    private var params: WindowManager.LayoutParams? = null
    private var videoUrl = ""
    private var rawHeaders = "{}"
    private var videoName = ""
    private var videoMime: String? = null
    private var isWindowExpanded = false
    private var normalWindowBounds: IntArray? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        videoUrl = intent?.getStringExtra(VideoPlayerFragment.VIDEO_URL).orEmpty()
        rawHeaders = intent?.getStringExtra(VideoPlayerFragment.VIDEO_HEADERS) ?: "{}"
        videoName = intent?.getStringExtra(VideoPlayerFragment.VIDEO_NAME).orEmpty()
        videoMime = intent?.getStringExtra(VideoPlayerFragment.VIDEO_MIME)
        if (videoUrl.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        showOverlay(
            intent?.getLongExtra(EXTRA_POSITION, 0L) ?: 0L,
            intent?.getBooleanExtra(EXTRA_PLAY_WHEN_READY, true) ?: true
        )
        return START_NOT_STICKY
    }

    private fun buildNotification(): android.app.Notification {
        val manager = getSystemService<NotificationManager>()!!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.floating_video_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(videoName.ifBlank { getString(R.string.floating_video) })
            .setContentText(getString(R.string.floating_video_active))
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun showOverlay(position: Long, playWhenReady: Boolean) {
        removeOverlay()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(
            R.layout.overlay_floating_video,
            android.widget.FrameLayout(this),
            false
        )
        val prefs = getSharedPreferences("floating_video", MODE_PRIVATE)
        val density = resources.displayMetrics.density
        val defaultWidth = (320 * density).roundToInt()
        val width = prefs.getInt("width", defaultWidth)
        // Older builds persisted an extra toolbar height. Normalize every regular window to 16:9.
        val height = width * 9 / 16
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val layoutParams = WindowManager.LayoutParams(
            width,
            height,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", (16 * density).roundToInt())
            y = prefs.getInt("y", (96 * density).roundToInt())
        }
        params = layoutParams
        overlay = view
        windowManager.addView(view, layoutParams)

        val headers = runCatching {
            Json.parseToJsonElement(rawHeaders).jsonObject.mapValues { it.value.toString().removeSurrounding("\"") }
        }.getOrDefault(emptyMap())
        val uri = Uri.parse(videoUrl)
        val dataSourceFactory: DataSource.Factory = if (uri.scheme == "http" || uri.scheme == "https") {
            DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
        } else {
            DefaultDataSource.Factory(this)
        }
        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .build()
        player = exoPlayer
        val playerView = view.findViewById<PlayerView>(R.id.floating_player_view)
        playerView.player = exoPlayer
        configureSeekGestures(playerView, exoPlayer)
        val mediaItem = MediaItem.Builder().setUri(uri).apply {
            if (!videoMime.isNullOrBlank()) setMimeType(videoMime)
            else if (videoUrl.contains("m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.seekTo(position)
        exoPlayer.playWhenReady = playWhenReady

        configureDrag(view.findViewById(R.id.floating_drag_bar), layoutParams)
        configurePinchResize(view.findViewById(R.id.floating_root), layoutParams)
        val topControls = view.findViewById<View>(R.id.floating_top_controls)
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                topControls.visibility = visibility
            }
        )
        playerView.controllerShowTimeoutMs = 3000
        playerView.showController()
        view.findViewById<TextView>(R.id.floating_drag_bar).text =
            videoName.ifBlank { getString(R.string.floating_video_drag_label) }
        view.findViewById<View>(R.id.floating_close).setOnClickListener { stopSelf() }
        view.findViewById<View>(R.id.floating_fullscreen).setOnClickListener {
            toggleFloatingFullscreen(layoutParams)
        }
        view.findViewById<View>(R.id.floating_download).setOnClickListener { downloadVideo() }
        view.findViewById<View>(R.id.floating_settings).setOnClickListener {
            playerView.showController()
            playerView.post {
                playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.performClick()
            }
        }
        val speedButton = view.findViewById<TextView>(R.id.floating_speed)
        val speeds = floatArrayOf(1f, 1.25f, 1.5f, 2f, 0.75f)
        var speedIndex = 0
        speedButton.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            exoPlayer.setPlaybackSpeed(speeds[speedIndex])
            speedButton.text = "${speeds[speedIndex]}×"
        }
    }

    private fun configureSeekGestures(playerView: PlayerView, exoPlayer: ExoPlayer) {
        val minDistance = 64 * resources.displayMetrics.density
        val detector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = false

                override fun onFling(
                    down: MotionEvent?,
                    up: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    val start = down ?: return false
                    val distanceX = up.x - start.x
                    val distanceY = up.y - start.y
                    if (kotlin.math.abs(distanceX) < minDistance ||
                        kotlin.math.abs(distanceX) <= kotlin.math.abs(distanceY)
                    ) return false

                    val width = playerView.width.coerceAtLeast(1).toFloat()
                    val proportionalOffset =
                        (distanceX / width * MAX_SWIPE_SEEK_MS).toLong()
                    val offset = if (proportionalOffset >= 0) {
                        proportionalOffset.coerceIn(MIN_SWIPE_SEEK_MS, MAX_SWIPE_SEEK_MS)
                    } else {
                        proportionalOffset.coerceIn(-MAX_SWIPE_SEEK_MS, -MIN_SWIPE_SEEK_MS)
                    }
                    val duration = exoPlayer.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                    exoPlayer.seekTo((exoPlayer.currentPosition + offset).coerceIn(0L, duration))
                    val seconds = kotlin.math.abs(offset) / 1000
                    Toast.makeText(
                        this@FloatingVideoService,
                        if (offset > 0) "+${seconds}s" else "-${seconds}s",
                        Toast.LENGTH_SHORT
                    ).show()
                    return true
                }
            }
        )
        playerView.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
    }

    private fun configureDrag(handle: View, layoutParams: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layoutParams.x
                    startY = layoutParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = startX + (event.rawX - touchX).roundToInt()
                    layoutParams.y = startY + (event.rawY - touchY).roundToInt()
                    overlay?.let { windowManager.updateViewLayout(it, layoutParams) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    saveBounds(layoutParams)
                    handle.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun configurePinchResize(
        root: ResizableOverlayLayout,
        layoutParams: WindowManager.LayoutParams
    ) {
        root.onScale = { scaleFactor ->
            resizeWindow(layoutParams, (layoutParams.width * scaleFactor).roundToInt())
        }
        root.onScaleFinished = { saveBounds(layoutParams) }
    }

    private fun resizeWindow(layoutParams: WindowManager.LayoutParams, requestedWidth: Int) {
        val metrics = resources.displayMetrics
        val minWidth = (220 * metrics.density).roundToInt()
        val maxByScreenWidth = (metrics.widthPixels * 0.98f).roundToInt()
        val maxByScreenHeight = (metrics.heightPixels * 0.80f * 16f / 9f).roundToInt()
        val width = requestedWidth.coerceIn(minWidth, minOf(maxByScreenWidth, maxByScreenHeight))
        layoutParams.width = width
        layoutParams.height = width * 9 / 16
        overlay?.let { windowManager.updateViewLayout(it, layoutParams) }
    }

    private fun toggleFloatingFullscreen(layoutParams: WindowManager.LayoutParams) {
        if (!isWindowExpanded) {
            normalWindowBounds = intArrayOf(
                layoutParams.width, layoutParams.height, layoutParams.x, layoutParams.y
            )
            val metrics = resources.displayMetrics
            layoutParams.width = metrics.widthPixels
            layoutParams.height = metrics.heightPixels
            layoutParams.x = 0
            layoutParams.y = 0
            isWindowExpanded = true
        } else {
            normalWindowBounds?.let { bounds ->
                layoutParams.width = bounds[0]
                layoutParams.height = bounds[1]
                layoutParams.x = bounds[2]
                layoutParams.y = bounds[3]
            }
            isWindowExpanded = false
            saveBounds(layoutParams)
        }
        overlay?.let { windowManager.updateViewLayout(it, layoutParams) }
    }

    private fun saveBounds(layoutParams: WindowManager.LayoutParams) {
        getSharedPreferences("floating_video", MODE_PRIVATE).edit()
            .putInt("width", layoutParams.width)
            .putInt("height", layoutParams.height)
            .putInt("x", layoutParams.x)
            .putInt("y", layoutParams.y)
            .apply()
    }

    private fun downloadVideo() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = ACTION_DOWNLOAD
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(VideoPlayerFragment.VIDEO_URL, videoUrl)
            putExtra(VideoPlayerFragment.VIDEO_HEADERS, rawHeaders)
            putExtra(VideoPlayerFragment.VIDEO_NAME, videoName)
            putExtra(VideoPlayerFragment.VIDEO_MIME, videoMime)
        })
    }

    private fun removeOverlay() {
        player?.release()
        player = null
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
    }

    override fun onDestroy() {
        params?.let { layoutParams ->
            val bounds = normalWindowBounds
            if (isWindowExpanded && bounds != null) {
                val normalParams = WindowManager.LayoutParams().apply {
                    width = bounds[0]
                    height = bounds[1]
                    x = bounds[2]
                    y = bounds[3]
                }
                saveBounds(normalParams)
            } else {
                saveBounds(layoutParams)
            }
        }
        removeOverlay()
        super.onDestroy()
    }
}

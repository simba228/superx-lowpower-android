package com.myAllVideoBrowser.ui.main.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView.SHOW_BUFFERING_ALWAYS
import com.myAllVideoBrowser.databinding.FragmentPlayerBinding
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.util.AppUtil
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import androidx.core.net.toUri


@UnstableApi
class VideoPlayerFragment : BaseFragment() {

    companion object {
        const val VIDEO_URL = "video_url"
        const val VIDEO_HEADERS = "video_headers"
        const val VIDEO_NAME = "video_name"
        const val VIDEO_MIME = "video_mime"
    }

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var appUtil: AppUtil

    @Inject
    lateinit var okHttpClient: OkHttpProxyClient

    private lateinit var player: ExoPlayer

    private lateinit var videoPlayerViewModel: VideoPlayerViewModel

    private lateinit var dataBinding: FragmentPlayerBinding
    private var isStretched = false
    private var pendingFloatingLaunch = false

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (pendingFloatingLaunch && Settings.canDrawOverlays(requireContext())) {
            launchFloatingPlayer()
        }
        pendingFloatingLaunch = false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        videoPlayerViewModel =
            ViewModelProvider(this, viewModelFactory)[VideoPlayerViewModel::class.java]
        dataBinding = FragmentPlayerBinding.inflate(inflater, container, false)

        arguments?.getString(VIDEO_HEADERS)?.let { rawHeaders ->
            try {
                val headers =
                    Json.parseToJsonElement(rawHeaders).jsonObject.mapValues { (_, value) ->
                        value.toString().removeSurrounding("\"")
                    }
                videoPlayerViewModel.videoHeaders.set(headers)
            } catch (_: Throwable) {
                videoPlayerViewModel.videoHeaders.set(emptyMap())
            }
        }
        arguments?.getString(VIDEO_NAME)?.let { videoPlayerViewModel.videoName.set(it) }

        val videoUrlStr = arguments?.getString(VIDEO_URL)
        if (videoUrlStr.isNullOrEmpty()) {
            Toast.makeText(context, "Invalid Video URL", Toast.LENGTH_SHORT).show()
            handleClose()
            return dataBinding.root
        }

        val iUrl = try {
            val parsedUri = videoUrlStr.toUri()
            if (parsedUri.scheme == null) {
                throw Exception("Invalid URI scheme")
            }
            parsedUri
        } catch (_: Throwable) {
            Toast.makeText(context, "Malformed Video URL", Toast.LENGTH_SHORT).show()
            handleClose()
            return dataBinding.root
        }
        videoPlayerViewModel.videoUrl.set(iUrl)

        val url = videoPlayerViewModel.videoUrl.get() ?: Uri.EMPTY
        // The "Cookie" header will be passed here, but OkHttp using CookieJar
        val headers = videoPlayerViewModel.videoHeaders.get() ?: emptyMap()

        val mediaFactory = createMediaFactory(headers, url.toString().startsWith("http"))

        player = ExoPlayer.Builder(requireContext())
            .setRenderersFactory(createRenderFactory())
            .setMediaSourceFactory(mediaFactory)
            .build()

        dataBinding.apply {
            val currentBinding = this

            currentBinding.viewModel = videoPlayerViewModel
            currentBinding.toolbar.setNavigationOnClickListener(navigationIconClickListener)
            currentBinding.toolbar.inflateMenu(com.myAllVideoBrowser.R.menu.player_menu)
            currentBinding.toolbar.setOnMenuItemClickListener { item ->
                if (item.itemId == com.myAllVideoBrowser.R.id.action_floating_player) {
                    requestFloatingPlayer()
                    true
                } else false
            }
            currentBinding.videoView.player = player
            currentBinding.videoView.setShowBuffering(SHOW_BUFFERING_ALWAYS)
            currentBinding.videoView.setFullscreenButtonClickListener {
                toggleStretchMode()
            }

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && player.playWhenReady) {
                        currentBinding.loadingBar.visibility = View.GONE
                    } else if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                        currentBinding.loadingBar.visibility = View.GONE
                    } else {
                        currentBinding.loadingBar.visibility = View.VISIBLE
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (videoPlayerViewModel.videoUrl.get().toString().startsWith("http")) {
                        AlertDialog.Builder(requireContext())
                            .setTitle("Download Only")
                            .setMessage("This video supports only download.")
                            .setPositiveButton("OK") { dialog, _ ->
                                dialog.dismiss()
                                handleClose()
                            }
                            .show()
                    }
                    Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
                }
            })

            if (url != Uri.EMPTY) {
                try {
                    val mediaItem = MediaItem.Builder()
                        .setUri(url)
                        .apply {
                            val explicitMime = arguments?.getString(VIDEO_MIME)
                            if (!explicitMime.isNullOrBlank()) setMimeType(explicitMime)
                            else if (url.toString().contains("m3u8", ignoreCase = true)) {
                                setMimeType(MimeTypes.APPLICATION_M3U8)
                            }
                        }
                        .build()
                    player.setMediaItem(mediaItem)
                    player.prepare()
                    val startPosition = arguments?.getLong(FloatingVideoService.EXTRA_POSITION, 0L) ?: 0L
                    if (startPosition > 0L) player.seekTo(startPosition)
                    player.playWhenReady = arguments?.getBoolean(
                        FloatingVideoService.EXTRA_PLAY_WHEN_READY, true
                    ) ?: true
                } catch (e: Throwable) {
                    Toast.makeText(context, "Error loading video: ${e.message}", Toast.LENGTH_LONG).show()
                    handleClose()
                }
            }
        }

        return dataBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        handleBackPressed()
        handlePlayerEvents()
        videoPlayerViewModel.start()
        getActivity(context)?.let { appUtil.hideSystemUI(it.window, dataBinding.root) }
    }

    private fun getActivity(context: Context?): Activity? {
        if (context == null) {
            return null
        } else if (context is ContextWrapper) {
            return if (context is Activity) {
                context
            } else {
                getActivity(context.baseContext)
            }
        }
        return null
    }

    override fun onDestroyView() {
        getActivity(context)?.let { appUtil.showSystemUI(it.window, dataBinding.root) }
        videoPlayerViewModel.stop()
        player.release()
        super.onDestroyView()
    }

    private val navigationIconClickListener = View.OnClickListener {
        handleClose()
    }

    private fun handlePlayerEvents() {
        videoPlayerViewModel.stopPlayerEvent.observe(viewLifecycleOwner) {
            player.stop()
        }
    }

    private fun createRenderFactory(): RenderersFactory {
        return DefaultRenderersFactory(requireContext().applicationContext)
            // Prefer platform MediaCodec hardware decoders. Extensions remain available only
            // as a fallback instead of being preferred over the efficient hardware path.
            .setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
    }

    private fun createMediaFactory(
        headers: Map<String, String>,
        isHttp: Boolean
    ): DefaultMediaSourceFactory {
        val dataSourceFactory: DataSource.Factory = if (isHttp) {
            OkHttpDataSource.Factory(okHttpClient.getProxyOkHttpClient())
                .setDefaultRequestProperties(headers)
        } else {
            DefaultDataSource.Factory(requireContext())
        }

        return DefaultMediaSourceFactory(requireContext()).setDataSourceFactory(dataSourceFactory)
    }

    private fun handleBackPressed() {
        this.view?.isFocusableInTouchMode = true
        this.view?.requestFocus()
        this.view?.setOnKeyListener { _, keyCode, _ ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                handleClose()
                true
            } else false
        }
    }

    private fun handleClose() {
        videoPlayerViewModel.stop()
        activity?.finish()
    }

    private fun requestFloatingPlayer() {
        if (Settings.canDrawOverlays(requireContext())) {
            launchFloatingPlayer()
            return
        }
        pendingFloatingLaunch = true
        Toast.makeText(context, com.myAllVideoBrowser.R.string.floating_video_permission, Toast.LENGTH_LONG).show()
        overlayPermissionLauncher.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${requireContext().packageName}")
            )
        )
    }

    private fun launchFloatingPlayer() {
        val serviceIntent = Intent(requireContext(), FloatingVideoService::class.java).apply {
            action = FloatingVideoService.ACTION_START
            putExtra(VIDEO_URL, videoPlayerViewModel.videoUrl.get()?.toString())
            putExtra(VIDEO_HEADERS, arguments?.getString(VIDEO_HEADERS) ?: "{}")
            putExtra(VIDEO_NAME, videoPlayerViewModel.videoName.get())
            putExtra(VIDEO_MIME, arguments?.getString(VIDEO_MIME))
            putExtra(FloatingVideoService.EXTRA_POSITION, player.currentPosition)
            putExtra(FloatingVideoService.EXTRA_PLAY_WHEN_READY, player.playWhenReady)
        }
        player.playWhenReady = false
        ContextCompat.startForegroundService(requireContext(), serviceIntent)
        activity?.finish()
    }

    private fun toggleStretchMode() {
        isStretched = !isStretched

        if (isStretched) {
            dataBinding.videoView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            dataBinding.toolbar.visibility = View.GONE
        } else {
            dataBinding.videoView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            dataBinding.toolbar.visibility = View.VISIBLE
        }
    }
}

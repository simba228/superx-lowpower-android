package com.myAllVideoBrowser.ui.main.home.browser

import android.graphics.Bitmap
import android.os.Build
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.data.local.room.entity.HistoryItem
import com.myAllVideoBrowser.ui.main.history.HistoryViewModel
import com.myAllVideoBrowser.ui.main.settings.SettingsViewModel
import com.myAllVideoBrowser.util.FaviconUtils
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.ui.main.home.browser.detectedVideos.IVideoDetector
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTab
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTabViewModel
import com.myAllVideoBrowser.util.CookieUtils
import com.myAllVideoBrowser.util.SingleLiveEvent
import com.myAllVideoBrowser.util.VideoUtils
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.ByteArrayInputStream
import androidx.core.net.toUri
import com.myAllVideoBrowser.ui.main.home.browser.adblocker.AdBlockEngine
import com.myAllVideoBrowser.util.AppLogger

val injectJsInterceptor = """
        (function() {
            const oldSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.send = function(body) {
                if (window.AndroidBridge && window.AndroidBridge.shouldInterceptPost(this._url, body || "")) {
                    this.abort(); // Kill the XHR
                    return;
                }
                oldSend.apply(this, arguments);
            };

            const oldFetch = window.fetch;
            window.fetch = async function(input, init) {
                const url = typeof input === 'string' ? input : input.url;
                const method = init?.method?.toUpperCase() || 'GET';
                if (method === 'POST' && window.AndroidBridge) {
                    if (window.AndroidBridge.shouldInterceptPost(url, init.body || "")) {
                        return new Response('', { status: 200 }); // Return empty
                    }
                }
                return oldFetch.apply(this, arguments);
            };
        })();
    """.trimIndent()

val injectVideoProgressTap = """
    (function() {
        if (window.__superxTapSeekInstalled) return;
        window.__superxTapSeekInstalled = true;

        function reportPlayingVideo(video) {
            const source = video && (video.currentSrc || video.src);
            if (source && window.AndroidBridge && window.AndroidBridge.videoStarted) {
                window.AndroidBridge.videoStarted(source);
            }
        }

        document.addEventListener('play', function(event) {
            if (event.target && event.target.tagName === 'VIDEO') {
                reportPlayingVideo(event.target);
            }
        }, true);

        function playableVideo(element) {
            const container = element && element.closest
                ? element.closest('[class*="player"], [id*="player"], video')
                : null;
            const nearby = container && container.tagName === 'VIDEO'
                ? container
                : container && container.querySelector ? container.querySelector('video') : null;
            if (nearby) return nearby;
            const videos = Array.from(document.querySelectorAll('video'));
            return videos.find(v => !v.paused && isFinite(v.duration) && v.duration > 0)
                || videos.find(v => isFinite(v.duration) && v.duration > 0)
                || null;
        }

        document.addEventListener('pointerup', function(event) {
            if (event.button !== undefined && event.button !== 0) return;
            const path = event.composedPath ? event.composedPath() : [event.target];
            const target = path.find(node => node && node.nodeType === 1);
            if (!target || !target.getBoundingClientRect) return;

            let seekBar = path.find(node => {
                if (!node || node.nodeType !== 1) return false;
                const text = ((node.id || '') + ' ' + (node.className || '') + ' ' +
                    (node.getAttribute && node.getAttribute('role') || '')).toLowerCase();
                return (node.tagName === 'INPUT' && node.type === 'range') ||
                    /progress|seek|timeline/.test(text);
            });

            let video = playableVideo(seekBar || target);
            let bar = seekBar;
            if (!bar && target.tagName === 'VIDEO') {
                const videoRect = target.getBoundingClientRect();
                const controlHeight = Math.max(48, videoRect.height * 0.18);
                if (event.clientY < videoRect.bottom - controlHeight) return;
                video = target;
                bar = target;
            }
            if (!bar || !video || !isFinite(video.duration) || video.duration <= 0) return;

            const rect = bar.getBoundingClientRect();
            if (!rect.width) return;
            const ratio = Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width));
            video.currentTime = video.duration * ratio;
        }, true);

        document.querySelectorAll('video').forEach(function(video) {
            if (!video.paused) reportPlayingVideo(video);
        });
    })();
""".trimIndent()

enum class ContentType {
    M3U8,
    MPD,
    VIDEO,
    AUDIO,
    OTHER
}

class CustomWebViewClient(
    private val tabViewModel: WebTabViewModel,
    private val settingsModel: SettingsViewModel,
    private val videoDetectionModel: IVideoDetector,
    private val historyModel: HistoryViewModel,
    private val okHttpProxyClient: OkHttpProxyClient,
    private val updateTabEvent: SingleLiveEvent<WebTab>,
    private val pageTabProvider: PageTabProvider,
    private val proxyController: CustomProxyController,
    private val adBlockEngine: AdBlockEngine
) : WebViewClient() {
    var videoAlert: MaterialAlertDialogBuilder? = null
    private var lastSavedHistoryUrl: String = ""
    private var lastSavedTitleHistory: String = ""
    private var lastRegularCheckUrl = ""
    private val regularJobsStorage: MutableMap<String, List<Disposable>> = mutableMapOf()
    private var approvedUrl: String? = null

    companion object {
        fun emptyResponse(): WebResourceResponse {
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                ByteArrayInputStream("".toByteArray())
            )
        }
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        val viewTitle = view?.title
        val title = tabViewModel.currentTitle.get()
        val userAgent = view?.settings?.userAgentString ?: tabViewModel.userAgent.get()

        if (url != null && lastSavedHistoryUrl != url) {
            historyModel.viewModelScope.launch(historyModel.executorSingleHistory) {
                val faviconUrl = FaviconUtils.getFaviconUrl(url)
                saveUrlToHistory(url, faviconUrl, viewTitle ?: title)

                videoDetectionModel.onStartPage(
                    url,
                    userAgent
                        ?: BrowserFragment.MOBILE_USER_AGENT
                )
                tabViewModel.onUpdateVisitedHistory(
                    url,
                    title,
                    userAgent
                )
            }
        }
        super.doUpdateVisitedHistory(view, url, isReload)
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?
    ) {
        if (proxyController.getCurrentRunningProxy().host == host) {
            val creds = proxyController.getProxyCredentials()

            if (creds.first.isNotEmpty() || creds.second.isNotEmpty()) {
                handler?.proceed(creds.first, creds.second)
            }
        }
        super.onReceivedHttpAuthRequest(view, handler, host, realm)
    }

    override fun shouldInterceptRequest(
        view: WebView?, request: WebResourceRequest?
    ): WebResourceResponse? {
        if (request == null || request.url == null) {
            return super.shouldInterceptRequest(view, request)
        }

        val url = request.url.toString()

        val resourceType = when {
            request.isForMainFrame -> "main_frame"
            request.url.toString().contains(".js") -> "script"
            request.url.toString().contains(".css") -> "stylesheet"
            request.requestHeaders["X-Requested-With"] != null -> "xmlhttprequest"
            request.requestHeaders["Accept"]?.contains("image") == true -> "image"
            else -> "other"
        }

        if (settingsModel.isAdBlockOn.get()) {
            val isAd = adBlockEngine.isAd(
                url,
                tabViewModel.getTabTextInput().get() ?: "",
                resourceType
            )

            if (isAd) {
                AppLogger.d("AdBlock (GET/Resource): Blocked $url")
                return emptyResponse()
            }
        }


        val isCheckM3u8 = settingsModel.isCheckIfEveryRequestOnM3u8.get()
        val isCheckOnMp4 = settingsModel.getIsCheckEveryRequestOnMp4Video().get()
        val isCheckOnAudio = settingsModel.isCheckOnAudio.get()

        if (isCheckOnMp4 || isCheckM3u8 || isCheckOnAudio) {
            val requestWithCookies = request.let { resourceRequest ->
                try {
                    CookieUtils.webResourceRequestToOkHttpRequest(resourceRequest)
                } catch (_: Throwable) {
                    null
                }
            }

            val contentType =
                VideoUtils.getContentTypeByUrl(url, requestWithCookies?.headers, okHttpProxyClient)
            val isInterruptIntreceptedResources =
                settingsModel.isInterruptIntreceptedResources.get()
            when {

                contentType == ContentType.M3U8 || contentType == ContentType.MPD || url.contains(".m3u8") || url.contains(
                    ".mpd"
                ) || (url.contains(".txt") && url.contains("hentaihaven")) -> {
                    val isM3u8 =
                        ContentType.M3U8 == contentType || (url.contains(".txt")) || url.contains(".m3u8")
                    val isMpd = ContentType.MPD == contentType || url.contains(".mpd")

                    if (requestWithCookies != null && isCheckM3u8) {
                        videoDetectionModel.verifyLinkStatus(
                            requestWithCookies, tabViewModel.currentTitle.get(), isM3u8, isMpd
                        )
                    }
                    if (isInterruptIntreceptedResources) {
                        return emptyResponse()
                    }
                }

                else -> {
                    if ((isCheckOnMp4 || isCheckOnAudio) && contentType != ContentType.OTHER) {
                        val disposable = videoDetectionModel.checkRegularVideoOrAudio(
                            requestWithCookies,
                            isCheckOnAudio,
                            isCheckOnMp4
                        )

                        val currentUrl = tabViewModel.getTabTextInput().get() ?: ""
                        if (currentUrl != lastRegularCheckUrl) {
                            regularJobsStorage[lastRegularCheckUrl]?.forEach {
                                it.dispose()
                            }
                            regularJobsStorage.remove(lastRegularCheckUrl)
                            lastRegularCheckUrl = currentUrl
                        }
                        if (disposable != null) {
                            val overall = mutableListOf<Disposable>()
                            overall.addAll(regularJobsStorage[currentUrl]?.toList() ?: emptyList())
                            overall.add(disposable)
                            regularJobsStorage[currentUrl] = overall
                        }
                        if (isInterruptIntreceptedResources) {
                            return emptyResponse()
                        }
                    }
                }
            }
        }

        return super.shouldInterceptRequest(
            view, request
        )
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)

        view.evaluateJavascript(injectJsInterceptor, null)

        videoAlert = null
        val pageTab = pageTabProvider.getPageTab(tabViewModel.thisTabIndex.get())
        val headers = pageTab.getHeaders() ?: emptyMap()
        val favi = pageTab.getFavicon() ?: view.favicon ?: favicon

        updateTabEvent.value = WebTab(
            url,
            view.title,
            favi,
            headers,
            view,
            id = pageTab.id
        )
        tabViewModel.onStartPage(url, view.title)
    }

    override fun shouldOverrideUrlLoading(view: WebView, url: WebResourceRequest): Boolean {
        val newUrl = url.url.toString()
        if (newUrl.startsWith("http") && url.isForMainFrame) {
            if (approvedUrl == newUrl) {
                approvedUrl = null
                return false
            }

            val currentUrl = view.url
            val currentHost = currentUrl?.toUri()?.host
            val newHost = url.url.host

            if (settingsModel.isAskRedirection.get() && currentHost != null && newHost != null && currentHost != newHost) {
                showRedirectionDialog(view, newUrl, newHost)
                return true
            }

            if (!tabViewModel.isTabInputFocused.get()) {
                tabViewModel.setTabTextInput(newUrl)
            }
            return false
        } else {
            return true
        }
    }

    private fun showRedirectionDialog(view: WebView, newUrl: String, newHost: String) {
        val context = view.context
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.redirection_dialog_title))
            .setMessage("${context.getString(R.string.redirection_dialog_message)} $newHost")
            .setPositiveButton(context.getString(R.string.yes)) { _, _ ->
                approvedUrl = newUrl
                view.loadUrl(newUrl)
            }
            .setNegativeButton(context.getString(R.string.no), null)
            .setNeutralButton(context.getString(R.string.dontshow)) { _, _ ->
                settingsModel.setIsAskRedirection(false)
                approvedUrl = newUrl
                view.loadUrl(newUrl)
            }
            .show()
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        view.evaluateJavascript(injectVideoProgressTap, null)
        tabViewModel.finishPage(url)
    }

    override fun onRenderProcessGone(
        view: WebView?, detail: RenderProcessGoneDetail?
    ): Boolean {
        val pageTab = pageTabProvider.getPageTab(tabViewModel.thisTabIndex.get())

        val webView = pageTab.getWebView()
        if (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                view == webView && detail?.didCrash() == true
            } else {
                view == webView
            }
        ) {
            webView?.destroy()
            return true
        }

        return super.onRenderProcessGone(view, detail)
    }

    private suspend fun saveUrlToHistory(url: String, favicon: Any?, title: String?) {
        val isTitleEmpty = title?.trim()?.isEmpty() == true

        if (!isTitleEmpty && lastSavedTitleHistory != title && lastSavedHistoryUrl != url && url.isNotEmpty() && !url.contains(
                "about:blank"
            )
        ) {
            lastSavedHistoryUrl = url
            lastSavedTitleHistory = title ?: ""

            val faviconUrl = when (favicon) {
                is String -> favicon
                else -> FaviconUtils.getFaviconUrl(url)
            }

            yield()

            historyModel.saveHistory(
                HistoryItem(
                    url = url, faviconUrl = faviconUrl, title = title
                )
            )
        }
    }
}

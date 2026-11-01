package com.myAllVideoBrowser.ui.main.home.browser.detectedVideos

import com.myAllVideoBrowser.data.local.room.entity.VideoInfo

enum class MediaDetectionSource {
    NETWORK,
    PAGE_PLAYBACK,
    SERVICE_WORKER,
    VERIFIED
}

/**
 * One per-tab source of truth shared by detection, floating playback and download metadata.
 */
data class MediaCandidate(
    val tabId: String,
    val pageUrl: String,
    val pageTitle: String,
    val source: MediaDetectionSource,
    val rawResource: DetectedVideoResource? = null,
    val videoInfo: VideoInfo? = null,
    val detectedAtMillis: Long = System.currentTimeMillis()
) {
    val mediaUrl: String
        get() = videoInfo?.formats?.allFormats()
            ?.firstOrNull { it.url?.startsWith("http") == true }
            ?.url
            ?: videoInfo?.downloadUrls?.firstOrNull()?.url?.toString()
            ?: rawResource?.request?.url?.toString()
            ?: ""

    fun withVerifiedInfo(info: VideoInfo, title: String): MediaCandidate = copy(
        pageTitle = title.ifBlank { pageTitle },
        source = MediaDetectionSource.VERIFIED,
        videoInfo = info,
        detectedAtMillis = System.currentTimeMillis()
    )

    fun diagnosticSummary(): String = buildString {
        append("tab=").append(tabId)
        append(", source=").append(source)
        append(", page=").append(pageUrl.substringBefore('?'))
        append(", media=").append(mediaUrl.substringBefore('?'))
        append(", raw=").append(rawResource != null)
        append(", verified=").append(videoInfo != null)
        append(", formats=").append(videoInfo?.formats?.allFormats()?.size ?: 0)
        append(", downloadUrls=").append(videoInfo?.downloadUrls?.size ?: 0)
    }
}

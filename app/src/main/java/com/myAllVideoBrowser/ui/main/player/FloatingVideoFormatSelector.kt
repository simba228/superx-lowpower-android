package com.myAllVideoBrowser.ui.main.player

import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo

object FloatingVideoFormatSelector {
    fun select(videoInfo: VideoInfo): VideoFormatEntity? = videoInfo.formats.allFormats()
        .asSequence()
        .filter { format ->
            val url = format.url.orEmpty()
            url.startsWith("http") &&
                !format.formatId.orEmpty().startsWith("sb") &&
                !format.ext.equals("mhtml", ignoreCase = true) &&
                !format.vcodec.equals("none", ignoreCase = true)
        }
        .maxByOrNull { format ->
            val hlsScore = if (format.isM3u8 || format.url.orEmpty().contains("m3u8", true)) {
                3_000_000
            } else 0
            val combinedScore = if (!format.acodec.isNullOrBlank() &&
                !format.acodec.equals("none", ignoreCase = true)
            ) 2_000_000 else 0
            hlsScore + combinedScore + format.height * 1_000 + format.tbr
        }
}

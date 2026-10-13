package com.myAllVideoBrowser.ui.main.player

import com.myAllVideoBrowser.data.local.room.entity.VideFormatEntityList
import com.myAllVideoBrowser.data.local.room.entity.VideoFormatEntity
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FloatingVideoFormatSelectorTest {
    @Test fun skipsYoutubeStoryboardsAndPrefersCombinedVideo() {
        val storyboard = VideoFormatEntity(
            formatId = "sb2", ext = "mhtml", vcodec = "none", url = "https://img/storyboard"
        )
        val videoOnly = VideoFormatEntity(
            formatId = "137", ext = "mp4", vcodec = "avc1", acodec = "none",
            height = 1080, url = "https://video/1080"
        )
        val combined = VideoFormatEntity(
            formatId = "18", ext = "mp4", vcodec = "avc1", acodec = "mp4a",
            height = 360, url = "https://video/360"
        )
        val info = VideoInfo(formats = VideFormatEntityList(listOf(storyboard, videoOnly, combined)))
        assertEquals("18", FloatingVideoFormatSelector.select(info)?.formatId)
    }

    @Test fun returnsNullWhenOnlyStoryboardsExist() {
        val info = VideoInfo(formats = VideFormatEntityList(listOf(
            VideoFormatEntity(formatId = "sb1", ext = "mhtml", vcodec = "none", url = "https://img")
        )))
        assertNull(FloatingVideoFormatSelector.select(info))
    }
}

package com.myAllVideoBrowser.ui.main.bookmarks

import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkRulesTest {
    private val items = listOf(
        PageInfo(name = "News", link = "https://example.com/news", folder = "Daily"),
        PageInfo(name = "Video", link = "https://media.test/watch", folder = "Media")
    )

    @Test fun filterMatchesTitleUrlAndFolder() {
        assertEquals(1, BookmarkRules.filter(items, "", "news").size)
        assertEquals(1, BookmarkRules.filter(items, "", "media.test").size)
        assertEquals(1, BookmarkRules.filter(items, "", "daily").size)
    }

    @Test fun filterCombinesFolderAndSearch() {
        assertEquals(items[1], BookmarkRules.filter(items, "Media", "video").single())
        assertTrue(BookmarkRules.filter(items, "Daily", "video").isEmpty())
    }

    @Test fun supportedUrlRequiresHttpHost() {
        assertTrue(BookmarkRules.isSupportedUrl("https://example.com/a"))
        assertTrue(BookmarkRules.isSupportedUrl("http://localhost:8080"))
        assertFalse(BookmarkRules.isSupportedUrl("javascript:alert(1)"))
        assertFalse(BookmarkRules.isSupportedUrl("https:///missing-host"))
    }

    @Test fun duplicateIgnoresCurrentBookmarkAndCase() {
        assertFalse(BookmarkRules.isDuplicate(items, items[0].link, "HTTPS://EXAMPLE.COM/NEWS"))
        assertTrue(BookmarkRules.isDuplicate(items, items[0].link, "HTTPS://MEDIA.TEST/WATCH"))
    }
}

package com.myAllVideoBrowser.ui.main.home.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEngineTest {
    @Test fun unknownIdFallsBackToDuckDuckGo() {
        assertEquals(SearchEngine.DUCKDUCKGO, SearchEngine.fromId("unknown"))
    }

    @Test fun queryIsSafelyEncoded() {
        val url = SearchEngine.BAIDU.searchUrl("安卓 浏览器&视频")
        assertTrue(url.startsWith("https://www.baidu.com/s?wd="))
        assertTrue(url.contains("%20"))
        assertTrue(url.contains("%26"))
    }

    @Test fun yandexUsesSelectedQueryTemplate() {
        assertEquals(
            "https://yandex.com/search/?text=android%20video",
            SearchEngine.YANDEX.searchUrl("android video")
        )
    }
}

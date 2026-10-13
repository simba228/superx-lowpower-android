package com.myAllVideoBrowser.ui.main.home.browser

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class SearchEngine(val id: String, val displayName: String, private val template: String) {
    DUCKDUCKGO("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    GOOGLE("google", "Google", "https://www.google.com/search?q=%s"),
    BING("bing", "Bing", "https://www.bing.com/search?q=%s"),
    BAIDU("baidu", "百度", "https://www.baidu.com/s?wd=%s"),
    YANDEX("yandex", "Yandex", "https://yandex.com/search/?text=%s");

    fun searchUrl(query: String): String = template.format(
        URLEncoder.encode(query, StandardCharsets.UTF_8.name()).replace("+", "%20")
    )

    companion object {
        @Volatile var current: SearchEngine = DUCKDUCKGO

        fun fromId(id: String?): SearchEngine = entries.firstOrNull { it.id == id } ?: DUCKDUCKGO
    }
}

package com.myAllVideoBrowser.ui.main.home.browser.webTab

import android.util.Patterns
import com.myAllVideoBrowser.ui.main.home.browser.SearchEngine

class WebTabFactory {
    companion object {
        fun createWebTabFromInput(input: String): WebTab {
            if (input.isNotEmpty()) {
                return if (input.startsWith("http://") || input.startsWith("https://")) {
                    WebTab(input, null, null, emptyMap())
                } else if (Patterns.WEB_URL.matcher(input).matches()) {
                    WebTab("https://$input", null, null, emptyMap())
                } else {
                    WebTab(
                        SearchEngine.current.searchUrl(input),
                        null,
                        null,
                        emptyMap())
                }
            }

            return WebTab.HOME_TAB
        }
    }
}

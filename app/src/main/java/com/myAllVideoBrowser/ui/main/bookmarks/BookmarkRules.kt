package com.myAllVideoBrowser.ui.main.bookmarks

import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import java.net.URI

object BookmarkRules {
    fun filter(items: List<PageInfo>, folder: String, query: String): List<PageInfo> {
        val needle = query.trim().lowercase()
        return items.filter { bookmark ->
            (folder.isBlank() || bookmark.folder == folder) &&
                (needle.isBlank() || bookmark.name.lowercase().contains(needle) ||
                    bookmark.link.lowercase().contains(needle) ||
                    bookmark.folder.lowercase().contains(needle))
        }
    }

    fun isSupportedUrl(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
            !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    fun isDuplicate(items: List<PageInfo>, currentLink: String, candidate: String): Boolean =
        items.any {
            !it.link.equals(currentLink, ignoreCase = true) &&
                it.link.equals(candidate.trim(), ignoreCase = true)
        }
}

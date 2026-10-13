package com.myAllVideoBrowser.ui.main.bookmarks

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.addCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.databinding.Observable
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.databinding.FragmentBookmarksBinding
import com.myAllVideoBrowser.ui.component.adapter.BookmarksAdapter
import com.myAllVideoBrowser.ui.component.adapter.BookmarksListener
import com.myAllVideoBrowser.ui.component.adapter.ReorderableItemTouchHelperCallback
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTabFactory
import com.myAllVideoBrowser.ui.main.progress.WrapContentLinearLayoutManager
import javax.inject.Inject

class BookmarksFragment : BaseFragment() {

    @Inject lateinit var mainActivity: MainActivity

    private lateinit var binding: FragmentBookmarksBinding
    private lateinit var adapter: BookmarksAdapter
    private var allBookmarks = mutableListOf<PageInfo>()
    private var selectedFolder = ""
    private var query = ""

    companion object {
        @JvmStatic fun newInstance() = BookmarksFragment()
    }

    private val bookmarksChanged = object : Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(sender: Observable?, propertyId: Int) {
            if (!::binding.isInitialized) return
            binding.root.post {
                if (!isAdded || view == null) return@post
                allBookmarks = mainActivity.mainViewModel.bookmarksList.get()?.toMutableList()
                    ?: mutableListOf()
                rebuildFolderChips()
                applyFilter()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        adapter = BookmarksAdapter(mutableListOf(), listener)
        binding = FragmentBookmarksBinding.inflate(inflater, container, false).apply {
            bookmarksContainer.setBackgroundColor(getThemeBackgroundColor())
            bookmarksList.layoutManager = WrapContentLinearLayoutManager(
                context, LinearLayoutManager.VERTICAL, false
            )
            bookmarksList.adapter = adapter
            toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }
            toolbar.inflateMenu(R.menu.bookmarks_menu)
            toolbar.setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.action_delete_all_bookmarks) {
                    confirmDeleteAllBookmarks()
                    true
                } else false
            }
            bookmarkSearch.doAfterTextChanged {
                query = it?.toString()?.trim()?.lowercase().orEmpty()
                applyFilter()
            }
        }

        ItemTouchHelper(
            ReorderableItemTouchHelperCallback(adapter) {
                query.isBlank() && selectedFolder.isBlank()
            }
        ).attachToRecyclerView(binding.bookmarksList)

        allBookmarks = mainActivity.mainViewModel.bookmarksList.get()?.toMutableList()
            ?: mutableListOf()
        rebuildFolderChips()
        applyFilter()
        mainActivity.mainViewModel.bookmarksList.addOnPropertyChangedCallback(bookmarksChanged)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            parentFragmentManager.popBackStack()
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, 0)
            binding.bookmarksList.setPadding(8, 8, 8, bars.bottom)
            insets
        }
        return binding.root
    }

    private fun rebuildFolderChips() {
        val group = binding.bookmarkFolders
        group.removeAllViews()
        val folders = allBookmarks.map { it.folder.trim() }.filter { it.isNotEmpty() }
            .distinct().sortedBy { it.lowercase() }
        val available = listOf("") + folders
        if (selectedFolder !in available) selectedFolder = ""
        available.forEach { folder ->
            group.addView(Chip(requireContext()).apply {
                id = View.generateViewId()
                text = folder.ifEmpty { getString(R.string.bookmark_all_folders) }
                isCheckable = true
                isChecked = folder == selectedFolder
                setOnClickListener {
                    selectedFolder = folder
                    applyFilter()
                }
            })
        }
    }

    private fun applyFilter() {
        val filtered = BookmarkRules.filter(allBookmarks, selectedFolder, query)
        adapter.setData(filtered.toMutableList())
    }

    private val listener = object : BookmarksListener {
        override fun onBookmarkOpenClicked(view: View, bookmarkItem: PageInfo) {
            openBookmark(bookmarkItem)
        }

        override fun onBookmarkLongClicked(view: View, bookmarkItem: PageInfo): Boolean {
            MaterialAlertDialogBuilder(requireContext())
                .setItems(
                    arrayOf(
                        getString(R.string.bookmark_open),
                        getString(R.string.bookmark_edit_action),
                        getString(R.string.delete)
                    )
                ) { _, which ->
                    when (which) {
                        0 -> openBookmark(bookmarkItem)
                        1 -> showEditDialog(bookmarkItem)
                        2 -> deleteWithUndo(bookmarkItem)
                    }
                }.show()
            return true
        }

        override fun onBookmarkMove(bookmarks: MutableList<PageInfo>) {
            if (query.isBlank() && selectedFolder.isBlank()) {
                allBookmarks = bookmarks.toMutableList()
                persist()
            }
        }

        override fun onBookmarkDelete(bookmarks: MutableList<PageInfo>, position: Int) {
            val deleted = bookmarks.getOrNull(position) ?: return
            deleteWithUndo(deleted)
        }
    }

    private fun openBookmark(bookmark: PageInfo) {
        mainActivity.mainViewModel.browserServicesProvider?.getOpenTabEvent()?.value =
            WebTabFactory.createWebTabFromInput(bookmark.link)
        parentFragmentManager.popBackStack()
    }

    private fun showEditDialog(bookmark: PageInfo) {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
        }
        val title = EditText(requireContext()).apply {
            hint = getString(R.string.bookmark_title_hint)
            setText(bookmark.name)
            setSelectAllOnFocus(true)
        }
        val url = EditText(requireContext()).apply {
            hint = getString(R.string.bookmark_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(bookmark.link)
        }
        val folder = EditText(requireContext()).apply {
            hint = getString(R.string.bookmark_folder_hint)
            setText(bookmark.folder)
        }
        form.addView(title)
        form.addView(url)
        form.addView(folder)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.bookmark_edit)
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save_proxy, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val editedUrl = url.text.toString().trim()
                if (!BookmarkRules.isSupportedUrl(editedUrl)) {
                    url.error = getString(R.string.bookmark_invalid_url)
                    return@setOnClickListener
                }
                val duplicate = BookmarkRules.isDuplicate(allBookmarks, bookmark.link, editedUrl)
                if (duplicate) {
                    url.error = getString(R.string.bookmark_duplicate_url)
                    return@setOnClickListener
                }
                val index = allBookmarks.indexOfFirst { it.link == bookmark.link }
                if (index >= 0) {
                    allBookmarks[index] = bookmark.copy(
                        name = title.text.toString().trim().ifEmpty { Uri.parse(editedUrl).host.orEmpty() },
                        link = editedUrl,
                        folder = folder.text.toString().trim()
                    )
                    persist()
                    rebuildFolderChips()
                    applyFilter()
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun deleteWithUndo(bookmark: PageInfo) {
        val index = allBookmarks.indexOfFirst { it.link == bookmark.link }
        if (index < 0) return
        allBookmarks.removeAt(index)
        persist()
        rebuildFolderChips()
        applyFilter()
        Snackbar.make(binding.root, R.string.bookmark_deleted, Snackbar.LENGTH_LONG)
            .setAction(R.string.bookmark_undo) {
                allBookmarks.add(index.coerceAtMost(allBookmarks.size), bookmark)
                persist()
                rebuildFolderChips()
                applyFilter()
            }.show()
    }

    private fun confirmDeleteAllBookmarks() {
        if (allBookmarks.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.bookmark_delete_all)
            .setMessage(getString(R.string.bookmark_delete_all_message, allBookmarks.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteAllWithUndo() }
            .show()
    }

    private fun deleteAllWithUndo() {
        val deleted = allBookmarks.toList()
        allBookmarks.clear()
        selectedFolder = ""
        persist()
        rebuildFolderChips()
        applyFilter()
        Snackbar.make(binding.root, R.string.bookmark_all_deleted, Snackbar.LENGTH_LONG)
            .setAction(R.string.bookmark_undo) {
                allBookmarks = deleted.toMutableList()
                persist()
                rebuildFolderChips()
                applyFilter()
            }.show()
    }

    private fun persist() {
        allBookmarks.forEachIndexed { index, bookmark -> bookmark.order = index }
        mainActivity.mainViewModel.updateBookmarks(allBookmarks)
    }

    override fun onDestroyView() {
        mainActivity.mainViewModel.bookmarksList.removeOnPropertyChangedCallback(bookmarksChanged)
        super.onDestroyView()
    }
}

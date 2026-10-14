package com.myAllVideoBrowser.ui.main.progress

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.databinding.Observable
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Recycler
import com.google.android.material.slider.Slider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.FragmentProgressBinding
import com.myAllVideoBrowser.ui.component.adapter.ProgressAdapter
import com.myAllVideoBrowser.ui.component.adapter.ProgressListener
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.home.MainViewModel
import com.myAllVideoBrowser.ui.main.settings.SettingsViewModel
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import javax.inject.Inject

class ProgressFragment : BaseFragment() {

    companion object {
        fun newInstance() = ProgressFragment()
    }

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var mainActivity: MainActivity

    private lateinit var progressViewModel: ProgressViewModel

    private lateinit var mainViewModel: MainViewModel

    private lateinit var settingsViewModel: SettingsViewModel

    private lateinit var dataBinding: FragmentProgressBinding

    private lateinit var progressAdapter: ProgressAdapter

    private val queueSizeCallback = object :
        Observable.OnPropertyChangedCallback() {
        override fun onPropertyChanged(
            sender: Observable?,
            propertyId: Int
        ) {
            val currentSize = settingsViewModel.queueSize.get()
            this@ProgressFragment.dataBinding.simsCountSlider.value =
                currentSize.coerceIn(1, 15).toFloat()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        mainViewModel = mainActivity.mainViewModel
        progressViewModel = mainActivity.progressViewModel
        settingsViewModel = mainActivity.settingsViewModel
        progressAdapter = ProgressAdapter(emptyList(), progressListener)

        dataBinding = FragmentProgressBinding.inflate(inflater, container, false).apply {
            val managerL =
                WrapContentLinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            this.mainViewModel = mainActivity.mainViewModel
            this.viewModel = progressViewModel
            this.settingsViewModel = mainActivity.settingsViewModel
            this.rvProgress.layoutManager = managerL
            this.rvProgress.adapter = progressAdapter

            this.simsCountSlider.valueTo = 15f
            this.simsCountSlider.value = settingsViewModel?.queueSize?.get()?.toFloat() ?: 1f
            this.simsCountSlider.addOnChangeListener(simsThreadsListener)
            this.startAllDownloads.setOnClickListener { progressViewModel.resumeAllDownloads() }
            this.pauseAllDownloads.setOnClickListener { progressViewModel.pauseAllDownloads() }
            this.selectAllDownloads.setOnClickListener { showStatusSelectionDialog() }
            this.deleteSelectedDownloads.setOnClickListener { confirmDeleteSelected() }
            val simsText =
                getString(R.string.queue_downloads_size) + " ${settingsViewModel?.queueSize?.get()}"
            this.simDownloadsCount.text = simsText
        }
        progressAdapter.onSelectionChanged = { count ->
            dataBinding.deleteSelectedDownloads.isEnabled = count > 0
            dataBinding.deleteSelectedDownloads.text = if (count > 0) {
                getString(R.string.delete_selected_count, count)
            } else {
                getString(R.string.delete_selected)
            }
        }

        return dataBinding.root
    }

    private fun confirmDeleteSelected() {
        val ids = progressAdapter.selectedDownloadIds()
        if (ids.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_selected)
            .setMessage(getString(R.string.delete_downloads_confirmation, ids.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                progressViewModel.cancelDownloads(ids, true)
                progressAdapter.clearSelection()
            }
            .show()
    }

    private fun showStatusSelectionDialog() {
        val labels = arrayOf(
            getString(R.string.download_status_all),
            getString(R.string.download_status_downloading),
            getString(R.string.download_status_paused),
            getString(R.string.download_status_waiting),
            getString(R.string.download_status_failed)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_by_status)
            .setItems(labels) { _, index ->
                val statuses = when (index) {
                    1 -> setOf(
                        VideoTaskState.PREPARE,
                        VideoTaskState.START,
                        VideoTaskState.DOWNLOADING,
                        VideoTaskState.PROXYREADY
                    )
                    2 -> setOf(VideoTaskState.PAUSE)
                    3 -> setOf(VideoTaskState.PENDING, VideoTaskState.ENQUEUE)
                    4 -> setOf(VideoTaskState.ERROR, VideoTaskState.ENOSPC)
                    else -> null
                }
                progressAdapter.selectByStatuses(statuses)
            }
            .show()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        handleDownloadVideoEvent()
        this.settingsViewModel.queueSize.addOnPropertyChangedCallback(queueSizeCallback)
    }

    override fun onDestroy() {
        this.dataBinding.simsCountSlider.removeOnChangeListener(simsThreadsListener)
        this.settingsViewModel.queueSize.removeOnPropertyChangedCallback(queueSizeCallback)
        super.onDestroy()
    }

    private val simsThreadsListener = Slider.OnChangeListener { _, value, fromUser ->
        if (fromUser) {
            val downloadsCount = value.toInt()
            settingsViewModel.setSimulationsCount(downloadsCount)
            progressViewModel.refreshDownloadQueue()
        }
    }

    private fun handleDownloadVideoEvent() {
        mainViewModel.downloadVideoEvent.observe(viewLifecycleOwner) { videoInfo ->
            val currentOriginal = videoInfo.originalUrl
            mainViewModel.currentOriginal.set(currentOriginal)
            progressViewModel.downloadVideo(this.context, videoInfo)
        }
    }

    private val progressListener = object : ProgressListener {
        override fun onMenuClicked(view: View, downloadId: Long) {
            showPopupMenu(view, downloadId)
        }
    }

    private fun showPopupMenu(view: View, downloadId: Long) {
        val menuCandidate =
            progressViewModel.progressInfos.get()?.find { it.downloadId == downloadId }

        val popupMenu = PopupMenu(view.context, view)
        popupMenu.menuInflater.inflate(R.menu.menu_progress, popupMenu.menu)

        popupMenu.menu.findItem(R.id.item_stop_save)?.isVisible = menuCandidate?.isLive == true

        popupMenu.setForceShowIcon(true)
        popupMenu.show()

        popupMenu.setOnMenuItemClickListener { arg0 ->
            when (arg0.itemId) {
                R.id.item_cancel -> {
                    progressViewModel.cancelDownload(downloadId, true)
                    true
                }

                R.id.item_pause -> {
                    progressViewModel.pauseDownload(downloadId)
                    true
                }

                R.id.item_resume -> {
                    progressViewModel.resumeDownload(downloadId)
                    true
                }

                R.id.item_stop_save -> {
                    progressViewModel.stopAndSaveDownload(downloadId)
                    true
                }

                else -> false
            }
        }
    }
}

class WrapContentLinearLayoutManager : LinearLayoutManager {
    constructor(context: Context?) : super(context) {}
    constructor(context: Context?, orientation: Int, reverseLayout: Boolean) : super(
        context, orientation, reverseLayout
    ) {
    }

    constructor(
        context: Context?, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes) {
    }

    override fun onLayoutChildren(recycler: Recycler, state: RecyclerView.State) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (e: IndexOutOfBoundsException) {
            AppLogger.e("meet a IOOBE in RecyclerView")
        }
    }
}

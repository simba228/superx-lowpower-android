package com.myAllVideoBrowser.ui.component.adapter

import android.content.Context
import android.util.DisplayMetrics
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.databinding.ItemProgressBinding

class ProgressAdapter(
    private var progressInfos: List<ProgressInfo>,
    private var videoListener: ProgressListener
) : RecyclerView.Adapter<ProgressAdapter.ProgressViewHolder>() {
    private val selectedIds = linkedSetOf<Long>()
    private var selectionMode = false
    var onSelectionChanged: ((Int) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProgressViewHolder {
        val binding = DataBindingUtil.inflate<ItemProgressBinding>(
            LayoutInflater.from(parent.context), R.layout.item_progress, parent, false
        )

        return ProgressViewHolder(binding)
    }

    override fun getItemCount() = progressInfos.size

    override fun onBindViewHolder(holder: ProgressViewHolder, position: Int) =
        holder.bind(
            progressInfos[position],
            videoListener,
            selectionMode,
            selectedIds.contains(progressInfos[position].downloadId)
        ) { downloadId -> toggleSelection(downloadId) }

    class ProgressViewHolder(val binding: ItemProgressBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(
            progressInfo: ProgressInfo,
            progressListener: ProgressListener,
            selectionMode: Boolean,
            isSelected: Boolean,
            onSelectionClick: (Long) -> Unit
        ) {
            val thumbnail = progressInfo.thumbnail
            val placeholder = R.drawable.ic_video_24dp
            val size = getScreenResolution(itemView.context)

            with(binding)
            {
                this.progressInfo = progressInfo
                this.progressListener = progressListener
                this.downloadId = progressInfo.downloadId
                selectionCheckbox.visibility = if (selectionMode) View.VISIBLE else View.GONE
                selectionCheckbox.isChecked = isSelected
                selectionCheckbox.setOnClickListener { onSelectionClick(progressInfo.downloadId) }
                cardProgress.setOnClickListener {
                    if (selectionMode) onSelectionClick(progressInfo.downloadId)
                }
                ivMore.visibility = if (selectionMode) View.GONE else View.VISIBLE

                Glide.with(this@ProgressViewHolder.itemView.context).load(thumbnail).fitCenter()
                    .error(placeholder)
                    .placeholder(placeholder)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .apply(RequestOptions().override(size.first / 8, size.second / 8))
                    .into(this.ivThumbnail)

                executePendingBindings()
            }
        }

        private fun getScreenResolution(context: Context): Pair<Int, Int> {
            val displayMetrics = DisplayMetrics()
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            windowManager.defaultDisplay.getMetrics(displayMetrics)

            val widthPixels = displayMetrics.widthPixels
            val heightPixels = displayMetrics.heightPixels

            return Pair(widthPixels, heightPixels)
        }
    }

    fun setData(progressInfos: List<ProgressInfo>) {
        this.progressInfos = progressInfos
        selectedIds.retainAll(progressInfos.map { it.downloadId }.toSet())
        notifyDataSetChanged()
    }

    fun selectAll() {
        selectByStatuses(null)
    }

    fun selectByStatuses(statuses: Set<Int>?) {
        selectionMode = true
        selectedIds.clear()
        selectedIds.addAll(
            progressInfos.asSequence()
                .filter { statuses == null || it.downloadStatus in statuses }
                .map { it.downloadId }
                .toList()
        )
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedIds.size)
    }

    fun clearSelection() {
        selectionMode = false
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    fun selectedDownloadIds(): Set<Long> = selectedIds.toSet()

    private fun toggleSelection(downloadId: Long) {
        if (!selectedIds.add(downloadId)) selectedIds.remove(downloadId)
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedIds.size)
    }
}

interface ProgressListener {
    fun onMenuClicked(view: View, downloadId: Long)
}

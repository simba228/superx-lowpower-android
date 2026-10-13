package com.myAllVideoBrowser.ui.component.adapter

import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import com.google.android.material.color.MaterialColors
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.databinding.ItemTopPageBinding
import java.util.Collections

class TopPageAdapter(
    private var pageInfos: MutableList<PageInfo>,
    private val itemListener: TopPagesListener
) : RecyclerView.Adapter<TopPageAdapter.TopPageViewHolder>(), ItemTouchHelperAdapter {

    private var editMode = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TopPageViewHolder =
        TopPageViewHolder(ItemTopPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: TopPageViewHolder, position: Int) {
        val page = pageInfos[position]
        with(holder.binding) {
            pageInfo = page
            listener = itemListener
            
            val placeholder = AppCompatResources.getDrawable(
                root.context, R.drawable.ic_browser
            )?.apply {
                setColorFilter(
                    MaterialColors.getColor(
                        root.context,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                        android.graphics.Color.GRAY
                    ),
                    PorterDuff.Mode.SRC_IN
                )
            }

            imgIcon.let { imageView ->
                Glide.with(imageView.context)
                    .load(page.favicon)
                    .placeholder(placeholder)
                    .error(placeholder)
                    .circleCrop()
                    .into(imageView)
            }

            deleteBookmark.setOnClickListener {
                val adapterPosition = holder.bindingAdapterPosition
                if (adapterPosition != RecyclerView.NO_POSITION) deleteAt(adapterPosition)
            }
            deleteBookmark.isVisible = editMode
            root.setOnLongClickListener(if (editMode) {
                View.OnLongClickListener {
                    exitEditMode()
                    true
                }
            } else null)
            executePendingBindings()
        }
    }

    override fun getItemCount() = pageInfos.size

    class TopPageViewHolder(val binding: ItemTopPageBinding) : RecyclerView.ViewHolder(binding.root)

    fun setData(pageInfos: List<PageInfo>) {
        this.pageInfos = pageInfos.toMutableList()
        notifyDataSetChanged()
    }

    fun currentItems(): List<PageInfo> = pageInfos.toList()

    fun enterEditMode() {
        if (editMode) return
        editMode = true
        notifyItemRangeChanged(0, itemCount)
    }

    fun isInEditMode(): Boolean = editMode

    private fun exitEditMode() {
        if (!editMode) return
        editMode = false
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onItemMove(fromPosition: Int, toPosition: Int) {
        if (fromPosition !in pageInfos.indices || toPosition !in pageInfos.indices) return
        Collections.swap(pageInfos, fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
        itemListener.onOrderChanged(pageInfos.toList())
    }

    override fun onItemDismiss(position: Int) = deleteAt(position)

    private fun deleteAt(position: Int) {
        if (position !in pageInfos.indices) return
        val deleted = pageInfos.removeAt(position)
        notifyItemRemoved(position)
        itemListener.onItemDeleted(deleted, position, pageInfos.toList())
    }

    interface TopPagesListener {
        fun onItemClicked(pageInfo: PageInfo)
        fun onOrderChanged(items: List<PageInfo>)
        fun onItemDeleted(pageInfo: PageInfo, position: Int, remaining: List<PageInfo>)
    }
}

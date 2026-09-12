package com.ufi_axis.installer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ufi_axis.installer.R
import com.ufi_axis.installer.databinding.ItemLogLineBinding
import com.ufi_axis.installer.logging.LogLine

/**
 * 日志列表适配器。
 *
 * 用 [ListAdapter] + DiffUtil 而不是 notifyDataSetChanged：
 * 安装期间日志滚动很快（权限就 12 行），全量刷新会明显掉帧。
 */
class LogAdapter : ListAdapter<LogLine, LogAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemLogLineBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class VH(private val binding: ItemLogLineBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(line: LogLine) {
            val ctx = binding.root.context
            binding.tvLogLine.text = line.formatForUi()
            binding.tvLogLine.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    when (line.level) {
                        LogLine.Level.INFO -> R.color.log_info
                        LogLine.Level.OK -> R.color.log_ok
                        LogLine.Level.WARN -> R.color.log_warn
                        LogLine.Level.ERROR -> R.color.log_error
                    }
                )
            )
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<LogLine>() {
            override fun areItemsTheSame(oldItem: LogLine, newItem: LogLine): Boolean =
                oldItem.timestamp == newItem.timestamp &&
                    oldItem.level == newItem.level &&
                    oldItem.message == newItem.message

            override fun areContentsTheSame(oldItem: LogLine, newItem: LogLine): Boolean =
                oldItem == newItem
        }
    }
}

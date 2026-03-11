package com.messenger.messengerclient.ui

import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.messenger.messengerclient.data.model.Message

class MessageAdapter(private val currentUser: String) :
    ListAdapter<Message, MessageAdapter.MessageViewHolder>(MessageDiffCallback()) {

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).senderUsername == currentUser) 0 else 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        // Создаем FrameLayout как корневой контейнер
        val container = FrameLayout(parent.context)
        container.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // Создаем ячейку
        val cell = ChatMessageCell(parent.context)

        // LayoutParams для ячейки внутри FrameLayout
        val cellParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        cellParams.gravity = if (viewType == 0) Gravity.END else Gravity.START
        cell.layoutParams = cellParams

        // Добавляем ячейку в контейнер
        container.addView(cell)

        return MessageViewHolder(container, cell)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MessageViewHolder(
        container: FrameLayout,
        private val cell: ChatMessageCell
    ) : RecyclerView.ViewHolder(container) {

        fun bind(message: Message) {
            val isOutgoing = message.senderUsername == currentUser
            cell.message = message
            cell.isOutgoing = isOutgoing
            cell.requestLayout()
        }
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean {
            return oldItem.id == newItem.id &&
                    oldItem.content == newItem.content &&
                    oldItem.timestamp == newItem.timestamp
        }
    }
}
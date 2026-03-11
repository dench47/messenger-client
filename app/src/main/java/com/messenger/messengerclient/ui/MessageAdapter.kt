package com.messenger.messengerclient.ui

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.messenger.messengerclient.data.model.Message

class MessageAdapter(private val currentUser: String) :
    ListAdapter<Message, MessageAdapter.MessageViewHolder>(MessageDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val cell = ChatMessageCell(parent.context)
        return MessageViewHolder(cell)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MessageViewHolder(private val cell: ChatMessageCell) :
        RecyclerView.ViewHolder(cell) {

        fun bind(message: Message) {
            val isOutgoing = message.senderUsername == currentUser
            cell.message = message
            cell.isOutgoing = isOutgoing
            cell.requestLayout() // обязательно!
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
package com.messenger.messengerclient.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.messenger.messengerclient.R
import com.messenger.messengerclient.data.model.Conversation
import com.messenger.messengerclient.databinding.ItemConversationBinding
import java.io.File

class ConversationAdapter(
    private val onItemClick: (Conversation) -> Unit
) : RecyclerView.Adapter<ConversationAdapter.ViewHolder>() {

    private var items = listOf<Conversation>()

    fun submitList(list: List<Conversation>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConversationBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(
        private val binding: ItemConversationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(conversation: Conversation) {
            val user = conversation.user
            val lastMsg = conversation.lastMessage

            // Имя
            binding.tvName.text = user.displayName ?: user.username

            // Аватарка
            val avatarFile = File(itemView.context.filesDir, "avatar_${user.username}.jpg")
            if (avatarFile.exists()) {
                Glide.with(itemView.context)
                    .load(avatarFile)
                    .circleCrop()
                    .into(binding.ivAvatar)
            } else {
                binding.ivAvatar.setImageResource(R.drawable.ic_default_avatar)
            }

            // Последнее сообщение
            binding.tvLastMessage.text = when {
                lastMsg == null -> "Нет сообщений"
                lastMsg.senderUsername == user.username -> lastMsg.content ?: "Вложение"
                else -> "Вы: ${lastMsg.content ?: "Вложение"}"
            }

            // Время последнего сообщения
            binding.tvTime.text = conversation.lastMessageTime ?: ""

            // Клик
            binding.root.setOnClickListener {
                onItemClick(conversation)
            }
        }
    }
}
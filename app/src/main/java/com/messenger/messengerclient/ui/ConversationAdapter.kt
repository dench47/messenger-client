package com.messenger.messengerclient.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.messenger.messengerclient.R
import com.messenger.messengerclient.data.model.Conversation
import com.messenger.messengerclient.config.ApiConfig
import com.messenger.messengerclient.databinding.ItemConversationBinding
import com.messenger.messengerclient.utils.DateUtils
import java.io.File

class ConversationAdapter(
    private val onItemClick: (Conversation) -> Unit
) : RecyclerView.Adapter<ConversationAdapter.ViewHolder>() {

    private var items = listOf<Conversation>()

    @SuppressLint("NotifyDataSetChanged")
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

            // Аватарка — сначала локально, потом с сервера
            val localFile = File(itemView.context.filesDir, "avatar_${user.username}.jpg")
            if (localFile.exists()) {
                Glide.with(itemView.context)
                    .load(localFile)
                    .circleCrop()
                    .into(binding.ivAvatar)
            } else if (!user.avatarUrl.isNullOrEmpty()) {
                // 👇 ДОБАВЛЯЕМ BASE_URL
                val fullAvatarUrl = ApiConfig.BASE_URL + user.avatarUrl
                Glide.with(itemView.context)
                    .load(fullAvatarUrl)
                    .circleCrop()
                    .into(binding.ivAvatar)
            } else {
                binding.ivAvatar.setImageResource(R.drawable.ic_default_avatar)
            }

            // Последнее сообщение
            binding.tvLastMessage.text = when {
                lastMsg == null -> "Нет сообщений"
                lastMsg.senderUsername == user.username -> lastMsg.content
                else -> "Вы: ${lastMsg.content}"
            }

            // Время последнего сообщения
            binding.tvTime.text = DateUtils.formatMessageTime(conversation.lastMessageTime)

            // Клик
            binding.root.setOnClickListener {
                onItemClick(conversation)
            }
        }
    }
}
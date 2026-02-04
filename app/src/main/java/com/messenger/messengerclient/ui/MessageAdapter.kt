package com.messenger.messengerclient.ui

import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.messenger.messengerclient.R
import com.messenger.messengerclient.data.model.Message
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class MessageAdapter(private val currentUser: String) : ListAdapter<Message, RecyclerView.ViewHolder>(MessageDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_SENT = 1
        private const val VIEW_TYPE_RECEIVED = 2
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).senderUsername == currentUser) VIEW_TYPE_SENT else VIEW_TYPE_RECEIVED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_SENT -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_message_sent, parent, false)
                SentMessageViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_message_received, parent, false)
                ReceivedMessageViewHolder(view)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)

        when (holder) {
            is SentMessageViewHolder -> holder.bind(message)
            is ReceivedMessageViewHolder -> holder.bind(message)
        }
    }

    inner class SentMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tv_message)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_time)

        @RequiresApi(Build.VERSION_CODES.O)
        fun bind(message: Message) {
            tvMessage.text = message.content
            tvTime.text = formatTime(message.timestamp)
        }
    }

    inner class ReceivedMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tv_message)
        private val tvSender: TextView = itemView.findViewById(R.id.tv_sender)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_time)

        @RequiresApi(Build.VERSION_CODES.O)
        fun bind(message: Message) {
            tvMessage.text = message.content
            tvSender.text = message.senderUsername
            tvTime.text = formatTime(message.timestamp)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun formatTime(timestamp: String?): String {
        if (timestamp.isNullOrEmpty()) return ""

        return try {
            val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
            val dateTime = LocalDateTime.parse(timestamp, formatter)
            dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))
        } catch (e: DateTimeParseException) {
            // Если не получается распарсить, пытаемся извлечь время
            try {
                timestamp.substring(11, 16) // Берем "HH:mm" из "yyyy-MM-ddTHH:mm:ss"
            } catch (e2: Exception) {
                timestamp.take(5) // Берем первые 5 символов
            }
        }
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean {
            // Сравниваем по ID если есть, иначе по содержанию и времени
            return if (oldItem.id != null && newItem.id != null) {
                oldItem.id == newItem.id
            } else {
                oldItem.content == newItem.content &&
                        oldItem.senderUsername == newItem.senderUsername &&
                        oldItem.timestamp == newItem.timestamp
            }
        }



        override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean {
            return oldItem == newItem
        }
    }
}
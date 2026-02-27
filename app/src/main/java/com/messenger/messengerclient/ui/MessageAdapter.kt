package com.messenger.messengerclient.ui

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
        private val tvMessageColumn: TextView = itemView.findViewById(R.id.tv_message_column)
        private val tvTimeColumn: TextView = itemView.findViewById(R.id.tv_time_column)
        private val messageRow: View = itemView.findViewById(R.id.message_row)
        private val messageColumn: View = itemView.findViewById(R.id.message_column)
        private val messageContainer: View = itemView.findViewById(R.id.message_container)

        fun bind(message: Message) {
            // Сначала показываем column вариант (время снизу) для всех сообщений
            messageRow.visibility = View.GONE
            messageColumn.visibility = View.VISIBLE
            tvMessageColumn.text = message.content
            tvTimeColumn.text = formatTime(message.timestamp)

            // После отрисовки проверяем, помещается ли текст в одну строку
            messageContainer.post {
                val lineCount = tvMessageColumn.lineCount
                // Если текст в одну строку и не очень длинный, переключаем на row вариант (время справа)
                if (lineCount == 1 && tvMessageColumn.text.length < 25) {
                    messageRow.visibility = View.VISIBLE
                    messageColumn.visibility = View.GONE
                    tvMessage.text = message.content
                    tvTime.text = formatTime(message.timestamp)
                }
            }
        }
    }

    inner class ReceivedMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tv_message)
        private val tvSender: TextView = itemView.findViewById(R.id.tv_sender)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_time)
        private val tvMessageColumn: TextView = itemView.findViewById(R.id.tv_message_column)
        private val tvTimeColumn: TextView = itemView.findViewById(R.id.tv_time_column)
        private val messageRow: View = itemView.findViewById(R.id.message_row)
        private val messageColumn: View = itemView.findViewById(R.id.message_column)
        private val messageContainer: View = itemView.findViewById(R.id.message_container)

        fun bind(message: Message) {
            tvSender.text = message.senderUsername

            // Сначала показываем column вариант (время снизу) для всех сообщений
            messageRow.visibility = View.GONE
            messageColumn.visibility = View.VISIBLE
            tvMessageColumn.text = message.content
            tvTimeColumn.text = formatTime(message.timestamp)

            // После отрисовки проверяем, помещается ли текст в одну строку
            messageContainer.post {
                val lineCount = tvMessageColumn.lineCount
                // Если текст в одну строку и не очень длинный, переключаем на row вариант (время справа)
                if (lineCount == 1 && tvMessageColumn.text.length < 25) {
                    messageRow.visibility = View.VISIBLE
                    messageColumn.visibility = View.GONE
                    tvMessage.text = message.content
                    tvTime.text = formatTime(message.timestamp)
                }
            }
        }
    }

    private fun formatTime(timestamp: String?): String {
        if (timestamp.isNullOrEmpty()) return ""

        return try {
            val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
            val dateTime = LocalDateTime.parse(timestamp, formatter)
            dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))
        } catch (_: DateTimeParseException) {
            try {
                timestamp.substring(11, 16)
            } catch (_: Exception) {
                timestamp.take(5)
            }
        }
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean {
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
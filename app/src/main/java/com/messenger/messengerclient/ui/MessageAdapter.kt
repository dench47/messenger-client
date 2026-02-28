package com.messenger.messengerclient.ui

import android.text.Layout
import android.util.Log
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
        private const val TIME_WIDTH_DP = 45
        private const val MAX_SINGLE_LINE_CHARS = 20 // Если символов больше, даже в одну строку время вниз
        private const val TAG = "MessageAdapter"
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
        private val tvMessageMulti: TextView = itemView.findViewById(R.id.tv_message_multi)
        private val tvTimeMulti: TextView = itemView.findViewById(R.id.tv_time_multi)
        private val tvMessageBottom: TextView = itemView.findViewById(R.id.tv_message_bottom)
        private val tvTimeBottom: TextView = itemView.findViewById(R.id.tv_time_bottom)
        private val layoutSingleLine: View = itemView.findViewById(R.id.layout_single_line)
        private val layoutMultiLine: View = itemView.findViewById(R.id.layout_multi_line)
        private val layoutBottomTime: View = itemView.findViewById(R.id.layout_bottom_time)
        private val messageContainer: View = itemView.findViewById(R.id.message_container)

        fun bind(message: Message) {
            Log.d(TAG, "Binding sent message: ${message.content}")

            // Устанавливаем текст во все TextView
            tvMessage.text = message.content
            tvMessageMulti.text = message.content
            tvMessageBottom.text = message.content

            val timeText = formatTime(message.timestamp)
            tvTime.text = timeText
            tvTimeMulti.text = timeText
            tvTimeBottom.text = timeText

            // Показываем мультилайн как базовый
            layoutSingleLine.visibility = View.GONE
            layoutMultiLine.visibility = View.VISIBLE
            layoutBottomTime.visibility = View.GONE

            messageContainer.post {
                try {
                    val lineCount = tvMessageMulti.lineCount
                    Log.d(TAG, "Line count: $lineCount")

                    val layout = tvMessageMulti.layout ?: return@post

                    when {
                        // Одна строка
                        lineCount == 1 -> {
                            // Проверяем длину текста
                            if (message.content.length > MAX_SINGLE_LINE_CHARS) {
                                Log.d(TAG, "Single line but long text (${message.content.length} chars) - using bottom time")
                                // Длинный текст - время снизу
                                layoutMultiLine.visibility = View.GONE
                                layoutBottomTime.visibility = View.VISIBLE
                            } else {
                                Log.d(TAG, "Single line short text - using single line layout")
                                // Короткий текст - время справа
                                layoutSingleLine.visibility = View.VISIBLE
                                layoutMultiLine.visibility = View.GONE
                            }
                        }

                        // Много строк - проверяем последнюю строку
                        else -> {
                            val lastLineIndex = lineCount - 1
                            val lastLineWidth = layout.getLineWidth(lastLineIndex)
                            val maxWidth = tvMessageMulti.width - tvMessageMulti.totalPaddingLeft - tvMessageMulti.totalPaddingRight
                            val timeWidthPx = (TIME_WIDTH_DP * tvMessageMulti.resources.displayMetrics.density).toInt()

                            val hasSpaceForTime = (maxWidth - lastLineWidth) >= timeWidthPx
                            Log.d(TAG, "Last line width: $lastLineWidth, max: $maxWidth, hasSpace: $hasSpaceForTime")

                            if (!hasSpaceForTime) {
                                Log.d(TAG, "No space - switching to bottom time")
                                // Переключаемся на вариант с временем снизу
                                layoutMultiLine.visibility = View.GONE
                                layoutBottomTime.visibility = View.VISIBLE
                            }
                            // Если есть место - оставляем мультилайн (он уже видим)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in post", e)
                }
            }
        }
    }

    inner class ReceivedMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSender: TextView = itemView.findViewById(R.id.tv_sender)
        private val tvMessage: TextView = itemView.findViewById(R.id.tv_message)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_time)
        private val tvMessageMulti: TextView = itemView.findViewById(R.id.tv_message_multi)
        private val tvTimeMulti: TextView = itemView.findViewById(R.id.tv_time_multi)
        private val tvMessageBottom: TextView = itemView.findViewById(R.id.tv_message_bottom)
        private val tvTimeBottom: TextView = itemView.findViewById(R.id.tv_time_bottom)
        private val layoutSingleLine: View = itemView.findViewById(R.id.layout_single_line)
        private val layoutMultiLine: View = itemView.findViewById(R.id.layout_multi_line)
        private val layoutBottomTime: View = itemView.findViewById(R.id.layout_bottom_time)
        private val messageContainer: View = itemView.findViewById(R.id.message_container)

        fun bind(message: Message) {
            Log.d(TAG, "Binding received message: ${message.content}")

            tvSender.text = message.senderUsername

            // Устанавливаем текст во все TextView
            tvMessage.text = message.content
            tvMessageMulti.text = message.content
            tvMessageBottom.text = message.content

            val timeText = formatTime(message.timestamp)
            tvTime.text = timeText
            tvTimeMulti.text = timeText
            tvTimeBottom.text = timeText

            // Показываем мультилайн как базовый
            layoutSingleLine.visibility = View.GONE
            layoutMultiLine.visibility = View.VISIBLE
            layoutBottomTime.visibility = View.GONE

            messageContainer.post {
                try {
                    val lineCount = tvMessageMulti.lineCount
                    Log.d(TAG, "Line count: $lineCount")

                    val layout = tvMessageMulti.layout ?: return@post

                    when {
                        // Одна строка
                        lineCount == 1 -> {
                            // Проверяем длину текста
                            if (message.content.length > MAX_SINGLE_LINE_CHARS) {
                                Log.d(TAG, "Single line but long text (${message.content.length} chars) - using bottom time")
                                // Длинный текст - время снизу
                                layoutMultiLine.visibility = View.GONE
                                layoutBottomTime.visibility = View.VISIBLE
                            } else {
                                Log.d(TAG, "Single line short text - using single line layout")
                                // Короткий текст - время справа
                                layoutSingleLine.visibility = View.VISIBLE
                                layoutMultiLine.visibility = View.GONE
                            }
                        }

                        // Много строк - проверяем последнюю строку
                        else -> {
                            val lastLineIndex = lineCount - 1
                            val lastLineWidth = layout.getLineWidth(lastLineIndex)
                            val maxWidth = tvMessageMulti.width - tvMessageMulti.totalPaddingLeft - tvMessageMulti.totalPaddingRight
                            val timeWidthPx = (TIME_WIDTH_DP * tvMessageMulti.resources.displayMetrics.density).toInt()

                            val hasSpaceForTime = (maxWidth - lastLineWidth) >= timeWidthPx
                            Log.d(TAG, "Last line width: $lastLineWidth, max: $maxWidth, hasSpace: $hasSpaceForTime")

                            if (!hasSpaceForTime) {
                                Log.d(TAG, "No space - switching to bottom time")
                                // Переключаемся на вариант с временем снизу
                                layoutMultiLine.visibility = View.GONE
                                layoutBottomTime.visibility = View.VISIBLE
                            }
                            // Если есть место - оставляем мультилайн
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in post", e)
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
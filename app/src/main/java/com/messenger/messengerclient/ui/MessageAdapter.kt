package com.messenger.messengerclient.ui

import android.text.Layout
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
        private const val MAX_SINGLE_LINE_CHARS = 20
        private const val TAG = "MessageAdapter"

        // 👇 Константы для payload (частичное обновление)
        const val PAYLOAD_STATUS = "status"
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

    // 👇 НОВЫЙ МЕТОД - для частичного обновления (только статус)
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            // Если нет payload - обычное полное обновление
            onBindViewHolder(holder, position)
            return
        }

        val message = getItem(position)

        // Проверяем, что это наше сообщение (отправленное) и есть payload статуса
        if (holder is SentMessageViewHolder && payloads.contains(PAYLOAD_STATUS)) {
            holder.updateStatusOnly(message.status)
            Log.d(TAG, "🔄 Частичное обновление статуса для сообщения ${message.id}: ${message.status}")
        } else {
            // Если что-то другое - полное обновление
            onBindViewHolder(holder, position)
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

        // 👇 ImageView для статусов
        private val ivStatusSingle: ImageView = itemView.findViewById(R.id.iv_status_single)
        private val ivStatusMulti: ImageView = itemView.findViewById(R.id.iv_status_multi)
        private val ivStatusBottom: ImageView = itemView.findViewById(R.id.iv_status_bottom)

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

            // 👇 Устанавливаем статус
            updateStatusIcon(message.status)

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
                            if (message.content.length > MAX_SINGLE_LINE_CHARS) {
                                Log.d(TAG, "Single line but long text (${message.content.length} chars) - using bottom time")
                                layoutMultiLine.visibility = View.GONE
                                layoutBottomTime.visibility = View.VISIBLE
                            } else {
                                Log.d(TAG, "Single line short text - using single line layout")
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
                                layoutMultiLine.visibility = View.GONE
                                layoutBottomTime.visibility = View.VISIBLE
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in post", e)
                }
            }
        }

        // 👇 НОВЫЙ МЕТОД - обновление только статуса (для payload)
        fun updateStatusOnly(status: String) {
            updateStatusIcon(status)
            Log.d(TAG, "✅ Статус обновлен (частично): $status")
        }

        // 👇 НОВЫЙ МЕТОД - установка иконки статуса
        private fun updateStatusIcon(status: String) {
            val iconRes = when (status) {
                "SENT" -> R.drawable.ic_check_sent
                "DELIVERED" -> R.drawable.ic_check_delivered
                "READ" -> R.drawable.ic_check_read
                else -> R.drawable.ic_check_sent
            }

            // Обновляем во всех вариантах layout'а (активный будет видимым)
            ivStatusSingle.setImageResource(iconRes)
            ivStatusMulti.setImageResource(iconRes)
            ivStatusBottom.setImageResource(iconRes)
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

            tvMessage.text = message.content
            tvMessageMulti.text = message.content
            tvMessageBottom.text = message.content

            val timeText = formatTime(message.timestamp)
            tvTime.text = timeText
            tvTimeMulti.text = timeText
            tvTimeBottom.text = timeText

            layoutSingleLine.visibility = View.GONE
            layoutMultiLine.visibility = View.VISIBLE
            layoutBottomTime.visibility = View.GONE

            messageContainer.post {
                try {
                    val lineCount = tvMessageMulti.lineCount
                    Log.d(TAG, "Line count: $lineCount")

                    val layout = tvMessageMulti.layout ?: return@post

                    when {
                        lineCount == 1 -> {
                            if (message.content.length > MAX_SINGLE_LINE_CHARS) {
                                Log.d(TAG, "Single line but long text - using bottom time")
                                layoutMultiLine.visibility = View.GONE
                                layoutBottomTime.visibility = View.VISIBLE
                            } else {
                                Log.d(TAG, "Single line short text - using single line layout")
                                layoutSingleLine.visibility = View.VISIBLE
                                layoutMultiLine.visibility = View.GONE
                            }
                        }

                        else -> {
                            val lastLineIndex = lineCount - 1
                            val lastLineWidth = layout.getLineWidth(lastLineIndex)
                            val maxWidth = tvMessageMulti.width - tvMessageMulti.totalPaddingLeft - tvMessageMulti.totalPaddingRight
                            val timeWidthPx = (TIME_WIDTH_DP * tvMessageMulti.resources.displayMetrics.density).toInt()

                            val hasSpaceForTime = (maxWidth - lastLineWidth) >= timeWidthPx
                            Log.d(TAG, "Last line width: $lastLineWidth, max: $maxWidth, hasSpace: $hasSpaceForTime")

                            if (!hasSpaceForTime) {
                                Log.d(TAG, "No space - switching to bottom time")
                                layoutMultiLine.visibility = View.GONE
                                layoutBottomTime.visibility = View.VISIBLE
                            }
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

        // 👇 НОВЫЙ МЕТОД - определяем, что именно изменилось
        override fun getChangePayload(oldItem: Message, newItem: Message): Any? {
            // Если изменился только статус - возвращаем PAYLOAD_STATUS
            if (oldItem.id == newItem.id &&
                oldItem.content == newItem.content &&
                oldItem.senderUsername == newItem.senderUsername &&
                oldItem.receiverUsername == newItem.receiverUsername &&
                oldItem.timestamp == newItem.timestamp &&
                oldItem.isRead == newItem.isRead &&
                oldItem.type == newItem.type &&
                oldItem.status != newItem.status) {

                return PAYLOAD_STATUS
            }

            // Если изменилось что-то еще - полное обновление
            return null
        }
    }
}
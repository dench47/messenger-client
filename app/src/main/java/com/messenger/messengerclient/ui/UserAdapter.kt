package com.messenger.messengerclient.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.messenger.messengerclient.R
import com.messenger.messengerclient.data.model.User

class UserAdapter(
    private val listener: OnUserClickListener
) : ListAdapter<User, UserAdapter.UserViewHolder>(UserDiffCallback()) {

    interface OnUserClickListener {
        fun onUserClick(user: User)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_user, parent, false)
        return UserViewHolder(view)
    }

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvUsername: TextView = itemView.findViewById(R.id.tv_username)
        private val tvStatus: TextView = itemView.findViewById(R.id.tv_status)

        fun bind(user: User) {
            println("🔄 [UserAdapter] Binding ${user.username}: status='${user.status}', lastSeenText='${user.lastSeenText}'")

            println("🔄 [UserAdapter] Binding ${user.username}:")
            println("   📊 Online: ${user.online}")
            println("   🏷️ Status: '${user.status}'")
            println("   ⏰ lastSeenText: '${user.lastSeenText}'") // ← ЭТО ВАЖНО!

            tvUsername.text = user.displayName ?: user.username

            // Определяем текст статуса
            val statusText = when (user.status) {
                "online", "active" -> "online"
                "inactive" -> user.lastSeenText ?: "был недавно" // ← ИСПОЛЬЗУЕМ lastSeenText!
                "offline" -> user.lastSeenText ?: "offline"
                else -> if (user.online) "online" else user.lastSeenText ?: "offline"
            }

            println("🔄 [UserAdapter] Status text for ${user.username}: '$statusText'")

            tvStatus.text = statusText

            // Цвет в зависимости от статуса
            val statusColor = when {
                user.status == "online" || user.status == "active" -> Color.GREEN
                user.status == "inactive" -> Color.parseColor("#FF9800") // оранжевый
                else -> Color.GRAY // offline
            }
            tvStatus.setTextColor(statusColor)

            itemView.setOnClickListener {
                listener.onUserClick(user)
            }
        }
    }

    class UserDiffCallback : DiffUtil.ItemCallback<User>() {
        override fun areItemsTheSame(oldItem: User, newItem: User): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: User, newItem: User): Boolean {
            return oldItem == newItem
        }
    }
}
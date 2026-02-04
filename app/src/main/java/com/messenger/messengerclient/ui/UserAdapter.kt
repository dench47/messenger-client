package com.messenger.messengerclient.ui

import android.graphics.Color
import android.util.Log
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
            Log.d("UserAdapter", "🔥 BIND ${user.username}: isOnline=${user.isOnline}, status='${user.status}', lastSeenText='${user.lastSeenText}'")

            tvUsername.text = user.displayName ?: user.username

            // Текст статуса
            tvStatus.text = user.lastSeenText ?:
                    if (user.isOnline) "online" else "offline"

            // Цвет статуса
            tvStatus.setTextColor(
                if (user.isOnline) Color.GREEN else Color.GRAY
            )

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
            // Сравниваем только актуальные поля
            return oldItem.status == newItem.status &&
                    oldItem.lastSeenText == newItem.lastSeenText
            // УБРАТЬ: oldItem.online == newItem.online
        }
    }
}
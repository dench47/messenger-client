package com.messenger.messengerclient.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.messenger.messengerclient.data.model.User
import com.messenger.messengerclient.databinding.ItemSearchUserBinding

class SearchUsersAdapter(
    private val onAddClick: (User) -> Unit
) : RecyclerView.Adapter<SearchUsersAdapter.ViewHolder>() {

    private var items = listOf<User>()

    fun submitList(list: List<User>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSearchUserBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(
        private val binding: ItemSearchUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(user: User) {
            binding.username.text = user.displayName ?: user.username
            binding.status.text = user.status ?: "offline"

            binding.addButton.setOnClickListener {
                onAddClick(user)
            }
        }
    }
}
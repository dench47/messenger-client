package com.messenger.messengerclient.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.messenger.messengerclient.databinding.ActivitySearchUsersBinding
import com.messenger.messengerclient.network.RetrofitClient
import com.messenger.messengerclient.service.UserService
import com.messenger.messengerclient.utils.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.messenger.messengerclient.data.model.User
import com.messenger.messengerclient.utils.ActivityCounter

class SearchUsersActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchUsersBinding
    private lateinit var prefsManager: PrefsManager
    private lateinit var userService: UserService
    private lateinit var adapter: SearchUsersAdapter
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchUsersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = PrefsManager(this)
        userService = RetrofitClient.getClient().create(UserService::class.java)

        setupUI()
        setupSearch()

        // Сообщаем, что активити открыта
        ActivityCounter.activityStarted("SearchUsersActivity")
        ActivityCounter.updateCurrentActivity("SearchUsersActivity")
    }

    private fun setupUI() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Добавить контакт"

        adapter = SearchUsersAdapter { user ->
            addToContacts(user)
        }

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@SearchUsersActivity)
            adapter = this@SearchUsersActivity.adapter
        }
    }

    private fun setupSearch() {
        binding.searchEditText.addTextChangedListener {
            searchJob?.cancel()
            searchJob = CoroutineScope(Dispatchers.Main).launch {
                delay(500) // debounce
                val query = binding.searchEditText.text.toString()
                if (query.length >= 2) {
                    searchUsers(query)
                } else {
                    adapter.submitList(emptyList())
                }
            }
        }
    }

    private fun searchUsers(query: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = userService.searchUsers(query)
                runOnUiThread {
                    if (response.isSuccessful) {
                        val users = response.body()?.filter {
                            it.username != prefsManager.username
                        } ?: emptyList()
                        adapter.submitList(users)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun addToContacts(user: User) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = mapOf(
                    "username" to prefsManager.username,
                    "contactUsername" to user.username
                )
                val response = userService.addContact(request)

                runOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(
                            this@SearchUsersActivity,
                            "${user.displayName ?: user.username} добавлен", Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    } else {
                        Toast.makeText(
                            this@SearchUsersActivity,
                            "Ошибка добавления", Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        ActivityCounter.activityStarted("SearchUsersActivity")
        ActivityCounter.updateCurrentActivity("SearchUsersActivity")
    }

    override fun onPause() {
        super.onPause()
        ActivityCounter.activityStopped()
    }

    override fun onDestroy() {
        super.onDestroy()
        ActivityCounter.activityStopped()
    }
}
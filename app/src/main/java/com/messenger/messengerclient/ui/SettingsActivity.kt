package com.messenger.messengerclient.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.messenger.messengerclient.R
import com.messenger.messengerclient.databinding.ActivitySettingsBinding
import com.messenger.messengerclient.utils.PrefsManager
import java.io.File
import java.io.FileOutputStream

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefsManager: PrefsManager

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                saveAvatarToInternalStorage(uri)
                loadAvatar() // перезагружаем с очисткой кэша
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = PrefsManager(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Настройки"

        loadUserInfo()
        loadAvatar()
        setupClickListeners()
    }

    private fun loadUserInfo() {
        val username = prefsManager.displayName ?: prefsManager.username ?: "Пользователь"
        binding.tvUsername.text = username
    }

    private fun loadAvatar() {
        val avatarFile = File(filesDir, "avatar_${prefsManager.username}.jpg")

        if (avatarFile.exists()) {
            // Очищаем кэш Glide для этого файла
            Glide.with(this)
                .load(avatarFile)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .circleCrop()
                .into(binding.ivAvatar)
        } else {
            binding.ivAvatar.setImageResource(R.drawable.ic_default_avatar)
        }
    }

    private fun saveAvatarToInternalStorage(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val file = File(filesDir, "avatar_${prefsManager.username}.jpg")

            // Удаляем старый файл, если есть
            if (file.exists()) {
                file.delete()
            }

            val outputStream = FileOutputStream(file)
            inputStream?.copyTo(outputStream)
            inputStream?.close()
            outputStream.close()

            Toast.makeText(this, "Аватарка сохранена", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Ошибка сохранения", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupClickListeners() {
        binding.btnChangeAvatar.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            pickImageLauncher.launch(intent)
        }

        binding.btnChangePassword.setOnClickListener {
            Toast.makeText(this, "Смена пароля (будет позже)", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
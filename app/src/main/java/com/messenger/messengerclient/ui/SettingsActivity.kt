package com.messenger.messengerclient.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.Log
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.messenger.messengerclient.R
import com.messenger.messengerclient.databinding.ActivitySettingsBinding
import com.messenger.messengerclient.network.RetrofitClient
import com.messenger.messengerclient.service.UserService
import com.messenger.messengerclient.utils.ActivityCounter
import com.messenger.messengerclient.utils.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefsManager: PrefsManager
    private lateinit var userService: UserService

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
//                saveAvatarToInternalStorage(uri)
                uploadAvatarToServer(uri)
//                loadAvatar() // перезагружаем с очисткой кэша
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = PrefsManager(this)

        // 👇 ИНИЦИАЛИЗАЦИЯ userService ДО ВСЕГО
        RetrofitClient.initialize(this)
        userService = RetrofitClient.getClient().create(UserService::class.java)

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
        val currentUser = prefsManager.username ?: return

        // Ищем любой файл avatar_currentUser.*
        val existingFiles = filesDir.listFiles { _, name ->
            name.startsWith("avatar_${currentUser}.")
        }

        if (existingFiles?.isNotEmpty() == true) {
            // Сортируем по дате изменения (самый новый последний)
            val latestFile = existingFiles.maxByOrNull { it.lastModified() }

            Glide.with(this)
                .load(latestFile)
                .diskCacheStrategy(DiskCacheStrategy.NONE)  // Не кэшировать на диске
                .skipMemoryCache(true)                        // Не кэшировать в памяти
                .circleCrop()
                .into(binding.ivAvatar)

            Log.d("SettingsActivity", "✅ Loaded avatar: ${latestFile?.name}")
            return
        }

        // Дефолтная аватарка
        binding.ivAvatar.setImageResource(R.drawable.ic_default_avatar)
        Log.d("SettingsActivity", "ℹ️ No avatar found, using default")
    }


    private fun uploadAvatarToServer(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val tempFile = File(cacheDir, "avatar_${System.currentTimeMillis()}.jpg")
                FileOutputStream(tempFile).use { outputStream ->
                    inputStream?.copyTo(outputStream)
                }
                inputStream?.close()

                val requestBody = tempFile.asRequestBody("image/*".toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("avatar", tempFile.name, requestBody)

                val response = userService.uploadAvatar(part)

                runOnUiThread {
                    if (response.isSuccessful) {
                        // Удаляем старый локальный файл
                        val oldFile = File(filesDir, "avatar_${prefsManager.username}.jpg")
                        if (oldFile.exists()) {
                            oldFile.delete()
                        }

                        // Сохраняем новую аватарку локально
                        saveAvatarToInternalStorage(uri)

                        // Перезагружаем аватарку в UI
                        loadAvatar()

                        Toast.makeText(this@SettingsActivity, "Аватарка обновлена", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@SettingsActivity, "Ошибка загрузки", Toast.LENGTH_SHORT).show()
                    }
                    tempFile.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this@SettingsActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveAvatarToInternalStorage(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)

            // Получаем расширение из Uri или определяем по MIME-типу
            val extension = getFileExtension(uri) ?: "jpg"
            val newFile = File(filesDir, "avatar_${prefsManager.username}.$extension")

            // Удаляем ВСЕ старые файлы этого пользователя (любого расширения)
            val existingFiles = filesDir.listFiles { _, name ->
                name.startsWith("avatar_${prefsManager.username}.")
            }
            existingFiles?.forEach {
                it.delete()
                Log.d("SettingsActivity", "🗑️ Deleted old avatar: ${it.name}")
            }

            // Сохраняем новый файл
            FileOutputStream(newFile).use { outputStream ->
                inputStream?.copyTo(outputStream)
            }
            inputStream?.close()

            Log.d("SettingsActivity", "✅ Saved new avatar: ${newFile.name}")
            Toast.makeText(this, "Аватарка сохранена", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Ошибка сохранения", Toast.LENGTH_SHORT).show()
        }
    }


    private fun getFileExtension(uri: Uri): String? {
        return when (contentResolver.getType(uri)) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/bmp" -> "bmp"
            else -> "jpg" // по умолчанию
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

    override fun onResume() {
        super.onResume()
        ActivityCounter.activityStarted("SettingsActivity")
        ActivityCounter.updateCurrentActivity("SettingsActivity")
    }

    override fun onPause() {
        super.onPause()
        ActivityCounter.activityStopped()
    }

//    override fun onDestroy() {
//        super.onDestroy()
//        ActivityCounter.activityStopped()  // ← на всякий случай
//    }
}
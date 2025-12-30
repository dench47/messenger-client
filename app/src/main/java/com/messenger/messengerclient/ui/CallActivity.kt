package com.messenger.messengerclient.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.*
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.messenger.messengerclient.MainActivity
import com.messenger.messengerclient.R
import com.messenger.messengerclient.utils.PrefsManager
import com.messenger.messengerclient.webrtc.CallSignalManager
import com.messenger.messengerclient.webrtc.WebRTCManager
import com.messenger.messengerclient.websocket.WebSocketService
import realtimekit.org.webrtc.*
import java.util.concurrent.Executors

class CallActivity : AppCompatActivity() {

    private lateinit var tvCallStatus: TextView
    private lateinit var tvCallType: TextView
    private lateinit var btnAccept: Button
    private lateinit var btnDecline: Button
    private lateinit var btnEndCall: Button
    private lateinit var btnToggleMute: Button
    private lateinit var btnToggleSpeaker: Button

    private lateinit var prefsManager: PrefsManager
    private var webRTCManager: WebRTCManager? = null
    private lateinit var callSignalManager: CallSignalManager
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var callType: String
    private lateinit var targetUsername: String
    private var isIncomingCall: Boolean = false
    private var isCallActive: Boolean = false
    private var isInitialized = false
    private var isRinging = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var windowFlagsAdded = false
    private var isFinishingCall = false

    private var ringtonePlayer: MediaPlayer? = null
    private var vibrationHandler: Handler? = null
    private var vibrator: Vibrator? = null
    private var ringtone: Ringtone? = null
    private var toneGenerator: ToneGenerator? = null
    private var pendingOffer: SessionDescription? = null

    private var fixAudioHandler: Handler? = null


    companion object {
        private const val TAG = "CallActivity"
        private const val PERMISSION_REQUEST_CODE = 100

        const val EXTRA_CALL_TYPE = "call_type"
        const val EXTRA_TARGET_USER = "target_user"
        const val EXTRA_IS_INCOMING = "is_incoming"
        const val EXTRA_OFFER_SDP = "offer_sdp"
        const val EXTRA_SDP_TYPE = "sdp_type"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "🚀 onCreate() called, savedInstanceState: ${savedInstanceState != null}")

        if (savedInstanceState != null) {
            Log.d(TAG, "🔄 Restoring from saved state")
            isInitialized = savedInstanceState.getBoolean("isInitialized", false)
            isCallActive = savedInstanceState.getBoolean("isCallActive", false)
            targetUsername = savedInstanceState.getString("targetUsername") ?: ""
            isIncomingCall = savedInstanceState.getBoolean("isIncomingCall", false)
            callType = savedInstanceState.getString("callType") ?: "audio"
        }

        try {
            // Держим экран включенным во время звонка
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
            windowFlagsAdded = true

            // Получаем WakeLock
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "Messenger:CallWakeLock"
            )

            setContentView(R.layout.activity_call_simple)

            initViews()
            getIntentData()
            initManagers()
            setupAudio()
            setupUI()

            if (!checkPermissions()) {
                requestPermissions()
            } else {
                initializeCall()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in onCreate: ${e.message}", e)
            Toast.makeText(this, "Ошибка инициализации звонка", Toast.LENGTH_SHORT).show()
            finishCall()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "🔄 onResume() called")

        // При возвращении на экран включаем WakeLock
        if (wakeLock?.isHeld == false) {
            try {
                wakeLock?.acquire(10 * 60 * 1000L) // 10 минут
                Log.d(TAG, "🔋 WakeLock acquired")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to acquire WakeLock", e)
            }
        }

        // Убедимся что флаги установлены
        if (!windowFlagsAdded) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
            windowFlagsAdded = true
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "⏸️ onPause() called")
        // НЕ освобождаем WakeLock при паузе
    }

    private fun initViews() {
        tvCallStatus = findViewById(R.id.tv_call_status)
        tvCallType = findViewById(R.id.tv_call_type)
        btnAccept = findViewById(R.id.btn_accept)
        btnDecline = findViewById(R.id.btn_decline)
        btnEndCall = findViewById(R.id.btn_end_call)
        btnToggleMute = findViewById(R.id.btn_toggle_mute)
        btnToggleSpeaker = findViewById(R.id.btn_toggle_speaker)
    }

    private fun getIntentData() {
        callType = intent.getStringExtra(EXTRA_CALL_TYPE) ?: "audio"
        targetUsername = intent.getStringExtra(EXTRA_TARGET_USER) ?: ""
        isIncomingCall = intent.getBooleanExtra(EXTRA_IS_INCOMING, false)

        if (targetUsername.isEmpty()) {
            Toast.makeText(this, "Ошибка: не указан пользователь", Toast.LENGTH_SHORT).show()
            finishCall()
        }
    }

    private fun initManagers() {
        prefsManager = PrefsManager(this)
        callSignalManager = CallSignalManager(prefsManager, WebSocketService.getInstance())
    }

    private fun setupAudio() {
        volumeControlStream = AudioManager.STREAM_VOICE_CALL
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false
        vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
    }

    private fun initializeCall() {
        Log.d(TAG, "🚀 Initializing call to $targetUsername, incoming: $isIncomingCall")

        executor.execute {
            try {
                val manager = WebRTCManager(this@CallActivity)
                manager.initialize()

                runOnUiThread {
                    webRTCManager = manager
                    setupWebRTCCallbacks()

                    if (isIncomingCall) {
                        setupIncomingCall()
                    } else {
                        startOutgoingCall()
                    }
                    isInitialized = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error initializing WebRTC", e)
                runOnUiThread {
                    Toast.makeText(this@CallActivity, "Ошибка инициализации WebRTC", Toast.LENGTH_SHORT).show()
                    finishCall()
                }
            }
        }
    }

    private fun setupWebRTCCallbacks() {
        Log.d(TAG, "🔄 Setting up WebRTC callbacks")

        webRTCManager?.onIceCandidate = { candidate ->
            Log.d(TAG, "❄️ Sending ICE candidate to $targetUsername")
            callSignalManager.sendIceCandidate(targetUsername, candidate)
        }

        webRTCManager?.onOfferCreated = { offer ->
            Log.d(TAG, "📤 Sending OFFER to $targetUsername")
            callSignalManager.sendOffer(targetUsername, offer)
        }

        webRTCManager?.onAnswerCreated = { answer ->
            Log.d(TAG, "📤 Sending ANSWER to $targetUsername")
            callSignalManager.sendAnswer(targetUsername, answer)
        }

        webRTCManager?.onConnectionStateChanged = { state ->
            Log.d(TAG, "🔗 Connection state: $state")
            runOnUiThread {
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        updateCallStatus("Соединение установлено")
                        isCallActive = true
                        stopRinging() // ← ОСТАНАВЛИВАЕМ ЗВУКИ при установке соединения!
                        Toast.makeText(this@CallActivity, "Соединение установлено", Toast.LENGTH_SHORT).show()
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED -> {
                        updateCallStatus("Соединение разорвано")
                        Toast.makeText(this@CallActivity, "Соединение разорвано", Toast.LENGTH_SHORT).show()
                        endCall()
                    }
                    PeerConnection.PeerConnectionState.FAILED -> {
                        updateCallStatus("Ошибка соединения")
                        Toast.makeText(this@CallActivity, "Ошибка соединения", Toast.LENGTH_SHORT).show()
                        endCall()
                    }
                    else -> {
                        Log.d(TAG, "🔗 Other state: $state")
                    }
                }
            }
        }

        webRTCManager?.onLocalDescriptionSet = {
            Log.d(TAG, "✅ Local description set")
        }

        webRTCManager?.onRemoteDescriptionSet = {
            Log.d(TAG, "✅ Remote description set")
        }

        WebSocketService.setCallSignalListenerForCallActivity { signal ->
            Log.d(TAG, "📞 [CallActivity] Received call signal via WebSocket: ${signal["type"]}")
            val type = signal["type"] as? String
            Log.d(TAG, "📞 Signal type: $type, from: ${signal["from"]}, to: ${signal["to"]}")

            executor.execute {
                processIncomingCallSignal(signal)
            }
        }

        Log.d(TAG, "✅ CallSignalListener установлен через setCallSignalListenerForCallActivity")
    }

    private fun processIncomingCallSignal(signal: Map<String, Any>) {
        try {
            val type = signal["type"] as? String ?: return
            val from = signal["from"] as? String ?: ""
            val to = signal["to"] as? String ?: ""

            if (from != targetUsername) {
                Log.w(TAG, "⚠️ Call signal from wrong user: $from, expected: $targetUsername")
                return
            }

            if (isFinishingCall) {
                Log.w(TAG, "⚠️ Skipping call signal processing - call is finishing")
                return
            }

            Log.d(TAG, "📥 Processing call signal: type=$type, from=$from, to=$to")

            when (type) {
                "offer" -> {
                    val sdp = signal["sdp"] as? String
                    val sdpType = signal["sdpType"] as? String

                    if (sdp != null) {
                        Log.d(TAG, "📥 Received OFFER via WebSocket from $from")
                        Log.d(TAG, "📥 SDP type: $sdpType, SDP length: ${sdp.length}")

                        pendingOffer = SessionDescription(SessionDescription.Type.OFFER, sdp)
                        Log.d(TAG, "💾 OFFER saved to pendingOffer. Waiting for user to accept...")

                        runOnUiThread {
                            updateCallStatus("Входящий звонок от $from")
                            btnAccept.visibility = android.view.View.VISIBLE
                            btnDecline.visibility = android.view.View.VISIBLE
                            btnEndCall.visibility = android.view.View.GONE

                            Toast.makeText(this@CallActivity, "Входящий звонок от $from", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                "answer" -> {
                    val sdp = signal["sdp"] as? String
                    if (sdp != null) {
                        Log.d(TAG, "📥 Received ANSWER from $from - остановка гудков")
                        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)

                        runOnUiThread {
                            stopRinging() // ← КРИТИЧЕСКО: останавливаем гудки при получении ANSWER
                        }

                        executor.execute {
                            webRTCManager?.setRemoteDescription(answer)
                        }
                    }
                }

                "ice-candidate" -> {
                    val candidate = signal["candidate"] as? String
                    val sdpMid = signal["sdpMid"] as? String
                    val sdpMLineIndex = (signal["sdpMLineIndex"] as? Double)?.toInt()

                    if (candidate != null && sdpMid != null && sdpMLineIndex != null) {
                        Log.d(TAG, "📥 Received ICE candidate from $from: $candidate")
                        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)

                        executor.execute {
                            webRTCManager?.addIceCandidate(iceCandidate)
                        }
                    }
                }

                "reject" -> {
                    Log.d(TAG, "📥 Received REJECT call from $from")
                    runOnUiThread {
                        stopRinging()
                        Toast.makeText(this, "Абонент отклонил звонок", Toast.LENGTH_SHORT).show()
                        Handler(Looper.getMainLooper()).postDelayed({
                            finish()
                        }, 1500)
                    }
                }

                "end" -> {
                    Log.d(TAG, "📥 Received END call from $from - call finished")
                    runOnUiThread {
                        stopRinging()
                        Toast.makeText(this, "Звонок завершен", Toast.LENGTH_SHORT).show()
                        finishCallAndReturn()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error processing call signal", e)
        }
    }

    private fun finishCallAndReturnToPrevious() {
        stopRinging()
        Log.d(TAG, "📞 Finishing call and returning to previous activity")

        if (isFinishingCall) return
        isFinishingCall = true

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d(TAG, "🔋 WakeLock released")
        }

        if (windowFlagsAdded) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
            windowFlagsAdded = false
        }

        WebSocketService.clearCallSignalListenerForCallActivity()
        webRTCManager?.cleanup()

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                val callingActivity = intent.getStringExtra("calling_activity")
                if (callingActivity == "ChatActivity") {
                    val chatIntent = Intent(this, ChatActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("RECEIVER_USERNAME", targetUsername)
                    }
                    startActivity(chatIntent)
                } else {
                    val mainIntent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(mainIntent)
                }
                finish()
            }
        }, 500)
    }

    private fun setupUI() {
        tvCallStatus.text = if (isIncomingCall) {
            "Входящий звонок от $targetUsername"
        } else {
            "Звонок $targetUsername..."
        }

        tvCallType.text = if (callType == "video") "Видеозвонок" else "Аудиозвонок"

        if (isIncomingCall) {
            setupUIForIncomingCall()
        } else {
            setupUIForOutgoingCall()
        }

        btnToggleMute.setOnClickListener { toggleMute() }
        btnToggleSpeaker.setOnClickListener { toggleSpeaker() }
    }

    private fun finishCallAndReturn() {
        Log.d(TAG, "📞 Finishing call and returning")

        if (isFinishingCall) return
        isFinishingCall = true

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        if (windowFlagsAdded) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
            windowFlagsAdded = false
        }

        WebSocketService.clearCallSignalListenerForCallActivity()
        webRTCManager?.cleanup()

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                finish()
            }
        }, 500)
    }

    private fun setupIncomingCall() {
        Log.d(TAG, "📞 setupIncomingCall() - ожидаем SDP через WebSocket")
        // Для принимающего: мелодия через громкую связь
        startRinging(false)

        val offerSdp = intent.getStringExtra(EXTRA_OFFER_SDP)
        if (!offerSdp.isNullOrEmpty()) {
            Log.d(TAG, "📞 Processing SDP from Intent")
            processIncomingOffer(offerSdp)
        } else {
            Log.d(TAG, "📞 No SDP in Intent, waiting for WebSocket offer...")
            updateCallStatus("Ожидание данных звонка...")
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isInitialized && !isFinishingCall) {
                    Log.w(TAG, "⚠️ SDP not received via WebSocket within timeout")
                    runOnUiThread {
                        Toast.makeText(this, "Данные звонка не получены. Попробуйте позже.", Toast.LENGTH_SHORT).show()
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (!isInitialized) {
                                finishCall()
                            }
                        }, 2000)
                    }
                }
            }, 5000)
        }
    }

    // ================= ЗВУК И ВИБРАЦИЯ =================
    private fun startRinging(isDialTone: Boolean) {
        stopRinging()
        isRinging = true

        try {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

            if (isDialTone) {
                // ГУДКИ для звонящего - через динамик уха (STREAM_VOICE_CALL)
                Log.d(TAG, "📞 Запуск ПРЕРЫВИСТЫХ ГУДКОВ для звонящего")
                audioManager.isSpeakerphoneOn = false
                audioManager.mode = AudioManager.MODE_IN_CALL
                startDialTone()
            } else {
                // МЕЛОДИЯ для принимающего - через громкую связь
                Log.d(TAG, "📞 Запуск МЕЛОДИИ для принимающего через ГРОМКУЮ СВЯЗЬ")
                audioManager.isSpeakerphoneOn = true
                audioManager.mode = AudioManager.MODE_RINGTONE
                startRingtone()
                startVibration()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка воспроизведения звука", e)
        }
    }

    private fun startDialTone() {
        try {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

            Log.d(TAG, "🔊 Запуск гудков через MediaPlayer")

            // 1. Останавливаем предыдущий звук если есть
            stopDialToneOnly()

            // 2. Создаем MediaPlayer
            ringtonePlayer = MediaPlayer().apply {
                try {
                    // Загружаем файл гудка из raw ресурсов
                    val afd = resources.openRawResourceFd(R.raw.dial_tone)
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()

                    // Настраиваем для голосового звонка (ушной динамик)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )

                    // Тихая громкость для ушного динамика
                    setVolume(0.3f, 0.3f)

                    // Не зацикливаем - будем управлять вручную
                    isLooping = false

                    // Подготавливаем
                    prepare()

                    Log.d(TAG, "✅ MediaPlayer подготовлен")

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Ошибка инициализации MediaPlayer", e)
                    release()
                    null
                }
            }

            // 3. Если MediaPlayer создан успешно
            ringtonePlayer?.let { player ->
                vibrationHandler = Handler(Looper.getMainLooper())

                val runnable = object : Runnable {
                    override fun run() {
                        if (isRinging && !isFinishingCall) {
                            try {
                                // Запускаем гудок
                                if (!player.isPlaying) {
                                    player.start()
                                    Log.d(TAG, "🔊 Гудок запущен")
                                }

                                // Останавливаем через 1 секунду
                                vibrationHandler?.postDelayed({
                                    if (player.isPlaying) {
                                        player.pause()
                                        player.seekTo(0) // перематываем в начало
                                    }

                                    // Пауза 1 секунда и повтор
                                    vibrationHandler?.postDelayed({
                                        if (isRinging && !isFinishingCall) {
                                            run()
                                        }
                                    }, 1000)
                                }, 1000)

                            } catch (e: Exception) {
                                Log.e(TAG, "❌ Ошибка воспроизведения гудка", e)
                            }
                        }
                    }
                }

                // Запускаем первый гудок
                vibrationHandler?.post(runnable)
                Log.d(TAG, "🔊 Гудки запущены через MediaPlayer")

            } ?: run {
                // Fallback на ToneGenerator если MediaPlayer не создан
                Log.w(TAG, "⚠️ MediaPlayer не создан, используем ToneGenerator")
                startToneGeneratorFallback()
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка создания гудков", e)
            startToneGeneratorFallback()
        }
    }

    private fun startToneGeneratorFallback() {
        try {
            Log.d(TAG, "🔊 Используем ToneGenerator как запасной вариант")

            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

            // Пробуем разные stream типы
            val streamType = if (audioManager.isSpeakerphoneOn) {
                AudioManager.STREAM_MUSIC
            } else {
                AudioManager.STREAM_VOICE_CALL
            }

            toneGenerator = ToneGenerator(streamType, 25) // очень тихо

            vibrationHandler = Handler(Looper.getMainLooper())
            val runnable = object : Runnable {
                override fun run() {
                    if (isRinging && toneGenerator != null && !isFinishingCall) {
                        // Короткий гудок
                        toneGenerator?.startTone(ToneGenerator.TONE_DTMF_0, 800)

                        vibrationHandler?.postDelayed({
                            if (isRinging && !isFinishingCall) {
                                run()
                            }
                        }, 2000)
                    }
                }
            }
            vibrationHandler?.post(runnable)

        } catch (e: Exception) {
            Log.e(TAG, "❌ ToneGenerator тоже не работает", e)
        }
    }

    private fun stopDialToneOnly() {
        try {
            // Останавливаем MediaPlayer
            ringtonePlayer?.stop()
            ringtonePlayer?.release()
            ringtonePlayer = null

            // Останавливаем ToneGenerator
            toneGenerator?.stopTone()
            toneGenerator?.release()
            toneGenerator = null

            // Останавливаем handler
            vibrationHandler?.removeCallbacksAndMessages(null)
            vibrationHandler = null

            Log.d(TAG, "🔇 Гудки остановлены")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка остановки гудков", e)
        }
    }

    private fun stopRinging() {
        isRinging = false

        if (isIncomingCall) {
            // Для принимающего: останавливаем все
            stopIncomingRinging()
        } else {
            // Для звонящего: останавливаем только гудки
            stopDialToneOnly()
        }
    }

    private fun stopIncomingRinging() {
        isRinging = false

        try {
            Log.d(TAG, "🛑 Останавливаем ВСЕ звуки и вибрацию")

            // 1. Останавливаем гудки (для звонящего)
            toneGenerator?.stopTone()
            toneGenerator?.release()
            toneGenerator = null

            // 2. Останавливаем MediaPlayer гудки
            ringtonePlayer?.stop()
            ringtonePlayer?.release()
            ringtonePlayer = null

            // 3. Останавливаем мелодию звонка (для принимающего)
            ringtone?.stop()
            ringtone = null

            // 4. Останавливаем вибрацию (ОБЯЗАТЕЛЬНО для принимающего!)
            vibrator?.cancel() // ← ЭТО КРИТИЧЕСКИ ВАЖНО!

            // 5. Останавливаем все handler
            vibrationHandler?.removeCallbacksAndMessages(null)
            vibrationHandler = null

            fixAudioHandler?.removeCallbacksAndMessages(null)
            fixAudioHandler = null

            // 6. Восстанавливаем нормальный аудио режим
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false

            Log.d(TAG, "🔇 ВСЕ звуки и вибрация остановлены")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка остановки звуков/вибрации", e)
        }
    }

    private fun startRingtone() {
        try {
            ringtone = RingtoneManager.getRingtone(
                applicationContext,
                android.provider.Settings.System.DEFAULT_RINGTONE_URI
            )
            ringtone?.apply {
                streamType = AudioManager.STREAM_RING
                play()
            }
            Log.d(TAG, "🎵 Мелодия звонка запущена (громкая связь)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка воспроизведения мелодии", e)
        }
    }

    private fun startVibration() {
        try {
            if (vibrator?.hasVibrator() == true) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.VIBRATE) == PackageManager.PERMISSION_GRANTED) {
                    val vibratePattern = longArrayOf(0, 1000, 1000)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val vibrationEffect = VibrationEffect.createWaveform(vibratePattern, 0)
                        vibrator?.vibrate(vibrationEffect)
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(vibratePattern, 0)
                    }
                    Log.d(TAG, "📳 Вибрация запущена")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка вибрации", e)
        }
    }

    private fun processIncomingOffer(offerSdp: String) {
        try {
            val offer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
            webRTCManager?.setRemoteDescription(offer)
            updateCallStatus("Обработка входящего звонка...")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error processing incoming offer", e)
            Toast.makeText(this, "Ошибка обработки звонка", Toast.LENGTH_SHORT).show()
            finishCall()
        }
    }

    private fun startOutgoingCall() {
        webRTCManager?.startCall()
        updateCallStatus("Установка соединения...")
        // Гудки для звонящего
        startRinging(true)
    }

    private fun acceptCall() {
        if (!isInitialized || webRTCManager == null) {
            Toast.makeText(this, "Звонок ещё не инициализирован", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "✅ Call accepted")
        stopRinging()

        webRTCManager?.acceptCall()

        pendingOffer?.let { offer ->
            Log.d(TAG, "🎯 Setting remote description from saved OFFER (user accepted)")
            webRTCManager?.setRemoteDescription(offer)
            pendingOffer = null
        } ?: run {
            Log.w(TAG, "⚠️ No pending offer found when accepting call")
            Toast.makeText(this, "Ошибка: данные звонка не найдены", Toast.LENGTH_SHORT).show()
            return
        }

        btnAccept.visibility = android.view.View.GONE
        btnDecline.visibility = android.view.View.GONE
        btnEndCall.visibility = android.view.View.VISIBLE
        updateCallStatus("Принятие звонка...")

        btnEndCall.setOnClickListener {
            Log.d(TAG, "📞 [INCOMING] ЗАВЕРШЕНИЕ активного входящего звонка")
            endCall()
        }
    }

    private fun setupUIForIncomingCall() {
        Log.d(TAG, "📱 Настройка UI для ВХОДЯЩЕГО звонка")

        btnAccept.visibility = android.view.View.VISIBLE
        btnDecline.visibility = android.view.View.VISIBLE
        btnEndCall.visibility = android.view.View.GONE

        btnAccept.text = "Принять"
        btnDecline.text = "Отклонить"

        btnAccept.setOnClickListener { acceptCall() }
        btnDecline.setOnClickListener {
            Log.d(TAG, "❌❌❌ ОТКЛОНЕНИЕ входящего звонка")
            rejectIncomingCall()
        }
        btnEndCall.setOnClickListener(null)
    }

    private fun setupUIForOutgoingCall() {
        Log.d(TAG, "📱 Настройка UI для ИСХОДЯЩЕГО звонка")

        btnAccept.visibility = android.view.View.GONE
        btnDecline.visibility = android.view.View.GONE
        btnEndCall.visibility = android.view.View.VISIBLE

        btnEndCall.text = "Завершить"

        btnEndCall.setOnClickListener {
            Log.d(TAG, "📞 ЗАВЕРШЕНИЕ активного звонка")
            endCall()
        }

        btnAccept.setOnClickListener(null)
        btnDecline.setOnClickListener(null)
    }

    private fun rejectIncomingCall() {
        stopRinging()
        if (isIncomingCall && targetUsername.isNotEmpty()) {
            callSignalManager.sendCallReject(targetUsername)
            Log.d(TAG, "📤 Отправлен REJECT для $targetUsername")
        }
        finish()
    }

    private fun endCall() {
        stopRinging()
        if (targetUsername.isNotEmpty()) {
            callSignalManager.sendCallEnd(targetUsername)
            Log.d(TAG, "📤 Отправлен END для $targetUsername (call active: $isCallActive)")
        }
        finishCallAndReturnToPrevious()
    }

    private fun toggleMute() {
        val isMuted = btnToggleMute.isSelected
        btnToggleMute.isSelected = !isMuted
        btnToggleMute.text = if (!isMuted) "Включить звук" else "Выключить звук"
        Toast.makeText(this, if (!isMuted) "Звук выключен" else "Звук включен", Toast.LENGTH_SHORT).show()
    }

    private fun toggleSpeaker() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val isSpeaker = audioManager.isSpeakerphoneOn
        audioManager.isSpeakerphoneOn = !isSpeaker
        audioManager.mode = if (!isSpeaker) AudioManager.MODE_NORMAL else AudioManager.MODE_IN_COMMUNICATION

        btnToggleSpeaker.isSelected = !isSpeaker
        btnToggleSpeaker.text = if (!isSpeaker) "Динамик" else "Наушники"
        Toast.makeText(this, if (!isSpeaker) "Включен динамик" else "Включены наушники", Toast.LENGTH_SHORT).show()
    }

    private fun updateCallStatus(status: String) {
        tvCallStatus.text = status
    }

    private fun checkPermissions(): Boolean {
        val permissions = if (callType == "video") {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissions = if (callType == "video") {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                initializeCall()
            } else {
                Toast.makeText(this, "Разрешения необходимы для звонков", Toast.LENGTH_SHORT).show()
                finishCall()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Log.d(TAG, "💾 onSaveInstanceState() called")
        outState.putBoolean("isInitialized", isInitialized)
        outState.putBoolean("isCallActive", isCallActive)
        outState.putString("targetUsername", targetUsername)
        outState.putBoolean("isIncomingCall", isIncomingCall)
        outState.putString("callType", callType)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        Log.d(TAG, "🔄 onRestoreInstanceState() called")
        isInitialized = savedInstanceState.getBoolean("isInitialized", false)
        isCallActive = savedInstanceState.getBoolean("isCallActive", false)
        targetUsername = savedInstanceState.getString("targetUsername") ?: ""
        isIncomingCall = savedInstanceState.getBoolean("isIncomingCall", false)
        callType = savedInstanceState.getString("callType") ?: "audio"
        if (isInitialized && targetUsername.isNotEmpty()) {
            setupUI()
        }
    }

    private fun finishCall() {
        finishCallAndReturnToPrevious()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "💀 onDestroy() called")
        stopRinging()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        WebSocketService.clearCallSignalListenerForCallActivity()
        webRTCManager?.cleanup()
        executor.shutdown()
    }
}
package com.messenger.messengerclient.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
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

    private var wakeLock: PowerManager.WakeLock? = null
    private var windowFlagsAdded = false
    private var isFinishingCall = false

    // НОВОЕ: сохраняем полученный OFFER до нажатия "Принять"
    private var pendingOffer: SessionDescription? = null

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

        // НЕ освобождаем WakeLock при паузе - звонок все еще активен
        // Освободим только когда звонок закончен
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
    }

    private fun initializeCall() {
        Log.d(TAG, "🚀 Initializing call to $targetUsername, incoming: $isIncomingCall")

        executor.execute {
            try {
                // Создаем WebRTCManager
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

        // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: используем специальный метод для CallActivity
        WebSocketService.setCallSignalListenerForCallActivity { signal ->
            Log.d(TAG, "📞 [CallActivity] Received call signal via WebSocket: ${signal["type"]}")

            // Проверяем тип сигнала для отладки
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

            // Проверяем, что сигнал предназначен нам
            if (from != targetUsername) {
                Log.w(TAG, "⚠️ Call signal from wrong user: $from, expected: $targetUsername")
                return
            }

            // Проверяем, не завершается ли уже звонок
            if (isFinishingCall) {
                Log.w(TAG, "⚠️ Skipping call signal processing - call is finishing")
                return
            }

            Log.d(TAG, "📥 Processing call signal: type=$type, from=$from, to=$to")

            when (type) {
                "offer" -> {
                    // ВАЖНО: Сохраняем OFFER, но НЕ устанавливаем его сразу!
                    val sdp = signal["sdp"] as? String
                    val sdpType = signal["sdpType"] as? String

                    if (sdp != null) {
                        Log.d(TAG, "📥 Received OFFER via WebSocket from $from")
                        Log.d(TAG, "📥 SDP type: $sdpType, SDP length: ${sdp.length}")

                        // СОХРАНЯЕМ OFFER, но НЕ устанавливаем remote description!
                        pendingOffer = SessionDescription(SessionDescription.Type.OFFER, sdp)
                        Log.d(TAG, "💾 OFFER saved to pendingOffer. Waiting for user to accept...")

                        runOnUiThread {
                            updateCallStatus("Входящий звонок от $from")
                            // Активируем кнопки принятия/отклонения
                            btnAccept.visibility = android.view.View.VISIBLE
                            btnDecline.visibility = android.view.View.VISIBLE
                            btnEndCall.visibility = android.view.View.GONE

                            Toast.makeText(
                                this@CallActivity,
                                "Входящий звонок от $from",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        Log.e(TAG, "❌ Offer received but SDP is null or empty")
                        runOnUiThread {
                            Toast.makeText(
                                this@CallActivity,
                                "Ошибка: нет данных звонка",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }

                "answer" -> {
                    val sdp = signal["sdp"] as? String
                    if (sdp != null) {
                        Log.d(TAG, "📥 Received ANSWER from $from")
                        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)

                        executor.execute {
                            webRTCManager?.setRemoteDescription(answer)
                        }
                    } else {
                        Log.e(TAG, "❌ Answer received but SDP is null")
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
                    } else {
                        Log.e(TAG, "❌ ICE candidate missing required fields")
                    }
                }

                "reject" -> {
                    Log.d(TAG, "📥 Received REJECT call from $from - user declined before answering")
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Абонент отклонил звонок",
                            Toast.LENGTH_SHORT
                        ).show()

                        // Просто закрываем Activity
                        Handler(Looper.getMainLooper()).postDelayed({
                            finish()
                        }, 1500)
                    }
                }

                "end" -> {
                    Log.d(TAG, "📥 Received END call from $from - call finished")
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Звонок завершен",
                            Toast.LENGTH_SHORT
                        ).show()

                        // Можно показать статистику звонка и т.д.
                        finishCallAndReturn()
                    }
                }

                else -> {
                    Log.w(TAG, "⚠️ Unknown call signal type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error processing call signal", e)
            // НЕ завершаем звонок при каждой ошибке!
            runOnUiThread {
                Toast.makeText(this, "Ошибка обработки сигнала", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun finishCallAndReturnToPrevious() {
        Log.d(TAG, "📞 Finishing call and returning to previous activity")

        // 1. Очищаем все ресурсы
        if (isFinishingCall) return
        isFinishingCall = true

        // 2. Освобождаем WakeLock
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d(TAG, "🔋 WakeLock released")
        }

        // 3. Убираем флаги окна
        if (windowFlagsAdded) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
            windowFlagsAdded = false
        }

        // 4. Очищаем WebSocket listener
        WebSocketService.clearCallSignalListenerForCallActivity()

        // 5. Очищаем WebRTC
        webRTCManager?.cleanup()

        // 6. Возвращаемся в предыдущую Activity
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                // Проверяем, откуда мы пришли
                val callingActivity = intent.getStringExtra("calling_activity")

                if (callingActivity == "ChatActivity") {
                    // Возвращаемся в ChatActivity
                    val chatIntent = Intent(this, ChatActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        // Можно передать username собеседника
                        putExtra("RECEIVER_USERNAME", targetUsername)
                    }
                    startActivity(chatIntent)
                } else {
                    // По умолчанию возвращаемся в MainActivity
                    val mainIntent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(mainIntent)
                }

                // Завершаем CallActivity
                finish()
            }
        }, 500)
    }

    private fun setupUI() {
        tvCallStatus = findViewById(R.id.tv_call_status)
        tvCallType = findViewById(R.id.tv_call_type)
        btnAccept = findViewById(R.id.btn_accept)
        btnDecline = findViewById(R.id.btn_decline)
        btnEndCall = findViewById(R.id.btn_end_call)
        btnToggleMute = findViewById(R.id.btn_toggle_mute)
        btnToggleSpeaker = findViewById(R.id.btn_toggle_speaker)

        tvCallStatus.text = if (isIncomingCall) {
            "Входящий звонок от $targetUsername"
        } else {
            "Звонок $targetUsername..."
        }

        tvCallType.text = if (callType == "video") "Видеозвонок" else "Аудиозвонок"

        // РАЗДЕЛЬНАЯ ЛОГИКА ДЛЯ ВХОДЯЩЕГО/ИСХОДЯЩЕГО ЗВОНКА
        if (isIncomingCall) {
            setupUIForIncomingCall()
        } else {
            setupUIForOutgoingCall()
        }

        // ОБЩИЕ КНОПКИ ДЛЯ ВСЕХ СЦЕНАРИЕВ
        btnToggleMute.setOnClickListener { toggleMute() }
        btnToggleSpeaker.setOnClickListener { toggleSpeaker() }
    }

    private fun finishCallAndReturn() {
        Log.d(TAG, "📞 Finishing call and returning to previous activity")

        if (isFinishingCall) return
        isFinishingCall = true

        // 1. Освобождаем ресурсы
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d(TAG, "🔋 WakeLock released")
        }

        // 2. Убираем флаги окна
        if (windowFlagsAdded) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
            windowFlagsAdded = false
        }

        // 3. Очищаем WebSocket listener
        WebSocketService.clearCallSignalListenerForCallActivity()

        // 4. Очищаем WebRTC
        webRTCManager?.cleanup()

        // 5. Закрываем Activity через 500мс
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                finish()
            }
        }, 500)
    }


    private fun setupIncomingCall() {
        Log.d(TAG, "📞 setupIncomingCall() - ожидаем SDP через WebSocket")

        val offerSdp = intent.getStringExtra(EXTRA_OFFER_SDP)

        if (!offerSdp.isNullOrEmpty()) {
            // Если SDP уже есть в Intent (старая логика или тестирование)
            Log.d(TAG, "📞 Processing SDP from Intent (length: ${offerSdp.length})")
            processIncomingOffer(offerSdp)
        } else {
            // НОВАЯ ЛОГИКА: SDP придет через WebSocket
            Log.d(TAG, "📞 No SDP in Intent, waiting for WebSocket offer...")
            updateCallStatus("Ожидание данных звонка...")

            // Можно добавить таймаут на случай если SDP не придет
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isInitialized && !isFinishingCall) {
                    Log.w(TAG, "⚠️ SDP not received via WebSocket within timeout")
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Данные звонка не получены. Попробуйте позже.",
                            Toast.LENGTH_SHORT
                        ).show()
                        // Только через 5 секунд закрываем если нет данных
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (!isInitialized) {
                                finishCall()
                            }
                        }, 2000)
                    }
                }
            }, 5000) // 5 секунд таймаут
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
    }

    private fun acceptCall() {
        if (!isInitialized || webRTCManager == null) {
            Toast.makeText(this, "Звонок ещё не инициализирован", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "✅ Call accepted")

        // 1. Сначала создаем PeerConnection
        webRTCManager?.acceptCall()

        // 2. Если есть сохраненный OFFER, устанавливаем его ТОЛЬКО СЕЙЧАС
        pendingOffer?.let { offer ->
            Log.d(TAG, "🎯 Setting remote description from saved OFFER (user accepted)")
            webRTCManager?.setRemoteDescription(offer)
            pendingOffer = null  // очищаем после использования
        } ?: run {
            Log.w(TAG, "⚠️ No pending offer found when accepting call")
            Toast.makeText(this, "Ошибка: данные звонка не найдены", Toast.LENGTH_SHORT).show()
            return
        }

        // 3. Обновляем UI
        btnAccept.visibility = android.view.View.GONE
        btnDecline.visibility = android.view.View.GONE
        btnEndCall.visibility = android.view.View.VISIBLE
        updateCallStatus("Принятие звонка...")

        // 4. Разрешаем завершение входящего звонка
        btnEndCall.setOnClickListener {
            Log.d(TAG, "📞 [INCOMING] ЗАВЕРШЕНИЕ активного входящего звонка")
            endCall()
        }
    }

    private fun declineCall() {
        Log.d(TAG, "❌ Call declined by user")

        // 1. Очищаем сохраненный OFFER (если был)
        pendingOffer = null

        // 2. Отправляем сигнал об отказе собеседнику
        sendCallEnd()

        // 3. Завершаем звонок
        finishCall()

        // 4. Возвращаемся в предыдущую Activity
        // (обычно это MainActivity или ChatActivity)
        if (!isFinishing) {
            finish()
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

        // Кнопка завершения НЕ активна для входящего звонка
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

        // Кнопки принятия/отклонения НЕ активны для исходящего звонка
        btnAccept.setOnClickListener(null)
        btnDecline.setOnClickListener(null)
    }

    private fun rejectIncomingCall() {
        // Отправляем REJECT только если это входящий звонок
        if (isIncomingCall && targetUsername.isNotEmpty()) {
            callSignalManager.sendCallReject(targetUsername)
            Log.d(TAG, "📤 Отправлен REJECT для $targetUsername")
        }

        // Закрываем Activity
        finish()
    }

    private fun endCall() {
        // Отправляем END ВСЕГДА, независимо от статуса звонка
        if (targetUsername.isNotEmpty()) {
            callSignalManager.sendCallEnd(targetUsername)
            Log.d(TAG, "📤 Отправлен END для $targetUsername (call active: $isCallActive)")
        }
        finishCallAndReturnToPrevious()
    }
    private fun sendCallEnd() {
        if (isCallActive) {
            callSignalManager.sendCallEnd(targetUsername)
            isCallActive = false
        }
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
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA
            )
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }

        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissions = if (callType == "video") {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA
            )
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }

        ActivityCompat.requestPermissions(
            this,
            permissions,
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
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
        // Сохраняем состояние звонка
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

        if (isInitialized && !targetUsername.isEmpty()) {
            // Восстанавливаем UI
            setupUI()
        }
    }

    private fun finishCall() {
        // Просто вызываем новый метод
        finishCallAndReturnToPrevious()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "💀 onDestroy() called")

        // На всякий случай освобождаем ресурсы
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        // Очищаем listener для CallActivity
        WebSocketService.clearCallSignalListenerForCallActivity()

        webRTCManager?.cleanup()
        executor.shutdown()
    }
}
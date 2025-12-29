package com.messenger.messengerclient.webrtc

import android.content.Context
import android.util.Log
import realtimekit.org.webrtc.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class WebRTCManager(private val context: Context) {
    private val TAG = "WebRTCManager"

    // Cloudflare WebRTC использует эти классы
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private val isInitialized = AtomicBoolean(false)
    private val initializationLock = Object()

    // Обратные вызовы
    var onIceCandidate: ((IceCandidate) -> Unit)? = null
    var onConnectionStateChanged: ((PeerConnection.PeerConnectionState) -> Unit)? = null
    var onOfferCreated: ((SessionDescription) -> Unit)? = null
    var onAnswerCreated: ((SessionDescription) -> Unit)? = null
    var onLocalDescriptionSet: (() -> Unit)? = null
    var onRemoteDescriptionSet: (() -> Unit)? = null
    var onTrack: ((MediaStreamTrack, Array<MediaStream>?) -> Unit)? = null

    private val executor = Executors.newSingleThreadExecutor()

    fun initialize() {
        Log.d(TAG, "🚀 Initializing Cloudflare WebRTC...")

        executor.execute {
            synchronized(initializationLock) {
                if (isInitialized.get()) {
                    Log.d(TAG, "⚠️ Already initialized")
                    return@execute
                }

                try {
                    // Инициализация PeerConnectionFactory
                    Log.d(TAG, "🔧 Creating InitializationOptions...")
                    val options = PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(true)
                        .createInitializationOptions()

                    Log.d(TAG, "🔧 Calling PeerConnectionFactory.initialize...")
                    PeerConnectionFactory.initialize(options)

                    Log.d(TAG, "🔧 Creating PeerConnectionFactory...")
                    peerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()

                    if (peerConnectionFactory == null) {
                        Log.e(TAG, "❌ PeerConnectionFactory is null after creation!")
                    } else {
                        Log.d(TAG, "✅ PeerConnectionFactory created successfully")
                    }

                    isInitialized.set(true)
                    initializationLock.notifyAll()
                    Log.d(TAG, "✅ WebRTC fully initialized")

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error initializing WebRTC", e)
                    // Логируем полный стектрейс
                    e.printStackTrace()
                }
            }
        }
    }
    private fun waitForInitialization(): Boolean {
        synchronized(initializationLock) {
            if (!isInitialized.get()) {
                Log.d(TAG, "⏳ Waiting for initialization...")
                // Ждем максимум 3 секунды
                var waited = 0
                while (!isInitialized.get() && waited < 3000) {
                    try {
                        initializationLock.wait(100)
                        waited += 100
                    } catch (e: InterruptedException) {
                        break
                    }
                }

                if (!isInitialized.get()) {
                    Log.e(TAG, "❌ Timeout waiting for WebRTC initialization")
                    return false
                }
            }
            return true
        }
    }

    fun createPeerConnection(): PeerConnection? {
        if (!waitForInitialization()) {
            Log.e(TAG, "❌ Cannot create peer connection - not initialized")
            return null
        }

        return try {
            // Получаем ICE серверы
            val iceServers = getIceServers()

            // Создаем конфигурацию
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers)

            // Настраиваем дополнительные параметры (упрощаем для теста)
            rtcConfig.iceTransportsType = PeerConnection.IceTransportsType.ALL
            rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

            val observer = object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                    Log.d(TAG, "📶 Signaling: $state")
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "🌐 ICE Connection: $state")

                    if (state == PeerConnection.IceConnectionState.CONNECTED ||
                        state == PeerConnection.IceConnectionState.COMPLETED) {
                        Log.d(TAG, "🔊 Audio connection established")
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    Log.d(TAG, "📡 ICE Receiving: $receiving")
                }

                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    Log.d(TAG, "⛄ ICE Gathering: $state")
                }

                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        Log.d(TAG, "❄️ ICE Candidate: ${it.sdpMid}:${it.sdpMLineIndex}")
                        executor.execute {
                            onIceCandidate?.invoke(it)
                        }
                    }
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                    Log.d(TAG, "🗑️ ICE candidates removed")
                }

                override fun onDataChannel(channel: DataChannel?) {
                    Log.d(TAG, "📨 Data Channel: ${channel?.label()}")
                }

                override fun onRenegotiationNeeded() {
                    Log.d(TAG, "🔄 Renegotiation Needed")
                }

                override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                    state?.let {
                        Log.d(TAG, "🔗 Connection State: $it")
                        executor.execute {
                            onConnectionStateChanged?.invoke(it)
                        }
                    }
                }

                override fun onTrack(transceiver: RtpTransceiver) {
                    Log.d(TAG, "🎤 onTrack called")
                    transceiver.receiver?.let { receiver ->
                        receiver.track()?.let { track ->
                            Log.d(TAG, "✅ Track obtained: ${track.id()}")
                            executor.execute {
                                onTrack?.invoke(track, emptyArray())
                            }
                        }
                    }
                }

                override fun onRemoveTrack(receiver: RtpReceiver?) {
                    Log.d(TAG, "🔇 Remove Track")
                }

                override fun onAddStream(stream: MediaStream?) {
                    Log.d(TAG, "🎥 Add Stream (legacy): ${stream?.getId()}")
                }

                override fun onRemoveStream(stream: MediaStream?) {
                    Log.d(TAG, "📹 Remove Stream (legacy)")
                }
            }

            val pc = peerConnectionFactory?.createPeerConnection(rtcConfig, observer)
            Log.d(TAG, "✅ PeerConnection created: ${pc != null}")
            pc
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creating peer connection", e)
            null
        }
    }

    fun createAudioTrack(): AudioTrack? {
        if (!waitForInitialization()) {
            Log.e(TAG, "❌ Cannot create audio track - not initialized")
            return null
        }

        return try {
            // Создаем аудио источник с настройками
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            }

            val audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
            val trackId = "audio_track_${System.currentTimeMillis()}"
            val track = peerConnectionFactory?.createAudioTrack(trackId, audioSource)

            Log.d(TAG, "🎤 Audio track created: $trackId")
            track
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creating audio track", e)
            null
        }
    }

    fun addAudioTrackToConnection() {
        audioTrack?.let { track ->
            executor.execute {
                try {
                    // Современный способ добавления трека
                    peerConnection?.addTrack(track, listOf("local_audio_stream"))
                    Log.d(TAG, "✅ Audio track added to connection")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error adding audio track: ${e.message}")
                    // Пробуем старый способ как fallback
                    try {
                        val localStream = peerConnectionFactory?.createLocalMediaStream("local_audio_stream")
                        localStream?.addTrack(track)
                        peerConnection?.addStream(localStream)
                        Log.d(TAG, "✅ Audio track added via stream (fallback)")
                    } catch (e2: Exception) {
                        Log.e(TAG, "❌ Fallback also failed: ${e2.message}")
                    }
                }
            }
        } ?: run {
            Log.e(TAG, "❌ No audio track to add")
        }
    }

    fun createOffer() {
        executor.execute {
            if (peerConnection == null) {
                Log.e(TAG, "❌ Cannot create offer - no peer connection")
                return@execute
            }

            try {
                val constraints = MediaConstraints().apply {
                    optional.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    optional.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                }

                Log.d(TAG, "📤 Creating offer...")
                peerConnection?.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(description: SessionDescription?) {
                        description?.let {
                            Log.d(TAG, "✅ Offer created: ${it.type}")
                            Log.d(TAG, "SDP length: ${it.description.length} chars")
                            setLocalDescription(it)
                            executor.execute {
                                onOfferCreated?.invoke(it)
                            }
                        }
                    }

                    override fun onSetSuccess() {
                        Log.d(TAG, "🎯 SDP operation succeeded")
                    }

                    override fun onCreateFailure(error: String?) {
                        Log.e(TAG, "❌ Create offer failed: $error")
                    }

                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "❌ Set description failed: $error")
                    }
                }, constraints)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error creating offer", e)
                e.printStackTrace()
            }
        }
    }

    fun createAnswer() {
        executor.execute {
            if (peerConnection == null) {
                Log.e(TAG, "❌ Cannot create answer - no peer connection")
                return@execute
            }

            try {
                val constraints = MediaConstraints().apply {
                    optional.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    optional.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                }

                Log.d(TAG, "📥 Creating answer...")
                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(description: SessionDescription?) {
                        description?.let {
                            Log.d(TAG, "✅ Answer created: ${it.type}")
                            setLocalDescription(it)
                            executor.execute {
                                onAnswerCreated?.invoke(it)
                            }
                        }
                    }

                    override fun onSetSuccess() {
                        Log.d(TAG, "🎯 SDP operation succeeded")
                    }

                    override fun onCreateFailure(error: String?) {
                        Log.e(TAG, "❌ Create answer failed: $error")
                    }

                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "❌ Set description failed: $error")
                    }
                }, constraints)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error creating answer", e)
                e.printStackTrace()
            }
        }
    }

    fun setLocalDescription(description: SessionDescription) {
        executor.execute {
            Log.d(TAG, "📝 Setting local description: ${description.type}")
            peerConnection?.setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    // Not used here
                }

                override fun onSetSuccess() {
                    Log.d(TAG, "✅ Local description set: ${description.type}")
                    executor.execute {
                        onLocalDescriptionSet?.invoke()
                    }
                }

                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "❌ Create failed in setLocal: $error")
                }

                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "❌ Local description failed: $error")
                }
            }, description)
        }
    }

    fun setRemoteDescription(description: SessionDescription) {
        executor.execute {
            Log.d(TAG, "🎯 setRemoteDescription CALLED with type: ${description.type}")

            // Если PeerConnection еще не создан, создаем его
            if (peerConnection == null) {
                Log.d(TAG, "⚠️ PeerConnection not created yet, creating...")
                peerConnection = createPeerConnection()

                if (peerConnection == null) {
                    Log.e(TAG, "❌ Failed to create PeerConnection")
                    return@execute
                }

                // Создаем и добавляем аудио трек
                audioTrack = createAudioTrack()
                if (audioTrack != null) {
                    addAudioTrackToConnection()
                }

                Log.d(TAG, "✅ PeerConnection created for setRemoteDescription")
            }

            // Теперь устанавливаем remote description
            Log.d(TAG, "📝 Actually setting remote description: ${description.type}")
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    // Not used here
                }

                override fun onSetSuccess() {
                    Log.d(TAG, "✅✅✅ Remote description set: ${description.type}")
                    executor.execute {
                        onRemoteDescriptionSet?.invoke()
                    }

                    if (description.type == SessionDescription.Type.OFFER) {
                        Log.d(TAG, "🔄🔄🔄 Received OFFER, creating answer...")
                        createAnswer()
                    }
                }

                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "❌ Create failed in setRemote: $error")
                }

                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "❌❌❌ Remote description failed: $error")
                }
            }, description)
        }
    }

    fun addIceCandidate(candidate: IceCandidate) {
        executor.execute {
            try {
                Log.d(TAG, "➕ Adding ICE candidate: ${candidate.sdpMid}:${candidate.sdpMLineIndex}")
                peerConnection?.addIceCandidate(candidate)
                Log.d(TAG, "✅ ICE candidate added")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error adding ICE candidate", e)
            }
        }
    }

    fun startCall() {
        executor.execute {
            Log.d(TAG, "📞 Starting call...")

            // 1. Создаем PeerConnection
            peerConnection = createPeerConnection()

            if (peerConnection == null) {
                Log.e(TAG, "❌ Failed to create PeerConnection")
                return@execute
            }

            // 2. Создаем и добавляем аудио трек
            audioTrack = createAudioTrack()

            if (audioTrack == null) {
                Log.e(TAG, "❌ Failed to create audio track")
                return@execute
            }

            addAudioTrackToConnection()

            // 3. Создаем offer
            createOffer()
        }
    }

    fun acceptCall() {
        executor.execute {
            Log.d(TAG, "📞 Accepting call...")

            // 1. Если PeerConnection уже создан (в setRemoteDescription), не создаем новый
            if (peerConnection == null) {
                peerConnection = createPeerConnection()

                if (peerConnection == null) {
                    Log.e(TAG, "❌ Failed to create PeerConnection")
                    return@execute
                }

                // 2. Создаем и добавляем аудио трек
                audioTrack = createAudioTrack()

                if (audioTrack == null) {
                    Log.e(TAG, "❌ Failed to create audio track")
                    return@execute
                }

                addAudioTrackToConnection()
                Log.d(TAG, "✅ Ready to receive OFFER")
            } else {
                Log.d(TAG, "🎯 PeerConnection already exists, reusing...")
            }

            Log.d(TAG, "⏳ Waiting for remote description (offer)...")
        }
    }

    fun endCall() {
        Log.d(TAG, "📞 Ending call...")
        cleanup()
    }

    fun getIceServers(): List<PeerConnection.IceServer> {
        val iceServers = mutableListOf<PeerConnection.IceServer>()

        try {
            // STUN сервер
            iceServers.add(
                PeerConnection.IceServer.builder("stun:turn.palomica.ru:3478")
                    .createIceServer()
            )

            // TURN сервер UDP
            iceServers.add(
                PeerConnection.IceServer.builder("turn:turn.palomica.ru:3478?transport=udp")
                    .setUsername("webrtc")
                    .setPassword("password123")
                    .createIceServer()
            )

            // TURN сервер TCP
            iceServers.add(
                PeerConnection.IceServer.builder("turn:turn.palomica.ru:3478?transport=tcp")
                    .setUsername("webrtc")
                    .setPassword("password123")
                    .createIceServer()
            )

            // Прямой IP адрес
            iceServers.add(
                PeerConnection.IceServer.builder("turn:176.125.152.138:3478")
                    .setUsername("webrtc")
                    .setPassword("password123")
                    .createIceServer()
            )

            // Google STUN как запасной
            iceServers.add(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                    .createIceServer()
            )

            Log.d(TAG, "🌐 ICE Servers configured: ${iceServers.size} servers")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creating ICE servers", e)

            // Минимальные fallback серверы
            iceServers.add(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                    .createIceServer()
            )
        }

        return iceServers
    }

    fun getPeerConnection(): PeerConnection? = peerConnection

    fun cleanup() {
        executor.execute {
            try {
                Log.d(TAG, "🧹 Cleaning up WebRTC...")

                peerConnection?.close()
                peerConnection = null

                audioTrack?.dispose()
                audioTrack = null

                audioSource?.dispose()
                audioSource = null

                Log.d(TAG, "✅ WebRTC cleaned up")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error cleaning up WebRTC", e)
            }
        }
    }

    fun isInitialized(): Boolean = isInitialized.get()
}
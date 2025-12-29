package com.messenger.messengerclient.webrtc

import android.util.Log
import com.messenger.messengerclient.websocket.WebSocketService
import com.messenger.messengerclient.utils.PrefsManager
import realtimekit.org.webrtc.*

class CallSignalManager(
    private val prefsManager: PrefsManager,
    private val webSocketService: WebSocketService
) {
    private val TAG = "CallSignalManager"

    fun sendOffer(toUser: String, offer: SessionDescription) {
        val callSignal = mapOf(
            "type" to "offer",
            "from" to (prefsManager.username ?: ""),
            "to" to toUser,
            "sdp" to offer.description,
            "sdpType" to offer.type.toString()
        )

        sendCallSignal(callSignal)
        Log.d(TAG, "📤 Sent offer to $toUser")
    }

    fun sendAnswer(toUser: String, answer: SessionDescription) {
        val callSignal = mapOf(
            "type" to "answer",
            "from" to (prefsManager.username ?: ""),
            "to" to toUser,
            "sdp" to answer.description,
            "sdpType" to answer.type.toString()
        )

        sendCallSignal(callSignal)
        Log.d(TAG, "📤 Sent answer to $toUser")
    }

    fun sendIceCandidate(toUser: String, candidate: IceCandidate) {
        val callSignal = mapOf(
            "type" to "ice-candidate",
            "from" to (prefsManager.username ?: ""),
            "to" to toUser,
            "candidate" to candidate.sdp,
            "sdpMid" to candidate.sdpMid,
            "sdpMLineIndex" to candidate.sdpMLineIndex
        )

        sendCallSignal(callSignal)
        Log.d(TAG, "📤 Sent ICE candidate to $toUser")
    }

    fun sendCallEnd(toUser: String) {
        val callSignal = mapOf(
            "type" to "end",
            "from" to (prefsManager.username ?: ""),
            "to" to toUser
        )

        sendCallSignal(callSignal)
        Log.d(TAG, "📤 Sent call end to $toUser")
    }

    private fun sendCallSignal(callSignal: Map<String, Any>) {
        // Отправляем через отдельный endpoint для звонков
        val success = webSocketService.sendCallSignal(callSignal)
        if (success) {
            Log.d(TAG, "✅ Call signal sent successfully")
        } else {
            Log.e(TAG, "❌ Failed to send call signal")
        }
    }

    fun sendCallReject(toUser: String) {
        val fromUsername = prefsManager.username ?: ""

        val callSignal: Map<String, Any> = mapOf(
            "type" to "reject",  // ← НОВЫЙ ТИП
            "from" to fromUsername,
            "to" to toUser,
            "reason" to "user_rejected"  // можно добавить причину
        )

        sendCallSignal(callSignal)
        Log.d(TAG, "📤 Sent call REJECT to $toUser")
    }

    companion object {
        fun createSessionDescription(type: String, sdp: String): SessionDescription? {
            return try {
                val sdpType = when (type.uppercase()) {
                    "OFFER" -> SessionDescription.Type.OFFER
                    "ANSWER" -> SessionDescription.Type.ANSWER
                    else -> null
                }

                sdpType?.let {
                    SessionDescription(it, sdp)
                }
            } catch (e: Exception) {
                Log.e("CallSignalManager", "❌ Error creating SessionDescription", e)
                null
            }
        }

        fun createIceCandidate(
            sdpMid: String?,
            sdpMLineIndex: Int?,
            candidate: String?
        ): IceCandidate? {
            return try {
                if (sdpMid == null || sdpMLineIndex == null || candidate == null) {
                    return null
                }

                IceCandidate(sdpMid, sdpMLineIndex, candidate)
            } catch (e: Exception) {
                Log.e("CallSignalManager", "❌ Error creating IceCandidate", e)
                null
            }
        }
    }
}
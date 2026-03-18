package com.messenger.messengerclient.data.model

import com.google.gson.annotations.SerializedName

data class StatusUpdateRequest(
    @SerializedName("messageId")
    val messageId: Long,

    @SerializedName("status")
    val status: String,

    @SerializedName("username")
    val username: String
)
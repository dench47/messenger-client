package com.messenger.messengerclient.data.model

import com.google.gson.annotations.SerializedName

data class MessageStatusBatchUpdateDto(
    @SerializedName("messageIds")
    val messageIds: List<Long>,

    @SerializedName("status")
    val status: String,

    @SerializedName("username")
    val username: String
)
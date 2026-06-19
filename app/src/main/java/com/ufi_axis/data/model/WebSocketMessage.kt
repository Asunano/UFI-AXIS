package com.ufi_axis.data.model

import com.google.gson.JsonElement

data class WebSocketMessage(
    val type: String,
    val data: JsonElement? = null
)

data class SubscriptionRequest(
    val subscribe: List<String>
)

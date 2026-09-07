package com.ufi_axis.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class WebSocketMessage(
    val type: String,
    val data: JsonElement? = null
)

@Serializable
data class SubscriptionRequest(
    val subscribe: List<String>
)

package com.ufi_axis_core.api

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.*
import kotlinx.serialization.serializerOrNull

/**
 * API 响应序列化工具
 * 将任意值转换为 JsonElement 以避免 kotlinx.serialization 混合类型错误
 */
object ResponseHelper {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 将任意值递归转换为 JsonElement，可安全用于 call.respond()
     * 支持: null, JsonElement, String, Boolean, Number, Map, List, Array, @Serializable 类
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun toJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Map<*, *> -> buildJsonObject {
                value.forEach { (k, v) ->
                    put(k.toString(), toJsonElement(v))
                }
            }
            is List<*> -> buildJsonArray {
                value.forEach { add(toJsonElement(it)) }
            }
            is Array<*> -> buildJsonArray {
                value.forEach { add(toJsonElement(it)) }
            }
            else -> {
                // 尝试用 kotlinx.serialization 处理 @Serializable 对象
                trySerialize(value) ?: JsonPrimitive(value.toString())
            }
        }
    }

    /**
     * 尝试使用 kotlinx.serialization 序列化 @Serializable 对象
     */
    @OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
    private fun trySerialize(value: Any): JsonElement? {
        return try {
            val kClass = value::class
            val serializer = kClass.serializerOrNull()
            if (serializer != null) {
                @Suppress("UNCHECKED_CAST")
                json.encodeToJsonElement(serializer as kotlinx.serialization.KSerializer<Any>, value)
            } else {
                android.util.Log.w("ResponseHelper", "No serializer for ${kClass.simpleName}, falling back to toString()")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("ResponseHelper", "Serialize failed for ${value::class.simpleName}: ${e.message}", e)
            null
        }
    }
}

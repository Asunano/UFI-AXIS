package com.ufi_axis_core.api

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
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
     * 显式序列化重载：路由侧对 @Serializable DTO 显式传入 [serializer]，
     * 避免运行时反射（serializerOrNull）。仅当显式序列化异常时才回退到反射路径。
     */
    fun <T> toJsonElement(value: T, serializer: KSerializer<T>): JsonElement {
        return try {
            json.encodeToJsonElement(serializer, value)
        } catch (e: Exception) {
            android.util.Log.e("ResponseHelper", "Explicit serializer failed: ${e.message}", e)
            toJsonElement(value)
        }
    }

    /**
     * 成功信封（C01）。`{success:true, ok:true, …pairs}`。
     *
     * `success` 与 `ok` 双写：core 历史上两种键都用过，双写让任何一端的既有读法都成立
     * （只增不减，老客户端不受影响）。
     */
    fun ok(vararg pairs: Pair<String, Any?>): JsonElement = toJsonElement(
        buildMap<String, Any?> {
            put("success", true)
            put("ok", true)
            pairs.forEach { put(it.first, it.second) }
        }
    )

    /**
     * 失败信封（C01）。`{success:false, ok:false, error:<message>, message:<message>, code:<code>}`。
     *
     * 为什么 `error` 与 `message` 双写：改动前 core 三种写法混用
     * （`{success:false,error}` / `{ok:false,error}` / 纯 `{error}`），
     * 两端只能逐端点猜读哪个键，web 侧还为此写了 `errMsg()` 兜底。双写 + 稳定的
     * [com.ufi_axis_core.contract.ErrorCode] 让客户端可以只认 `code`，且不破坏任何既有读法。
     *
     * @param code 取自 `core:contract` 的 `ErrorCode`，不要就地编字符串
     * @param message 面向用户的文案（迁移时**必须与原文案逐字一致**，否则就是行为变更）
     * @param extra 该端点特有的附加字段；同名键会覆盖上面的默认键
     */
    fun fail(
        code: String,
        message: String,
        extra: Map<String, Any?> = emptyMap()
    ): JsonElement = toJsonElement(
        buildMap<String, Any?> {
            put("success", false)
            put("ok", false)
            put("error", message)
            put("message", message)
            put("code", code)
            putAll(extra)
        }
    )

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

package com.ufi_axis.data.api

import com.ufi_axis.util.AppJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * Retrofit 转换器工厂：kotlinx.serialization 独占。
 *
 * T9 收尾后，UFI-AXIS 的 App 端已全量迁移至 kotlinx.serialization，
 * [UfiAxisApi] 所有端点的返回/请求类型均为 kotlinx 可序列化类型
 * （含 `kotlinx.serialization.json.JsonElement` 透传）。本工厂统一使用 [AppJson]
 * （isLenient=true / ignoreUnknownKeys=true / coerceInputValues=true）进行反/序列化，
 * 等价原 Gson `setLenient()` 行为，且不再依赖 Gson。
 *
 * 对任意返回/请求类型，若 kotlinx 能为其找到 serializer，则使用 kotlinx；
 * 否则返回 null，交由 Retrofit 给出明确的 "no converter" 错误（而非运行时崩溃）。
 */
class AppJsonConverterFactory private constructor(
    private val json: Json
) : Converter.Factory() {

    private val contentType = "application/json".toMediaType()

    override fun responseBodyConverter(
        type: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit
    ): Converter<ResponseBody, *>? {
        if (!isKotlinxSerializable(type)) return null
        return KotlinxResponseBodyConverter(json, type)
    }

    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<out Annotation>,
        methodAnnotations: Array<out Annotation>,
        retrofit: Retrofit
    ): Converter<*, RequestBody>? {
        // 新增：Map 类型特殊处理 —— 将 Map<K,V> 转为 JSON 对象。
        // JVM 类型擦除后，Retrofit 可能传入裸 Map.class（丢失 <String, Any> 泛型参数），
        // kotlinx.serialization 无法为裸 Map 解析 key/value 序列化器，故需手动转换。
        val rawType = when (type) {
            is Class<*> -> type
            is ParameterizedType -> type.rawType as Class<*>
            else -> null
        }
        if (rawType != null && Map::class.java.isAssignableFrom(rawType)) {
            return MapRequestBodyConverter(json, contentType)
        }

        // 原有逻辑：kotlinx 可序列化类型走标准路径
        if (!isKotlinxSerializable(type)) return null
        return KotlinxRequestBodyConverter(json, type, contentType)
    }

    private fun isKotlinxSerializable(type: Type): Boolean {
        return try {
            json.serializersModule.serializer(type)
            true
        } catch (_: SerializationException) {
            false
        }
    }

    private class KotlinxResponseBodyConverter(
        private val json: Json,
        private val type: Type
    ) : Converter<ResponseBody, Any?> {
        override fun convert(value: ResponseBody): Any? {
            val serializer = json.serializersModule.serializer(type) as KSerializer<Any>
            return value.use { json.decodeFromString(serializer, it.string()) }
        }
    }

    private class KotlinxRequestBodyConverter(
        private val json: Json,
        private val type: Type,
        private val contentType: okhttp3.MediaType
    ) : Converter<Any?, RequestBody> {
        override fun convert(value: Any?): RequestBody? {
            val serializer = json.serializersModule.serializer(type) as KSerializer<Any>
            return json.encodeToString(serializer, value ?: error("Request body is null"))
                .toRequestBody(contentType)
        }
    }

    /**
     * 将 Map<K, V> 手动转换为 JSON 字符串作为 RequestBody。
     *
     * 用于兼容 JVM 类型擦除后 kotlinx.serialization 无法为裸 Map 类型
     * 解析泛型参数的场景。调用侧传入 mutableMapOf<String, Any>(...)，
     * 此 converter 将其编码为 JSON object（如 {"url":"...","file_name":"..."}）。
     */
    private class MapRequestBodyConverter(
        private val json: Json,
        private val contentType: okhttp3.MediaType
    ) : Converter<Map<*, *>, RequestBody> {
        override fun convert(value: Map<*, *>): RequestBody {
            // 手动构建 JsonObject：key 转 String，value 按 Kotlin 类型映射为 JsonElement
            val jsonElement = buildJsonObject {
                for ((k, v) in value) {
                    val key = k?.toString() ?: continue
                    put(key, when (v) {
                        null -> JsonNull
                        is Number -> JsonPrimitive(v)
                        is Boolean -> JsonPrimitive(v)
                        is String -> JsonPrimitive(v)
                        is List<*> -> JsonArray(v.map { item ->
                            when (item) {
                                null -> JsonNull
                                is Number -> JsonPrimitive(item)
                                is Boolean -> JsonPrimitive(item)
                                else -> JsonPrimitive(item.toString())
                            }
                        })
                        is Map<*, *> -> JsonObject(v.entries.associate { (mk, mv) ->
                            (mk?.toString() ?: "") to when (mv) {
                                null -> JsonNull
                                is Number -> JsonPrimitive(mv)
                                is Boolean -> JsonPrimitive(mv)
                                else -> JsonPrimitive(mv.toString())
                            }
                        })
                        else -> JsonPrimitive(v.toString())
                    })
                }
            }
            return json.encodeToString(JsonObject.serializer(), jsonElement)
                .toRequestBody(contentType)
        }
    }

    companion object {
        fun create(json: Json = AppJson): AppJsonConverterFactory =
            AppJsonConverterFactory(json)
    }
}

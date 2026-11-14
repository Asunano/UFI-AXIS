package com.ufi_axis_core.deviceschema

import kotlinx.serialization.json.JsonObject

/**
 * 读侧客户端返回值的类型化凭据：「这份数据**过了归一化层**」。
 *
 * ## 为什么需要一个类型（批 B1）
 *
 * 在这之前，`GoformSignalClient` / `GoformWifiClient` 的查询方法全部返回裸 [JsonObject]，
 * 「字段名已经归一化」这件事只由方法体里那句 `fields.normalize(GROUP, raw)` 保证 ——
 * **靠的是纪律，不是类型**。后果很具体：接第二台设备时写一个同样返回 [JsonObject]、
 * 但忘了调归一化的实现，编译通过、接口「实现」了、route 拿到的字段名全错、
 * 前端静默显示空值，**没有任何测试会红**。
 *
 * 换成本类型之后，「返回了没过归一化层的数据」在**类型上不可表达**：
 * 想凑出一个 [NormalizedFields] 只有一条路 —— 真的跑一次 [FieldNormalizer.normalizeToFields]。
 *
 * ## 唯一构造途径
 *
 * - 构造函数 `private`；
 * - 伴生对象里只有一个 `internal` 的 [Companion.of]，它**只被 [FieldNormalizer.normalizeToFields]
 *   调用**，而 `internal` 的作用域是 `:core:device-schema` 这一个 Gradle 模块 ——
 *   归一化层自己的模块。所以对 `:core:goform` / `:core:api` / `:core:scheduler` / `:core`
 *   这些**全部**消费方来说，除了跑一次归一化没有别的构造入口（Kotlin 会在编译期拒绝）。
 * - 刻意**不提供** `NormalizedFields(someJsonObject)` 这类公开工厂：那等于把后门重新开一遍，
 *   做了也白做。[EMPTY] 不算后门，理由见它自己的注释。
 *
 * 一处实测到的 JVM 细节（不影响上面的保证，但别被字节码骗了）：`private constructor` 加
 * 伴生对象工厂，Kotlin 会额外生成一个**合成桥**构造函数，字节码里长这样
 * `public NormalizedFields(JsonObject, DefaultConstructorMarker)`。末参是 Kotlin 运行时的
 * 内部标记类型，**Kotlin 侧看不到也调不了**（本仓全 Kotlin）。`FieldNormalizerTest` 里那条
 * 反射断言钉的就是「除了这个合成桥，不许有别的公开构造函数」。

 *
 * ## ⚠ 语义是「过了归一化层」，**不是**「字段名一定是 canonical」
 *
 * 这个区别不是文字游戏，它由排障开关 `field_normalization_enabled` 决定：
 * 关掉它时 `GoformFieldMapper.normalize()` 里的闸门（`normalizeProfile ?: … ?: 透传`）
 * **根本不调用 [FieldNormalizer.normalize]，而是把设备原始响应原样往上传**
 * （对象都是同一个实例）。也就是说排障模式下，本类型包着的是**设备原名**。
 *
 * 所以：
 * - 本类型保证的是「这份数据**流经过**归一化闸门」，也就是「没有绕过闸门的旁路」；
 * - 「字段名是 canonical」只在归一化开着时成立（线上默认开着）；
 * - 唯一的例外方向是 `GoformFieldMapper.NORMALIZE_ALWAYS` 里的组（今天只有
 *   [FieldGroup.TRAFFIC_LIMIT]）：那些组**连排障模式也归一化**，所以它们的 canonical 性
 *   是无条件的。
 *
 * 想在「排障开关关掉」时也拿到 canonical，判据只有一份，在 `GoformFieldMapper.NORMALIZE_ALWAYS`
 * 的 KDoc 里 —— 不要试图用类型来表达那件事，那是运行期开关，编译期不可知。
 *
 * ## 用法
 *
 * 消费点用 [values] 解包成 [JsonObject] 再序列化。**解包出来的内容与改造前逐字一致**，
 * 对外 JSON 一个字节都没变（端到端断言见 `FieldNormalizerTest` 与
 * `GoformNormalizeAlwaysTest`）。
 */
class NormalizedFields private constructor(
    /**
     * 归一化层的产出对象 —— 对外序列化的就是它。
     *
     * 不做防御性拷贝：[JsonObject] 本身不可变，而 [FieldNormalizer.normalize] 每次都新建一个
     * （透传路径给的是设备响应那个实例，同样不可变）。
     */
    val values: JsonObject,
) {

    /** 按 [values] 比较 —— 两次归一化跑出同样内容就该相等（`GoformNormalizeAlwaysTest` 依赖这条）。 */
    override fun equals(other: Any?): Boolean =
        this === other || (other is NormalizedFields && values == other.values)

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "NormalizedFields($values)"

    companion object {

        /**
         * 空结果。给「两个查询都失败、但出口契约是非空」的那一个位置用
         * （`GoformWifiClient.getWifiSettingsMerged()`）。
         *
         * **这不是后门**：它不接受任何入参，因此**没法用它把未归一化的数据偷带上去** ——
         * 一个空对象里没有任何字段名可言。要往里塞东西仍然只能跑
         * [FieldNormalizer.normalizeToFields]。
         */
        val EMPTY: NormalizedFields = NormalizedFields(JsonObject(emptyMap()))

        /**
         * 唯一的构造入口，`internal` —— 只许 [FieldNormalizer.normalizeToFields] 调。
         *
         * 放在这里而不是让 [FieldNormalizer] 直接 `new`：Kotlin 的 `private` 构造函数
         * 对外层/同包的其它类都不可见，只有本类的伴生对象能碰 —— 于是「谁能构造」这件事
         * 在本文件里一眼看全，不需要翻整个模块。
         */
        internal fun of(values: JsonObject): NormalizedFields = NormalizedFields(values)
    }
}

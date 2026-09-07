package com.ufi_axis.ui.animation.page

import androidx.compose.ui.util.lerp
import com.ufi_axis.ui.components.common.UfiExperimentalApi
import kotlin.math.abs

/**
 * 页面转场策略注册表（T01 · 注册机制）。
 *
 * ## 职责
 * - 提供 id → [UfiPageTransition] 的全局查找，供设置项持久化与深链还原使用；
 * - 允许 `:app` 层或任意 feature 模块在运行时 [register] 自定义动画，注册后**自动出现在**
 *   设置页下拉列表（数据源即 [all]）；
 * - [byId] 对脏数据 / 未知 id **安全回退**，保证从 DataStore 读到旧版本或非法 id 也不会崩溃。
 *
 * ## 内置策略的装配时机
 * [Fade] / [Slide] / [Scale] / [Rotate] / [Cube3D] / [Flip3D] 六个引用由 **T03**
 * （`BuiltInTransitions.kt`）在初始化时赋值并 [register]。本文件**只提供注册机制**，
 * 不定义任何具体策略实现。
 *
 * ## 崩溃兜底
 * 六个引用虽声明为 `lateinit var`，但本对象的 `init` 块已先用一个内置的安全 Fade 兜底实现
 * 全部填充，因此**在 T03 装配之前访问也不会抛 `UninitializedPropertyAccessException`**，
 * 最坏情况只是所有动画表现为渐入渐出。T03 装配后这些兜底值会被真实实现覆盖。
 *
 * ## 线程约定
 * 注册表非线程安全，约定所有 [register] 调用发生在主线程的初始化阶段（`Application.onCreate`
 * 或 Composable 首次组合前）。
 */
@UfiExperimentalApi
object UfiPageTransitions {

    /** 保序 map：保证 [all] 的输出顺序 == 注册顺序，设置页下拉项顺序因而稳定可预期。 */
    private val registry = linkedMapOf<String, UfiPageTransition>()

    /**
     * 注册（或按 id 覆盖）一种转场策略。
     *
     * 覆盖同一 id 时**保持原插入位置**（`LinkedHashMap` 语义），因此 T03 重新注册内置策略
     * 不会打乱下拉列表顺序。
     */
    fun register(transition: UfiPageTransition) {
        registry[transition.id] = transition
    }

    /**
     * 按 id 查找策略，查不到时安全回退。
     *
     * @param id       目标 id，允许为 `null`（等价于"未设置"）。
     * @param fallback 查不到时的回退策略，默认为内置的安全 Fade 兜底实现。
     */
    fun byId(
        id: String?,
        fallback: UfiPageTransition = fallbackFade(),
    ): UfiPageTransition = registry[id] ?: fallback

    /** 当前已注册的全部策略，按注册顺序返回。设置页「切换动画」下拉直接消费此列表。 */
    fun all(): List<UfiPageTransition> = registry.values.toList()

    /** 渐入渐出。复刻现有 `tabEnter` 观感，是默认值，保证零视觉回归。由 T03 装配。 */
    lateinit var Fade: UfiPageTransition

    /** 水平平移。由 T03 装配。 */
    lateinit var Slide: UfiPageTransition

    /** 缩放淡入。由 T03 装配。 */
    lateinit var Scale: UfiPageTransition

    /** 平面旋转（绕 Z 轴）。由 T03 装配。 */
    lateinit var Rotate: UfiPageTransition

    /** 3D 立方体翻页。由 T03 装配。 */
    lateinit var Cube3D: UfiPageTransition

    /** 3D 卡片翻转（含背面剔除）。由 T03 装配。 */
    lateinit var Flip3D: UfiPageTransition

    init {
        // ── 崩溃兜底：先用安全 Fade 填满全部引用并占位注册 "fade"。
        // T03 的 BuiltInTransitions 会用真实实现覆盖这些引用与注册项。
        val safe = fallbackFade()
        Fade = safe
        Slide = safe
        Scale = safe
        Rotate = safe
        Cube3D = safe
        Flip3D = safe
        register(safe)
    }
}

/**
 * 内置的安全 Fade 兜底实现（**单例**，避免每次取默认参数都产生新对象）。
 *
 * 映射规格与 T03 的 `FadeTransition` 一致：
 * - `alpha = (1 - |p| * 1.6) coerceIn 0..1` —— 交叉淡化，中段短暂重叠
 * - `scale = lerp(0.98, 1, 1 - |p|)`        —— 轻微缩放，复刻 `tabEnter` 的 `scaleIn(0.98f)`
 */
private val FALLBACK_FADE: UfiPageTransition = object : UfiPageTransition {

    override val id: String = "fade"

    override val displayName: String = "渐入渐出"

    override fun layerAt(position: Float, ctx: UfiPageLayerContext): UfiPageLayer {
        // 夹取到 0..1，避免 position 超出 [-1, 1]（Pager 离屏页）时 lerp 向外插值。
        val distance = abs(position).coerceIn(0f, 1f)
        val scale = lerp(start = 0.98f, stop = 1f, fraction = 1f - distance)
        return UfiPageLayer(
            alpha = (1f - abs(position) * 1.6f).coerceIn(0f, 1f),
            scaleX = scale,
            scaleY = scale,
        )
    }
}

/** 获取内置的安全 Fade 兜底实现。 */
private fun fallbackFade(): UfiPageTransition = FALLBACK_FADE

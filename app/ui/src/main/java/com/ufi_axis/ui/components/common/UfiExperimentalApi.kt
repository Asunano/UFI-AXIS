package com.ufi_axis.ui.components.common

/**
 * 标记一个公共 UI 组件为**实验性 API**（F24）。
 *
 * 实验性组件允许在不保证向后兼容的前提下演进签名。调用方应意识到其 API 可能变更。
 * 与 Compose 惯例一致，可在其上方叠加 `@OptIn(UfiExperimentalApi::class)` 以显式 opt-in。
 *
 * 用法（声明级）：`@UfiExperimentalApi fun UfiNewThing(...)`
 * 调用方 opt-in：`@OptIn(UfiExperimentalApi::class)`
 */
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.FILE
)
annotation class UfiExperimentalApi

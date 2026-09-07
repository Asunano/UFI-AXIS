package com.ufi_axis.ui.components.common

/**
 * 标记一个公共 UI 组件为**稳定 API**（F24 签名冻结）。
 *
 * 被标注的组件（或文件）承诺：对外签名（参数列表、@Composable 语义、返回类型）保持稳定，
 * 修改须保持向后兼容。配合 Compose 的 [@Stable] / [@Immutable] 使用可进一步帮助编译器
 * 跳过重组合成。
 *
 * 用法（文件级）：`@file:UfiStableApi`
 * 用法（声明级）：`@UfiStableApi fun UfiButton(...)`
 *
 * 实验性 / 未完成组件请勿标注本注解，改用 [UfiExperimentalApi]。
 */
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.FILE
)
annotation class UfiStableApi

package com.ufi_axis.util

import java.net.URLEncoder

/**
 * 把一个值编码成**URI 组件**（用于 query 参数值与路由参数）。
 *
 * ## 为什么不能直接用 `URLEncoder.encode`
 * `URLEncoder` 做的是 **form 编码**（`application/x-www-form-urlencoded`）：空格编成 `+`。
 * 而这条链路上的两个解码方都不按 form 语义解：
 * - Navigation 的 `Uri.decode` 只解 `%XX`，`+` 原样留着；
 * - Ktor 的 query 解析虽然把 `+` 当空格，但那恰恰是灾难的另一半。
 *
 * 于是 `URLEncoder` 一旦遇上**值里本来就有 `+`** 就会出事（2026-09-21 实测）：
 * 专辑名 `万岁2001 新曲+精选` → 编码后空格是 `+`、真 `+` 是 `%2B`
 * → 中间某一层把 `%2B` 解回 `+` → 最后一层再把**两个 `+` 都**当成空格
 * → 服务端收到 `万岁2001 新曲 精选` → 查不到任何曲目，界面就是"专辑里没有歌"。
 *
 * 本函数把空格也编成 `%20`，于是编码结果里**不再出现任何裸 `+`**：
 * 无论后面是 `Uri.decode`、`URLDecoder.decode` 还是 Ktor 的 query 解析，
 * 都只会遇到 `%XX`，解出来一定是原值。
 *
 * ## 反向解码交给谁
 * 不需要配套的 `decode`：所有消费方（Navigation、Ktor）本身就会做一次百分号解码，
 * **不要再手动解第二次** —— 那是上面那个 bug 的直接成因。
 */
fun encodeUriComponent(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")

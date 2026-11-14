package com.ufi_axis.data.screen

/**
 * 一次截图请求的结果（2026-11-01）。
 *
 * ## 为什么要分成四种而不是「成功 / 失败」
 * 页面的轮询是**每秒 1–2 次**的，四种情况的处置完全不同，混成一个 error 字符串会出两种事故：
 * - `Busy`（429）当成失败 → 每两拍闪一次红字，而它只是"上一张还没跑完"，下一拍自然就好；
 * - `Disabled`（403）当成失败 → 用户只看到"截图失败"，不知道要去开开关。
 *
 * `core` 侧 `/api/screen/screenshot` 的契约是：PNG 二进制 / 403 开关未开 / 429 叠拍 /
 * 503 adb 或 display 不可用。这里一一对应，不认识的错误一律进 [Failed]。
 */
sealed interface ScreenShot {

    /** 成功：PNG 原始字节（设备原生像素，未缩放） */
    class Ok(val png: ByteArray) : ScreenShot

    /** 上一张还没跑完（HTTP 429）。**不是错误**：跳过这一拍即可。 */
    data object Busy : ScreenShot

    /** 开关没开（HTTP 403）。页面应停止轮询并把用户引到"开启投屏"。 */
    data object Disabled : ScreenShot

    /** 其它失败。reason 是可以直接显示给人看的一句话。 */
    class Failed(val reason: String) : ScreenShot
}

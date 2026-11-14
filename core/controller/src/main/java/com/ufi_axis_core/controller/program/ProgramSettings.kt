package com.ufi_axis_core.controller.program

/**
 * 程序端口与用户覆盖设置的解析/校验（2026-11-10）。
 *
 * 端口有两个来源：
 *  · 清单默认值（`run.internal_port` / `run.external_port`）；
 *  · 用户在「设置」里保存的覆盖值（`app.json` 的 `internalPort` / `externalPort`）。
 *
 * **所有读端口的地方都必须走这里**：运行时绑定、健康检查、Gateway 后端、`open` 返回的
 * 端口、适配器写进程序配置的端口。漏一处的结果就是「设置里改了，程序还绑在旧端口」。
 */
object ProgramSettings {
    /** 允许用户设置的端口下限。1024 以下要 root，普通应用绑不上。 */
    const val MIN_PORT = 1024
    /** 端口上限（含）。 */
    const val MAX_PORT = 65535

    /** 程序实际监听的端口；覆盖值越界/未设置时回落到清单默认。 */
    fun internal(meta: ProgramInstalledMeta?, manifest: ProgramManifest): Int =
        meta?.internalPort?.takeIf { it in MIN_PORT..MAX_PORT } ?: manifest.run.internalPort

    /**
     * 对外端口。
     *
     * gateway 模式是 Gateway 实际监听的端口；direct 模式下它不参与访问（浏览器直接开
     * internal），但设置里仍允许保存，切到 gateway 时不用再改一次。
     */
    fun external(meta: ProgramInstalledMeta?, manifest: ProgramManifest): Int =
        meta?.externalPort?.takeIf { it in MIN_PORT..MAX_PORT } ?: manifest.run.externalPort

    /**
     * 校验一次设置提交。`null` = 合法。
     *
     * 只做**纯值**校验（值域、互斥）；端口是否被占用要查 socket，由路由层做。
     */
    fun validate(internalPort: Int, externalPort: Int, accessMode: String): String? {
        if (accessMode !in setOf("direct", "gateway")) return "访问方式只能是 direct / gateway"
        if (internalPort !in MIN_PORT..MAX_PORT) return "内部端口必须在 $MIN_PORT–$MAX_PORT"
        if (accessMode == "gateway") {
            if (externalPort !in MIN_PORT..MAX_PORT) {
                return "经后台转发时外部端口必须在 $MIN_PORT–$MAX_PORT"
            }
            if (externalPort == internalPort) return "外部端口不能与内部端口相同"
        }
        return null
    }
}

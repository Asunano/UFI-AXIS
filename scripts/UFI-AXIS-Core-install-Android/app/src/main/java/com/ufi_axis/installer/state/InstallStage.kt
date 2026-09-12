package com.ufi_axis.installer.state

/**
 * 安装流程的 9 个阶段。严格对齐 bat 脚本的执行顺序，便于日志逐条比对。
 *
 * 依赖关系是线性的：每个阶段都依赖前一个阶段的产物
 * （例如「授权」必须知道包名，「启动」必须装完，「健康检查」必须已启动）。
 */
enum class InstallStage(val order: Int, val title: String, val runningText: String) {
    IDLE(0, "待开始", "等待开始"),
    CHECK_ENV(1, "检查本地环境", "正在检查内置 APK…"),
    PARSE_ADDR(2, "解析设备地址", "正在解析地址…"),
    PREFLIGHT(3, "连接前探测", "正在探测设备端口…"),
    CONNECT(4, "连接设备", "正在连接设备…"),
    CONFIRM(5, "确认安装", "等待确认…"),
    READY(6, "设备就绪检测", "正在确认设备状态…"),
    INSTALL(7, "安装 APK", "正在推送并安装…"),
    RESOLVE_PKG(8, "识别包名", "正在识别包名…"),
    GRANT(9, "授予权限", "正在授予权限…"),
    LAUNCH(10, "启动应用", "正在启动应用…"),
    HEALTH(11, "健康检查", "正在检查服务状态…"),
    DONE(12, "完成", "已完成"),
    FAILED(13, "失败", "安装失败");

    /** 是否处于可取消的运行中状态 */
    val isBusy: Boolean
        get() = this != IDLE && this != DONE && this != FAILED && this != CONFIRM
}

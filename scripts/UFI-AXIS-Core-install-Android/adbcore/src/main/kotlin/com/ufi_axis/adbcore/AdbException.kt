package com.ufi_axis.adbcore

/** ADB 相关异常基类 */
open class AdbException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** ADB 连接失败（TCP 层） */
class AdbConnectException(message: String, cause: Throwable? = null) : AdbException(message, cause)

/** ADB 握手失败（协议层） */
class AdbHandshakeException(message: String, cause: Throwable? = null) : AdbException(message, cause)

/**
 * 设备端拒绝了我们的公钥（用户点了拒绝，或超时未响应）。
 * 对应握手过程中第三次收到 AUTH(TOKEN)。
 */
class AdbAuthRejectedException(
    message: String = "设备拒绝了授权请求。请在设备屏幕上点『允许 USB 调试』后重试。"
) : AdbException(message)

/** 对端要求 TLS（即 Android 11+ 无线调试配对流程），本工具仅支持 5555 直连 */
class AdbTlsRequiredException(
    message: String = "设备要求 TLS 配对（无线调试模式）。本工具仅支持传统 5555 直连，" +
        "请在设备上改用 adb tcpip 5555 方式开放调试端口。"
) : AdbException(message)

/** 操作超时 */
class AdbTimeoutException(message: String) : AdbException(message)

/** 命令执行失败（exit code 非 0 或返回 FAIL） */
class AdbCommandException(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    message: String
) : AdbException(message)

/** 对端关闭了连接 */
class AdbConnectionClosedException(message: String = "ADB 连接已被设备关闭") : AdbException(message)

/** 协议数据异常 */
class AdbProtocolException(message: String) : AdbException(message)

package com.ufi_axis.adbcore

import java.lang.reflect.Constructor

/**
 * 构造一个不依赖真实 socket 的 [AdbStream]，用于单独测试字节缓冲与分帧逻辑。
 *
 * 通过反射调用 AdbStream 的内部构造函数（该构造函数对测试不可见），
 * 避免为了测试而放宽生产代码的可见性。
 */
object TestStreamFactory {

    fun create(client: AdbClient): AdbStream {
        // 取一个真实连接，复用它的 AdbConnection 作为写入目标
        val connField = AdbClient::class.java.getDeclaredField("connection")
        connField.isAccessible = true
        val connection = connField.get(client) as AdbConnection

        val ctor: Constructor<AdbStream> = AdbStream::class.java.getDeclaredConstructor(
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            AdbConnection::class.java,
            String::class.java
        )
        ctor.isAccessible = true
        return ctor.newInstance(9999, 9999, connection, "test:")
    }
}

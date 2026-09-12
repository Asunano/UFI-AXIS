package com.ufi_axis.adbcore

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一条通过 ADB OPEN 打开的逻辑流（对应一个服务，如 `shell:...` 或 `sync:`）。
 *
 * 关键设计：**报文边界 ≠ 逻辑消息边界**。
 * 对端可能把一条逻辑消息拆到多个 WRTE 报文里，也可能把多条消息塞进一个报文。
 * 因此本类维护一个字节缓冲，对外只暴露 [readFully] / [readAvailable]，
 * 由调用方按自己的子协议重新分帧（shell v2、SYNC 都依赖这个能力）。
 */
class AdbStream internal constructor(
    internal val localId: Int,
    val remoteId: Int,
    private val connection: AdbConnection,
    private val service: String
) {
    private val lock = Object()
    private val buffer = ByteArrayOutputStream()

    /** 对端已关闭写端（收到 CLSE） */
    private val closed = AtomicBoolean(false)

    /** 本端是否已主动 close，避免重复发送 CLSE */
    private val localClosed = AtomicBoolean(false)

    val isClosed: Boolean get() = closed.get()

    val serviceName: String get() = service

    /** 由 reader 线程调用：追加对端送来的数据 */
    internal fun onData(data: ByteArray) {
        synchronized(lock) {
            buffer.write(data)
            lock.notifyAll()
        }
    }

    /** 由 reader 线程调用：对端关闭了流 */
    internal fun onClose() {
        closed.set(true)
        synchronized(lock) {
            lock.notifyAll()
        }
    }

    /**
     * 读取恰好 [n] 个字节，不足则阻塞等待。
     * @throws AdbTimeoutException 超时
     * @throws AdbConnectionClosedException 流已关闭且缓冲不足
     */
    fun readFully(n: Int, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ByteArray {
        require(n >= 0) { "n 不能为负" }
        if (n == 0) return AdbProtocol.EMPTY

        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (buffer.size() < n) {
                if (closed.get()) {
                    throw AdbConnectionClosedException(
                        "流已关闭但数据不足：需要 $n 字节，仅有 ${buffer.size()} 字节"
                    )
                }
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) {
                    throw AdbTimeoutException("读取 $n 字节超时（已收到 ${buffer.size()} 字节）")
                }
                lock.wait(remain)
            }
            val all = buffer.toByteArray()
            val result = all.copyOfRange(0, n)
            buffer.reset()
            if (all.size > n) buffer.write(all, n, all.size - n)
            return result
        }
    }

    /** 尝试读取最多 [n] 字节；当前无数据时立即返回空数组（不阻塞） */
    fun readAvailable(n: Int = Int.MAX_VALUE): ByteArray {
        synchronized(lock) {
            if (buffer.size() == 0) return AdbProtocol.EMPTY
            val all = buffer.toByteArray()
            val take = minOf(n, all.size)
            val result = all.copyOfRange(0, take)
            buffer.reset()
            if (all.size > take) buffer.write(all, take, all.size - take)
            return result
        }
    }

    /** 阻塞等待至少 1 字节到达或流关闭/超时 */
    fun awaitData(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (buffer.size() == 0 && !closed.get()) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) return false
                lock.wait(remain)
            }
            return buffer.size() > 0
        }
    }

    /** 当前缓冲中已缓存但未消费的字节数 */
    fun available(): Int = synchronized(lock) { buffer.size() }

    /** 向该流写入数据（会按对端 maxdata 自动分片） */
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (localClosed.get()) throw AdbConnectionClosedException("流已关闭，无法写入")
        connection.writeToStream(this, data, offset, length)
    }

    fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    /** 主动关闭这条流（发送 CLSE） */
    fun close() {
        if (localClosed.compareAndSet(false, true)) {
            connection.closeStreamInternal(this)
        }
    }

    override fun toString(): String = "AdbStream(local=$localId, remote=$remoteId, service=$service)"

    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
        /** 单条命令的默认超时 */
        const val SHELL_TIMEOUT_MS = 60_000L
        /** pm install 这类慢命令的超时 */
        const val INSTALL_TIMEOUT_MS = 180_000L
    }
}

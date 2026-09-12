package com.ufi_axis.adbcore

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** 连接握手 / shell v2 / SYNC 的端到端验证（走本地假设备） */
class AdbConnectionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var server: FakeAdbServer? = null
    private var client: AdbClient? = null

    @After
    fun tearDown() {
        client?.close()
        server?.stop()
    }

    private fun newCrypto() = AdbCrypto(tempFolder.newFolder())

    private fun startServer(
        mode: FakeAdbServer.AuthMode = FakeAdbServer.AuthMode.ACCEPT_SIGNATURE,
        shellResponses: Map<String, Triple<String, String, Int>> = emptyMap(),
        serviceFilter: (String) -> Boolean = { true }
    ): FakeAdbServer {
        val s = FakeAdbServer(mode, shellResponses, serviceFilter)
        s.start()
        server = s
        return s
    }

    private fun connect(mode: FakeAdbServer.AuthMode = FakeAdbServer.AuthMode.ACCEPT_SIGNATURE): Pair<FakeAdbServer, AdbClient> {
        val s = startServer(mode)
        val c = AdbClient(newCrypto())
        c.connect("127.0.0.1", s.port)
        client = c
        return s to c
    }

    // ------------------------------------------------------------------
    // 握手
    // ------------------------------------------------------------------

    @Test
    fun `无鉴权设备首个回包即 CNXN 直接成功`() {
        val (_, c) = connect(FakeAdbServer.AuthMode.NO_AUTH)
        assertTrue(c.isConnected)
        assertEquals("device", c.getState())
        assertNotNull(c.peerBanner)
    }

    @Test
    fun `正常路径 —— TOKEN 签名被接受完成握手`() {
        val (s, c) = connect(FakeAdbServer.AuthMode.ACCEPT_SIGNATURE)
        assertTrue(c.isConnected)
        // 客户端应只提交过签名，未提交公钥
        assertEquals(listOf(AdbProtocol.AUTH_SIGNATURE), s.receivedAuthTypes)
    }

    @Test
    fun `签名被拒时提交公钥并触发等用户授权回调`() {
        val s = startServer(FakeAdbServer.AuthMode.REQUIRE_PUBLIC_KEY)
        val c = AdbClient(newCrypto())
        client = c

        val awaitingCalled = AtomicBoolean(false)
        c.connect("127.0.0.1", s.port, onAwaitingAuth = { awaitingCalled.set(true) })

        assertTrue("应触发「等待用户授权」回调", awaitingCalled.get())
        assertTrue(c.isConnected)
        // 先提交签名，再提交公钥
        assertEquals(
            listOf(AdbProtocol.AUTH_SIGNATURE, AdbProtocol.AUTH_RSAPUBLICKEY),
            s.receivedAuthTypes
        )
    }

    @Test(expected = AdbAuthRejectedException::class)
    fun `用户一直拒绝时抛出授权拒绝异常`() {
        val s = startServer(FakeAdbServer.AuthMode.REJECT_ALL)
        val c = AdbClient(newCrypto())
        client = c
        try {
            c.connect("127.0.0.1", s.port)
        } finally {
            c.close()
        }
    }

    @Test(expected = AdbTlsRequiredException::class)
    fun `设备要求 TLS 时给出明确错误`() {
        val s = startServer(FakeAdbServer.AuthMode.REQUIRE_TLS)
        val c = AdbClient(newCrypto())
        client = c
        try {
            c.connect("127.0.0.1", s.port)
        } finally {
            c.close()
        }
    }

    @Test(expected = AdbException::class)
    fun `连不上时抛出连接异常并给出排查提示`() {
        // 用一个必然关闭的端口
        val c = AdbClient(newCrypto())
        client = c
        try {
            c.connect("127.0.0.1", 1, connectTimeoutMs = 1000)
        } finally {
            c.close()
        }
    }

    // ------------------------------------------------------------------
    // shell v2
    // ------------------------------------------------------------------

    @Test
    fun `执行 shell 命令能走通链路`() {
        val (_, c) = connect()
        AdbShell.execText(c.rawConnection!!, "echo hi")
        assertTrue("设备端应收到命令", server!!.receivedShellCommands.any { it.startsWith("echo hi") })
    }

    @Test
    fun `pm list packages 解析为包名列表`() {
        val (_, c) = connect()
        val pkgs = c.listPackages(onlyThirdParty = true)
        assertEquals(listOf("com.android.chrome", "com.example.newapp"), pkgs)
    }

    @Test
    fun `pm path 能查到已安装包并返回路径`() {
        val (_, c) = connect()
        val path = c.pmPath("com.ufi_axis_core")
        assertEquals("/data/app/base.apk", path)
    }

    @Test
    fun `shell v2 的 stdout 被假设备分两个包发出仍能正确拼接`() {
        // 这是一条跨报文分片的核心验证：假设备刻意把 STDOUT 拆成两半发送
        val (_, c) = connect()
        val result = AdbShell.exec(c.rawConnection!!, "pm list packages -3")
        assertTrue("exit code 应为 0", result.exitCode == 0)
        assertTrue("stdout 应包含两个包", result.stdout.contains("com.android.chrome"))
        assertTrue("stdout 应包含第二个包", result.stdout.contains("com.example.newapp"))
    }

    @Test
    fun `shell v2 能区分 stdout 与 stderr`() {
        val s = startServer(
            shellResponses = mapOf(
                "mytest" to Triple("标准输出\n", "错误输出\n", 3)
            )
        )
        val c = AdbClient(newCrypto())
        client = c
        c.connect("127.0.0.1", s.port)

        val r = AdbShell.exec(c.rawConnection!!, "mytest")
        assertTrue(r.stdout.contains("标准输出"))
        assertTrue(r.stderr.contains("错误输出"))
        assertEquals(3, r.exitCode)
        assertFalse(r.isSuccess)
    }

    @Test
    fun `pm install 成功时 stdout 含 Success`() {
        val (_, c) = connect()
        val apk = tempFolder.newFile("dummy.apk").apply { writeBytes(ByteArray(1024) { it.toByte() }) }
        val result = c.installApk(apk)
        assertTrue(result.stdout.contains("Success"))
    }

    @Test
    fun `安装失败时抛出带 INSTALL_FAILED 原文的异常`() {
        val s = startServer(
            shellResponses = mapOf(
                "pm install -r -d '/data/local/tmp/ufi_core.apk'" to
                    Triple("Failure [INSTALL_FAILED_OLDER_SDK: ...]\n", "", 1)
            )
        )
        val c = AdbClient(newCrypto())
        client = c
        c.connect("127.0.0.1", s.port)

        val apk = tempFolder.newFile("dummy2.apk").apply { writeBytes(ByteArray(10)) }
        try {
            c.installApk(apk)
            throw AssertionError("应当抛出安装失败异常")
        } catch (e: AdbCommandException) {
            assertTrue("错误信息应包含原始失败原因", e.message!!.contains("INSTALL_FAILED_OLDER_SDK"))
        }
    }

    @Test
    fun `restorecon 失败时回退到流式安装 pm install -S 并成功`() {
        // 推送式安装报 restorecon 失败（真机 INSTALL_FAILED_MEDIA_UNAVAILABLE），
        // 应自动回退到流式安装 pm install -S <size>，由 pm 自己打 SELinux 上下文。
        val s = startServer(
            shellResponses = mapOf(
                "pm install -r -d '/data/local/tmp/ufi_core.apk'" to
                    Triple("Failure [INSTALL_FAILED_MEDIA_UNAVAILABLE: Failed to restorecon]\n", "", 1)
            )
        )
        val c = AdbClient(newCrypto())
        client = c
        c.connect("127.0.0.1", s.port)

        val apk = tempFolder.newFile("dummy3.apk").apply { writeBytes(ByteArray(2048) { it.toByte() }) }
        val result = c.installApk(apk)
        assertTrue("流式回退应成功", result.stdout.contains("Success"))
        // 确实走到了 pm install -S 流式路径
        assertTrue(
            "应回退到流式安装命令",
            s.receivedShellCommands.any { it.startsWith("pm install -S") }
        )
    }

    @Test
    fun `解析启动入口组件`() {
        val (_, c) = connect()
        val comp = c.resolveLauncherActivity("com.ufi_axis_core")
        assertEquals("com.ufi_axis_core/.MainActivity", comp)
    }

    @Test
    fun `进程存活检测`() {
        val (_, c) = connect()
        assertTrue(c.isProcessRunning("com.ufi_axis_core"))
    }

    // ------------------------------------------------------------------
    // SYNC 推送
    // ------------------------------------------------------------------

    @Test
    fun `推送文件到设备且字节完全一致`() {
        val (s, c) = connect()
        val payload = ByteArray(300_000) { (it % 251).toByte() } // 跨多个 64KB 分块
        val apk = tempFolder.newFile("core.apk").apply { writeBytes(payload) }

        val progressCalls = mutableListOf<Pair<Long, Long>>()
        c.installApk(apk, remoteName = "ufi_core.apk", onProgress = { sent, total ->
            progressCalls += sent to total
        })

        val received = s.receivedFiles["/data/local/tmp/ufi_core.apk"]
        assertNotNull("设备端应收到文件", received)
        assertEquals("收到的字节数应一致", payload.size, received!!.size)
        assertTrue("收到内容应逐字节一致", payload.contentEquals(received))
        assertTrue("应有进度回调", progressCalls.isNotEmpty())
        assertEquals("总大小应一致", payload.size.toLong(), progressCalls.last().second)
    }

    @Test
    fun `推送小文件不产生空分块问题`() {
        val (s, c) = connect()
        val payload = ByteArray(10) { it.toByte() }
        val f = tempFolder.newFile("tiny.apk").apply { writeBytes(payload) }
        c.installApk(f, remoteName = "tiny.apk")
        val received = s.receivedFiles["/data/local/tmp/tiny.apk"]
        assertNotNull(received)
        assertEquals(10, received!!.size)
    }

    // ------------------------------------------------------------------
    // 服务拒绝
    // ------------------------------------------------------------------

    @Test
    fun `对端拒绝服务时抛出可读异常`() {
        val s = startServer(serviceFilter = { false })
        val c = AdbClient(newCrypto())
        client = c
        c.connect("127.0.0.1", s.port)

        try {
            AdbShell.exec(c.rawConnection!!, "anything")
            throw AssertionError("应当抛异常")
        } catch (e: AdbException) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }

    // ------------------------------------------------------------------
    // 纯函数解析器
    // ------------------------------------------------------------------

    @Test
    fun `流缓冲能处理逐字节到达的任意分片`() {
        // 构造 STDOUT + STDERR + EXIT，然后逐字节喂给流，模拟最恶劣的分片
        // 包头 = 1 字节 ID + 4 字节长度
        val full = AdbProtocol.concat(
            byteArrayOf(AdbShell.ID_STDOUT.toByte()), AdbProtocol.le32(5), "hello".toByteArray(),
            byteArrayOf(AdbShell.ID_STDERR.toByte()), AdbProtocol.le32(3), "err".toByteArray(),
            byteArrayOf(AdbShell.ID_EXIT.toByte()), AdbProtocol.le32(1), byteArrayOf(7)
        )

        val (_, c) = connect()
        val stream = TestStreamFactory.create(c)
        for (b in full) stream.onData(byteArrayOf(b))

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        var exit = -1
        while (stream.available() >= AdbShell.V2_HEADER_SIZE) {
            val id = stream.readFully(1, 1000)[0].toInt() and 0xFF
            val len = AdbProtocol.getInt(stream.readFully(4, 1000), 0)
            val payload = stream.readFully(len, 1000)
            when (id) {
                AdbShell.ID_STDOUT -> stdout.write(payload)
                AdbShell.ID_STDERR -> stderr.write(payload)
                AdbShell.ID_EXIT -> exit = payload[0].toInt() and 0xFF
            }
        }
        assertEquals("hello", stdout.toString())
        assertEquals("err", stderr.toString())
        assertEquals(7, exit)
    }
}

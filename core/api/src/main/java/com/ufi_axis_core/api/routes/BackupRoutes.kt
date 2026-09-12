package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.backup.BackupAssembler
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.BackupCrypto
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 配置备份与恢复。
 *
 * ## 包格式
 *
 * 外层是一个 ZIP，固定两个条目：
 * - `manifest.json` —— **始终明文**，因为必须先读它才知道怎么解密
 * - `payload.zip` 或 `payload.enc` —— 前者是未加密的各段内容，后者是同一份内容整体加密
 *
 * 段的划分与取舍见 [BackupAssembler]。
 *
 * ## 为什么是"整包加密"而不是"只加密敏感段"
 *
 * 只加密密码字段的话，`payload.zip` 里仍然明文暴露"这台设备配了哪些通知渠道、
 * 定时任务叫什么名字、隧道有几条" —— 这些也是隐私。整包加密顺手把它们盖住，
 * 代价只是加密态下无法在不输口令的情况下预览段清单（这是可接受的，manifest 里
 * 已经带了段数与是否含敏感字段的概要）。
 *
 * ## AAD
 *
 * 加解密都以 `manifest.json` 的**原始字节**作为 GCM 的 AAD。所以篡改 manifest
 * （改 KDF 轮数、改 `encrypted` 标记、把密文搬到另一个包的 manifest 下）都会让解密失败，
 * 而不是解出可疑内容。
 *
 * ## 口令走请求头
 *
 * `preview` / `import` 的 body 已经被 ZIP 占满，口令只能另走一处。选请求头
 * [HEADER_PASSPHRASE] 而不是 query 参数：query 会进访问日志与浏览器历史。
 *
 * 局域网是明文 HTTP，请求体与该请求头在链路上不加密。这里**不阻止**隧道来源操作
 * （隧道通常已有 HTTPS），只在 `/info` 里回报 `origin`，由客户端在「隧道 + http」
 * 这一种组合下提示用户。
 *
 * ## 明文导出要显式确认
 *
 * `encrypted=false` 时必须带 `acknowledge_plaintext=true` 才放行。这不是形式主义：
 * 明文包里既有设备后台密码、隧道凭据这类凭据，也有「配了哪些渠道、任务叫什么」这类隐私，
 * 且文件一旦落盘就再无保护。确认标记只能由「已经向用户展示过风险」的客户端带上，
 * 服务端不替客户端决定要不要提示。
 *
 * ## 解压体积有上限
 *
 * 上传体积（[MAX_UPLOAD_BYTES]）与解压体积是两回事：ZIP 能把几十 KB 放大成几个 GB。
 * 外层与内层 ZIP 的每个条目都走 [readEntryBounded] 计数读取，并另加内层累计上限，
 * 超限直接 400，绝不让解压结果决定内存占用。
 */
class BackupRoutes(
    private val settings: AppSettings,
    private val assembler: BackupAssembler,
    /**
     * 设备上是否有隧道进程在运行。与 [PairingRoutes] 同一判据，见 [isTunnelOrigin]。
     * 默认 `true` 是保守取值：忘记接线时最多多提示一次，不会漏提示。
     */
    private val tunnelActive: () -> Boolean = { true }
) {

    fun register(route: Route) {
        route.route("/backup") {
            get("/info") {
                call.respond(toJsonElement(mapOf(
                    "format" to FORMAT_VERSION,
                    "origin" to originOf(call),
                    "kdf_iterations" to BackupCrypto.DEFAULT_ITERATIONS,
                    "max_iterations" to BackupCrypto.MAX_ITERATIONS,
                    "min_passphrase_length" to BackupCrypto.MIN_PASSPHRASE_LENGTH,
                    "max_upload_bytes" to MAX_UPLOAD_BYTES,
                    "plaintext_requires_ack" to true,
                    "sensitive_keys" to AppSettings.SENSITIVE_BACKUP_KEYS.toList()
                )))
            }

            post("/export") {
                val body = runCatching { call.receiveJsonObject() }.getOrNull() ?: JsonObject(emptyMap())
                val encrypted = body["encrypted"]?.jsonPrimitive?.booleanOrNull ?: true
                val passphrase = body["passphrase"]?.jsonPrimitive?.contentOrNull ?: ""
                if (encrypted) {
                    BackupCrypto.validatePassphrase(passphrase)?.let { why ->
                        call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, why)
                        return@post
                    }
                } else if (body["acknowledge_plaintext"]?.jsonPrimitive?.booleanOrNull != true) {
                    // 明文导出必须被**显式确认**，不能靠客户端自觉。
                    // 包里除了设备后台密码、隧道凭据这类明摆着的凭据，还有「配了哪些通知渠道、
                    // 定时任务叫什么名字、隧道有几条」这类隐私；文件一旦落盘就不再有任何保护。
                    // 两端 UI 都会先弹一次风险确认再带上这个标记 —— 缺标记说明调用方没让用户
                    // 看见风险，直接拒，而不是默默生成一个明文包。
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "明文导出需要确认：设备后台密码、隧道凭据、通知渠道令牌等将不做任何加密，" +
                            "获得该文件的任何人都可直接读取。确认后请带 acknowledge_plaintext=true 重试"
                    )
                    return@post
                }
                // 客户端把自己那份偏好放进请求体，由 core 拼进同一个包 —— 这就是"合体"的做法，
                // 避免每个客户端各造一个 ZIP、格式各写一遍。
                val clientSections = mutableMapOf<String, String>()
                (body["client"] as? JsonObject)?.forEach { (name, element) ->
                    clientSections[name] = element.toString()
                }

                val bytes = withContext(Dispatchers.IO) {
                    val sections = assembler.collect(clientSections)
                    val payload = zipSections(sections)
                    buildPackage(payload, encrypted, passphrase, sections.size)
                }
                val name = "ufi-axis-backup-${System.currentTimeMillis()}.ufibak"
                // 文件名是我们自己生成的纯 ASCII，不需要 RFC 5987 编码
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$name\"")
                if (!encrypted) {
                    // 明文包在响应上留痕，便于抓包/日志侧一眼看出这条下载没有加密保护
                    call.response.header(HEADER_PLAINTEXT_FLAG, "true")
                    AppLogger.w(TAG, "已按用户确认导出**未加密**备份包（含敏感配置）")
                }
                call.respondBytes(bytes, ContentType.Application.Zip)
            }

            post("/preview") {
                val pack = readPackage(call) ?: return@post
                val entries = withContext(Dispatchers.IO) {
                    openPayload(call, pack) ?: return@withContext null
                } ?: return@post
                val sections = assembler.describe(entries)
                call.respond(toJsonElement(mapOf(
                    "format" to (pack.manifest["format"]?.jsonPrimitive?.intOrNull ?: 0),
                    "created_at" to (pack.manifest["created_at"]?.jsonPrimitive?.longOrNull ?: 0L),
                    "device_id" to (pack.manifest["device_id"]?.jsonPrimitive?.contentOrNull ?: ""),
                    "encrypted" to pack.encrypted,
                    "same_device" to (pack.manifest["device_id"]?.jsonPrimitive?.contentOrNull == settings.deviceId),
                    "sections" to sections.map {
                        mapOf(
                            "path" to it.path,
                            "label" to it.label,
                            "items" to it.itemCount,
                            "sensitive_items" to it.sensitiveCount
                        )
                    }
                )))
            }

            post("/import") {
                val replace = call.request.queryParameters["mode"] == MODE_REPLACE
                val pack = readPackage(call) ?: return@post
                val entries = withContext(Dispatchers.IO) {
                    openPayload(call, pack) ?: return@withContext null
                } ?: return@post

                val report = withContext(Dispatchers.IO) { assembler.apply(entries, replace) }
                AppLogger.w(
                    TAG,
                    "备份恢复完成 mode=${if (replace) MODE_REPLACE else MODE_MERGE} " +
                        "applied=${report.applied.size} failed=${report.failed.size}"
                )
                call.respond(toJsonElement(mapOf(
                    "success" to true,
                    "mode" to if (replace) MODE_REPLACE else MODE_MERGE,
                    "applied" to report.applied,
                    "failed" to report.failed,
                    "needs_restart" to report.needsRestart,
                    // 客户端段原样回给对应客户端自行落地：core 不解释它们的语义
                    "client_app" to assembler.clientSection(entries, BackupAssembler.CLIENT_APP),
                    "client_web" to assembler.clientSection(entries, BackupAssembler.CLIENT_WEB)
                )))
            }
        }
    }

    // ── 打包 ──

    private fun zipSections(sections: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            sections.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun buildPackage(
        payload: ByteArray,
        encrypted: Boolean,
        passphrase: String,
        sectionCount: Int
    ): ByteArray {
        val salt = BackupCrypto.newSalt()
        val iterations = BackupCrypto.DEFAULT_ITERATIONS
        val manifest = buildJsonObject {
            put("format", FORMAT_VERSION)
            put("created_at", System.currentTimeMillis())
            put("device_id", settings.deviceId)
            put("encrypted", encrypted)
            put("section_count", sectionCount)
            put("payload_sha256", sha256Hex(payload))
            if (encrypted) {
                putJsonObject("kdf") {
                    put("algorithm", BackupCrypto.KDF_ALGORITHM)
                    put("iterations", iterations)
                    put("salt", B64.encodeToString(salt))
                }
            }
        }
        // manifest 的原始字节既是 ZIP 里的内容，也是 GCM 的 AAD —— 必须是同一份字节，
        // 所以这里只序列化一次，后面全用 manifestBytes。
        val manifestBytes = manifest.toString().toByteArray()

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_MANIFEST))
            zip.write(manifestBytes)
            zip.closeEntry()

            if (encrypted) {
                val chars = passphrase.toCharArray()
                try {
                    val sealed = BackupCrypto.seal(payload, chars, salt, iterations, manifestBytes)
                    zip.putNextEntry(ZipEntry(ENTRY_PAYLOAD_ENC))
                    // IV 不是秘密，但必须原样保留 → 直接前置到密文
                    zip.write(sealed.iv)
                    zip.write(sealed.cipherText)
                    zip.closeEntry()
                } finally {
                    chars.fill('\u0000')
                }
            } else {
                zip.putNextEntry(ZipEntry(ENTRY_PAYLOAD_PLAIN))
                zip.write(payload)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // ── 解包 ──

    private class Package(
        val manifest: JsonObject,
        val manifestBytes: ByteArray,
        val encrypted: Boolean,
        val payload: ByteArray
    )

    /** 读外层 ZIP。失败时已经回过错误响应，调用方直接 return。 */
    private suspend fun readPackage(call: ApplicationCall): Package? {
        val raw = withContext(Dispatchers.IO) { readBounded(call) }
        if (raw == null) {
            call.respondFail(
                HttpStatusCode.PayloadTooLarge, ErrorCode.BAD_REQUEST,
                "备份文件超过 ${MAX_UPLOAD_BYTES / 1024 / 1024}MB 上限"
            )
            return null
        }
        var manifestBytes: ByteArray? = null
        var payload: ByteArray? = null
        var encrypted = false
        runCatching {
            ZipInputStream(raw.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when (entry.name) {
                        ENTRY_MANIFEST -> manifestBytes = zip.readEntryBounded(MAX_MANIFEST_BYTES)
                        ENTRY_PAYLOAD_ENC -> { payload = zip.readEntryBounded(MAX_UPLOAD_BYTES); encrypted = true }
                        ENTRY_PAYLOAD_PLAIN -> payload = zip.readEntryBounded(MAX_UPLOAD_BYTES)
                        else -> Unit // 未知条目忽略：留出向后兼容的空间
                    }
                }
            }
        }.onFailure {
            val why = if (it is ZipOverflowException) it.message!! else "不是有效的备份文件"
            call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, why)
            return null
        }
        val mb = manifestBytes
        val pl = payload
        if (mb == null || pl == null) {
            call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "备份文件缺少必要内容")
            return null
        }
        val manifest = runCatching { Json.parseToJsonElement(mb.toString(Charsets.UTF_8)).jsonObject }
            .getOrElse {
                call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "备份文件的清单无法解析")
                return null
            }
        if ((manifest["format"]?.jsonPrimitive?.intOrNull ?: 0) > FORMAT_VERSION) {
            call.respondFail(
                HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                "这个备份文件来自更新版本的 UFI-AXIS，请先升级设备端再恢复"
            )
            return null
        }
        return Package(manifest, mb, encrypted, pl)
    }

    /** 解密（如需）并把内层 ZIP 摊成「路径 → 字节」。失败时已回错误响应。 */
    private suspend fun openPayload(call: ApplicationCall, pack: Package): Map<String, ByteArray>? {
        val plain: ByteArray = if (!pack.encrypted) {
            pack.payload
        } else {
            val passphrase = call.request.header(HEADER_PASSPHRASE).orEmpty()
            if (passphrase.isEmpty()) {
                call.respondFail(HttpStatusCode.Unauthorized, ErrorCode.BAD_REQUEST, "这是加密备份，需要提供口令")
                return null
            }
            val kdf = pack.manifest["kdf"] as? JsonObject
            val iterations = kdf?.get("iterations")?.jsonPrimitive?.intOrNull
            val saltB64 = kdf?.get("salt")?.jsonPrimitive?.contentOrNull
            if (iterations == null || saltB64.isNullOrEmpty()) {
                call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "备份文件的加密参数不完整")
                return null
            }
            if (pack.payload.size <= BackupCrypto.IV_BYTES) {
                call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "备份文件的加密内容不完整")
                return null
            }
            val chars = passphrase.toCharArray()
            try {
                val sealed = BackupCrypto.Sealed(
                    iv = pack.payload.copyOfRange(0, BackupCrypto.IV_BYTES),
                    cipherText = pack.payload.copyOfRange(BackupCrypto.IV_BYTES, pack.payload.size)
                )
                BackupCrypto.open(sealed, chars, B64D.decode(saltB64), iterations, pack.manifestBytes)
            } catch (e: BackupCrypto.InvalidPassphraseException) {
                call.respondFail(HttpStatusCode.Unauthorized, ErrorCode.INVALID_PASSWORD, e.message ?: "口令错误")
                return null
            } catch (e: IllegalArgumentException) {
                // KDF 轮数低于安全下限，或 base64 盐损坏
                call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "备份文件的加密参数不可用")
                return null
            } finally {
                chars.fill('\u0000')
            }
        }

        // 完整性对照：manifest 里的摘要不匹配说明包被截断或改过。
        // 加密包其实已由 GCM tag 保证，这条主要为不加密的包兜底。
        val expected = pack.manifest["payload_sha256"]?.jsonPrimitive?.contentOrNull
        if (!expected.isNullOrEmpty() && expected != sha256Hex(plain)) {
            call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "备份文件已损坏（内容校验不通过）")
            return null
        }

        val out = LinkedHashMap<String, ByteArray>()
        var totalUncompressed = 0
        runCatching {
            ZipInputStream(plain.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    // 单条上限 + 累计上限两道都要：只有单条上限时，一个塞满"刚好不超限"条目的包
                    // 照样能把内存吃光（见 [MAX_ENTRY_BYTES] / [MAX_UNCOMPRESSED_BYTES]）。
                    val bytes = zip.readEntryBounded(MAX_ENTRY_BYTES)
                    totalUncompressed += bytes.size
                    if (totalUncompressed > MAX_UNCOMPRESSED_BYTES) throw ZipOverflowException()
                    out[entry.name] = bytes
                }
            }
        }.onFailure {
            val why = if (it is ZipOverflowException) it.message!! else "备份内容无法解压"
            call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, why)
            return null
        }
        return out
    }

    /**
     * 读一个 ZIP 条目的内容，解压后超过 [limit] 立即失败。
     *
     * **不能用 `ZipInputStream.readBytes()`**：那是「把该条目解压到底」，解出多少完全由包里
     * 的内容决定 —— 一个几十 KB 的 zip bomb 能被解成几个 GB，在 256MB RAM 的设备上直接 OOM。
     * 外层（[readPackage]）与内层（[openPayload]）两个 ZIP 都是不可信输入，因此都必须走这里。
     */
    private fun ZipInputStream.readEntryBounded(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val read = read(chunk)
            if (read <= 0) break
            if (out.size() + read > limit) throw ZipOverflowException()
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * 解压后体积超限。
     *
     * 用独立异常类型而不是复用 `IOException`：这样能对用户说清是「包太大/被撑爆」而不是
     * 「包损坏」——前者是明确的拒绝理由，后者会让用户反复重试一个不可能成功的文件。
     */
    private class ZipOverflowException : Exception("备份内容解压后超出上限，已拒绝")

    /**
     * 按 [MAX_UPLOAD_BYTES] 上限读请求体；超限返回 null。
     *
     * **不能用 `readBytes(n)`**：Kotlin 的 `InputStream.readBytes(estimatedSize)` 那个参数是
     * 初始缓冲区大小，不是上限 —— 它照样会把整个流读完。这是唯一一个接受任意二进制的端点，
     * 不做真正的边读边计数就是给一发 OOM 的机会。
     */
    private suspend fun readBounded(call: ApplicationCall): ByteArray? {
        val stream = call.receiveStream()
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(chunk)
            if (read <= 0) break
            if (buffer.size() + read > MAX_UPLOAD_BYTES) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    /**
     * 请求是从局域网还是隧道进来的。
     *
     * 只做上报、不做拦截：隧道通常自带 HTTPS，拦了反而挡住正常的远程备份。
     * 客户端拿到 `tunnel` 且自己走的是 http 时，应提示内容在链路上是明文的。
     */
    private fun originOf(call: ApplicationCall): String =
        if (isTunnelOrigin(call.request.local.localAddress, tunnelActive())) "tunnel" else "lan"

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "Backup"

        /** 包格式版本。读到更大的值就拒绝，而不是猜着解析。 */
        const val FORMAT_VERSION = 1

        const val ENTRY_MANIFEST = "manifest.json"
        const val ENTRY_PAYLOAD_ENC = "payload.enc"
        const val ENTRY_PAYLOAD_PLAIN = "payload.zip"

        const val HEADER_PASSPHRASE = "X-Backup-Passphrase"

        /** 明文导出时在响应上留下的标记头，便于抓包/日志侧识别「这条下载没有加密保护」。 */
        const val HEADER_PLAINTEXT_FLAG = "X-Backup-Plaintext"

        const val MODE_MERGE = "merge"
        const val MODE_REPLACE = "replace"

        /** 上传上限 8MB。当前包的量级是几十 KB，留足余量同时挡住 OOM。 */
        const val MAX_UPLOAD_BYTES = 8 * 1024 * 1024

        /** 外层 ZIP 里 `manifest.json` 的解压上限。实际只有几百字节，1MB 是纯防御值。 */
        private const val MAX_MANIFEST_BYTES = 1 * 1024 * 1024

        /** 内层 ZIP 单个条目的解压上限。当前最大的段（配置 JSON）也只有几十 KB。 */
        private const val MAX_ENTRY_BYTES = 4 * 1024 * 1024

        /**
         * 内层 ZIP 所有条目的解压总量上限。
         *
         * 与单条上限配合使用：单条上限挡不住「一个包里塞几百个刚好不超限的条目」，
         * 总量上限才是内存占用的真实兜底。
         */
        private const val MAX_UNCOMPRESSED_BYTES = 16 * 1024 * 1024

        private val B64: Base64.Encoder = Base64.getEncoder()
        private val B64D: Base64.Decoder = Base64.getDecoder()
    }
}

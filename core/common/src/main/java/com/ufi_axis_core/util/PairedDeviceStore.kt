package com.ufi_axis_core.util

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * 配对设备记录。
 *
 * 主键 = fingerprint，**由服务端据 [pubKey] 计算**（`DeviceAuth.fingerprintOf`），
 * 绝不采信客户端自报的指纹字符串 —— 否则指纹与公钥可以不匹配，攻击者能拿自己的密钥
 * 冒充别人的记录。
 *
 * @property hwId 客户端上报的**硬件级稳定标识**（App=ANDROID_ID 派生；Web 恒为空串）。
 *   **仅用于合并同一台硬件换指纹后产生的重复记录，不参与任何安全判定**。
 *   它是客户端可任意伪造的明文字段，2026-08-28 已移除它对配对配额检查的旁路。
 *   默认空串：Web 与旧记录都是空串，空串永不参与匹配（见 [PairedDeviceStore.findByHwId]）。
 * @property pubKey 设备身份公钥，X.509 SPKI DER 的 base64。每请求签名的验签依据。
 *   空串 = 旧记录（部署严格设备独立性之前配对的），**无法通过鉴权，必须重新配对**。
 * @property tokenHash 该设备独占 token 的 SHA-256 十六进制小写。
 *   token 明文不落盘：即便 `paired_devices.json` 泄漏也拿不到可用凭据。
 *   空串 = 尚未签发（同上，需重新配对）。
 */
@Serializable
data class PairedDeviceRecord(
    val fingerprint: String,
    val deviceName: String = "",
    val lastSeen: Long = 0,
    val createdAt: Long = 0,
    val hwId: String = "",
    val pubKey: String = "",
    val tokenHash: String = ""
)

/**
 * 配对设备存储：应用私有目录 `context.filesDir/paired_devices.json`。
 *
 * - 提供记录级结构（设备名/时间戳），替代旧的逗号分隔 fingerprint 字符串。
 * - 迁移：首次构造且文件不存在、但 [AppSettings.pairedFingerprints] 有旧数据时，
 *   为每个 fingerprint 生成记录（deviceName=fingerprint），随后以文件为准。
 * - 写入统一收敛到 [PairingManager]（AppSettings 指纹列表双写兼容），本类不直接依赖管理器。
 *
 * ## 2026-09-08 事故：Core 重启后手机被踢下线、要求重新输入密码
 *
 * 这个文件承载的是**全部设备凭据**（`pubKey` / `tokenHash`），而当时的落盘是
 * `file.writeText(text)` —— 先把真文件截断到 0 再写。鉴权成功后每请求都会 [touch]，
 * 每 30s 就在后台线程做一次这样的全量重写。进程恰好死在「截断→写完」这个窗口里，
 * 留下的就是半截 JSON；下次启动解析失败、按空列表兜底，于是：
 * 零配对记录 → [findByTokenHash] 返回 null → `/api` 全部鉴权失败 → App 清空本地 token
 * → 回配对页 → 因为 `devicePasswordSet=true` 而要求输入密码。一次数据事故等于全员重配对。
 *
 * 现在的约束（三条都是硬要求，别再改回去）：
 * 1. [saveInternal] 写 `.tmp` + `fd.sync()` + `renameTo` 原子替换，**真文件永不处于截断态**；
 *    替换前把上一份好内容留成 `.bak`。
 * 2. [loadInternal] 解析失败**不静默兜底**：先取 `.bak`，再把坏文件挪到 `.corrupt` 备查，
 *    并把 [degraded] 置位。
 * 3. [degraded] 为真时，鉴权侧必须回「存储不可用、可重试」而不是「你没配对」
 *    （见 `DeviceRequestVerifier.Result.StoreUnavailable`）—— 撒这个谎的代价是客户端自毁凭据。
 *
 * 另外：[touch] 的异步落盘线程必须在服务停止时收干净（[shutdown]），
 * 且整个进程只能有一个实例（[getInstance]）—— 两个实例各有各的锁与队列，
 * 旧实例排队中的一次落盘会把新实例刚写的记录覆盖回去。
 */
class PairedDeviceStore(
    private val context: Context,
    private val settings: AppSettings
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val lock = Any()

    /**
     * 降级态：**存在**一份配对存储，但我们读不出来（真文件与 `.bak` 都解析失败）。
     *
     * 与"空存储"必须区分开：空存储意味着"确实没有任何设备配对过"，
     * 而降级意味着"设备大概是配过的，只是我们此刻不知道是谁"。前者回「未配对」是事实，
     * 后者回「未配对」是谎话 —— 客户端据此清空 token，用户被迫重新输密码（2026-09-08 事故）。
     *
     * 一次成功的落盘（配对/解绑等用户可感知的写操作）会把它清掉：那之后文件内容与内存一致，
     * 继续无限期回 503 只会把可用的服务也拖成不可用。
     */
    @Volatile
    var degraded: Boolean = false
        private set

    private val file: File
        get() = File(context.filesDir, FILE_NAME)

    // 内存缓存：读操作先命中缓存，写操作更新缓存并落盘
    private var records: List<PairedDeviceRecord> = emptyList()

    /** [touch] 上次落盘时刻（落盘节流，见 [touch]）。 */
    private var lastTouchFlushMs: Long = 0

    /**
     * [touch] 的异步落盘线程（单线程 + daemon）。
     * 只服务 touch：其余写操作（upsert/remove/rename/clear）都是用户可感知的动作，
     * 必须同步落盘保证「操作完成即已持久化」。
     *
     * daemon 线程在进程退出时是**被直接掐掉**的（不跑 finally、不等 IO 完成），
     * 所以服务停止路径必须显式调用 [shutdown]，别指望进程退出帮你收尾。
     */
    private val flushExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "PairedDeviceStore-flush").apply { isDaemon = true }
        }

    /** [shutdown] 已执行：[touch] 不再往已关停的 executor 上排任务。 */
    @Volatile
    private var stopped = false

    init {
        synchronized(lock) {
            records = loadInternal()
            // 迁移旧版 pairedFingerprints：仅当**确认没有存储**（文件不存在、非降级、内存也为空）
            // 且旧数据非空时才做。
            // 三个前置条件都是必要的：迁移出来的记录 pubKey/tokenHash 都是空串，
            // 鉴权侧会直接判 DeviceKeyMissing —— 它只能恢复"设备列表看起来还在"的表象，
            // 换不回任何一台设备的鉴权能力。用它去覆盖一份读到的（或读不出的）真存储，
            // 只会把「可重试的降级」变成「必须重新配对」。
            if (records.isEmpty() && !degraded && !file.exists() && settings.pairedFingerprints.isNotEmpty()) {
                migrateFromSettings()
            }
        }
    }

    /** 从磁盘重新加载（一般仅在测试/外部改动后调用）。 */
    fun load() {
        synchronized(lock) {
            records = loadInternal()
        }
    }

    /** 强制落盘当前缓存。 */
    fun save() {
        synchronized(lock) {
            saveInternal()
        }
    }

    /**
     * 关停：**同步**落一次盘，然后收掉 [touch] 的异步落盘线程。
     *
     * 由 `BackendService.stopAllComponents()` 在停完 HTTP 服务之后调用（那时已无人再发请求）。
     * 顺序不能反：先落盘再停线程，否则队列里那次 flush 会被 [java.util.concurrent.ExecutorService.shutdownNow]
     * 掐在半路 —— 而"落盘被掐在半路"正是 2026-09-08 事故的形态（见类注释）。
     *
     * `awaitTermination` 只等 [SHUTDOWN_WAIT_MS]：这条路径挂在服务停止流程上，
     * 不能为了几十秒的 `lastSeen` 把停止动作卡住；超时就 `shutdownNow`，
     * 反正真正要紧的数据上一行已经同步写完了。
     *
     * 关停后单例引用一并摘掉，服务重启时 [getInstance] 会建一个带活线程的新实例。
     */
    fun shutdown() {
        // 注意：save 在锁内、awaitTermination 在锁外。若持锁等待，队列里那个
        // `synchronized(lock) { saveInternal() }` 永远拿不到锁，直接死锁到超时。
        synchronized(lock) {
            stopped = true
            // 降级态且内存里一条记录都没有时**绝不落盘**。
            //
            // 否则会把 `[]` 原子写进真文件：下次启动它能被正常解析成"空存储"、degraded 归零，
            // 于是所有客户端拿到的是 444（你没配对过）而不是可重试的 503（我暂时读不出来）——
            // 等于亲手把「读不出来」洗成「确实没配对过」，用户被迫重新输密码配对，
            // 正是 2026-09-08 事故要避免的那个结果。
            //
            // 降级期间只有带真实新数据的写（配对/解绑等 upsert/remove）才允许落盘，
            // 那时 records 非空，走的是下面的正常分支。
            if (degraded && records.isEmpty()) {
                AppLogger.w(TAG, "降级态且无记录，跳过关停落盘（保留磁盘上的 $FILE_NAME 与 .bak 供排查）")
            } else {
                saveInternal()
            }
        }
        flushExecutor.shutdown()
        try {
            if (!flushExecutor.awaitTermination(SHUTDOWN_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                flushExecutor.shutdownNow()
                AppLogger.w(TAG, "flush 线程未在 ${SHUTDOWN_WAIT_MS}ms 内结束，已强制关停")
            }
        } catch (e: InterruptedException) {
            flushExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        detachInstance(this)
        AppLogger.i(TAG, "PairedDeviceStore shutdown（已同步落盘）")
    }

    /** 返回全部配对记录（副本，避免外部修改内部缓存）。 */
    fun list(): List<PairedDeviceRecord> = synchronized(lock) { records.toList() }

    /**
     * 写入/刷新一条记录；已存在则保留 createdAt 并刷新 deviceName/lastSeen。
     *
     * 空串参数一律表示「本次不更新该字段」，保留已有值。这条规则对 [pubKey] / [tokenHash]
     * 尤其重要：像 [rename] 这类只改一个字段的调用不能把设备凭据抹掉。
     *
     * @param deviceName 设备名；传空串**保留**已有值，无已有值时回退为 fingerprint。
     * @param hwId 硬件标识；传空串**保留**已有值，避免一次不带 hwId 的登录把已知标识抹成空、
     *   导致后续 [findByHwId] 去重失效。
     * @param pubKey 设备身份公钥（SPKI base64）；空串保留原值。
     * @param tokenHash 该设备 token 的 SHA-256；空串保留原值。
     */
    fun upsert(
        fingerprint: String,
        deviceName: String = "",
        lastSeen: Long,
        hwId: String = "",
        pubKey: String = "",
        tokenHash: String = ""
    ) {
        synchronized(lock) {
            val existing = records.firstOrNull { it.fingerprint == fingerprint }
            val record = PairedDeviceRecord(
                fingerprint = fingerprint,
                deviceName = deviceName.ifBlank { existing?.deviceName.orEmpty() }.ifBlank { fingerprint },
                lastSeen = lastSeen,
                createdAt = existing?.createdAt?.takeIf { it > 0 } ?: lastSeen,
                hwId = hwId.ifBlank { existing?.hwId.orEmpty() },
                pubKey = pubKey.ifBlank { existing?.pubKey.orEmpty() },
                tokenHash = tokenHash.ifBlank { existing?.tokenHash.orEmpty() }
            )
            records = (records.filterNot { it.fingerprint == fingerprint } + record)
                .sortedBy { it.createdAt }
            saveInternal()
        }
    }

    /**
     * 刷新设备活跃时间（鉴权成功后每请求调用）。
     *
     * 与 [upsert] 的区别是**落盘节流 + 异步落盘**：`lastSeen` 只用于配对界面展示"最近活跃"，
     * 每个 API 请求都写一次 `paired_devices.json` 会在手机闪存上造成完全无意义的写放大。
     * 这里内存立即更新，磁盘最多每 [TOUCH_FLUSH_INTERVAL_MS] 写一次，且**不在调用线程上写** ——
     * 这条路径跑在 Netty 的单 worker 线程上，任何阻塞 IO 都会直接变成所有请求的延迟抖动。
     * 进程被杀导致最后几十秒的 lastSeen 丢失是完全可接受的。
     */
    fun touch(fingerprint: String, nowMs: Long = System.currentTimeMillis()) {
        var needFlush = false
        synchronized(lock) {
            val index = records.indexOfFirst { it.fingerprint == fingerprint }
            if (index < 0) return
            val mutable = records.toMutableList()
            mutable[index] = mutable[index].copy(lastSeen = nowMs)
            records = mutable
            if (nowMs - lastTouchFlushMs >= TOUCH_FLUSH_INTERVAL_MS) {
                lastTouchFlushMs = nowMs
                needFlush = true
            }
        }
        if (needFlush) {
            // [shutdown] 之后 executor 已关停，再提交会抛 RejectedExecutionException 打断
            // 调用方（Netty worker 线程上的鉴权路径）。stopped 已在 shutdown 里同步落过盘，
            // 此刻丢掉的只是 lastSeen。
            if (stopped) return
            runCatching { flushExecutor.execute { synchronized(lock) { saveInternal() } } }
        }
    }

    /**
     * 按 token 哈希查找设备 —— 每请求鉴权的入口（[com.ufi_axis_core.api.middleware.AuthMiddleware]）。
     *
     * 空/空白 tokenHash 一律不匹配：旧记录的 tokenHash 是空串，若参与匹配，
     * 一个不带 Authorization 头的请求就会"匹配"上旧记录。
     */
    fun findByTokenHash(tokenHash: String): PairedDeviceRecord? = synchronized(lock) {
        if (tokenHash.isBlank()) return@synchronized null
        records.firstOrNull { it.tokenHash == tokenHash && it.tokenHash.isNotBlank() }
    }

    /** 按指纹查找。 */
    fun findByFingerprint(fingerprint: String): PairedDeviceRecord? = synchronized(lock) {
        if (fingerprint.isBlank()) return@synchronized null
        records.firstOrNull { it.fingerprint == fingerprint }
    }

    /**
     * 按硬件标识查找记录（去重合并入口）。
     *
     * 空/空白 hwId 一律不匹配——历史记录（部署本特性前配对的设备）hwId 为空串，
     * 若参与匹配会把所有旧记录误判为“同一台硬件”而错误合并。
     *
     * @return 首条 hwId 相同且非空的记录；无匹配返回 null。
     */
    fun findByHwId(hwId: String): PairedDeviceRecord? = synchronized(lock) {
        if (hwId.isBlank()) return@synchronized null
        records.firstOrNull { it.hwId == hwId && it.hwId.isNotBlank() }
    }

    /** 删除一条记录；存在并删除返回 true。 */
    fun remove(fingerprint: String): Boolean = synchronized(lock) {
        val before = records.size
        records = records.filterNot { it.fingerprint == fingerprint }
        val removed = records.size != before
        if (removed) saveInternal()
        removed
    }

    /** 重命名一条记录；存在返回 true。 */
    fun rename(fingerprint: String, newName: String): Boolean = synchronized(lock) {
        val index = records.indexOfFirst { it.fingerprint == fingerprint }
        if (index < 0) return@synchronized false
        val updated = records[index].copy(deviceName = newName.ifBlank { fingerprint })
        val mutable = records.toMutableList()
        mutable[index] = updated
        records = mutable
        saveInternal()
        true
    }

    /** 全部指纹列表。 */
    fun fingerprints(): List<String> = synchronized(lock) { records.map { it.fingerprint } }

    /** 是否没有任何配对记录。 */
    fun isEmpty(): Boolean = synchronized(lock) { records.isEmpty() }

    /** 清空全部记录（解除全部配对时调用）。 */
    fun clear() {
        synchronized(lock) {
            records = emptyList()
            saveInternal()
        }
    }

    /** 读取系统设备名（Settings.Global.DEVICE_NAME），读不到/为空返回空串（由调用方回退 deviceId）。 */
    fun systemDeviceName(): String {
        return runCatching {
            android.provider.Settings.Global.getString(
                context.contentResolver,
                android.provider.Settings.Global.DEVICE_NAME
            )
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: ""
    }

    /**
     * 读存储。**任何"读不出来"的情况都不许静音**：这份文件是全部设备凭据，
     * 读空一次的代价是所有客户端被判未配对并自毁本地 token（2026-09-08 事故，见类注释）。
     *
     * 优先级：真文件 → `.bak`（上一次落盘前的好内容）→ 降级。
     * 走到降级时把坏文件挪成 `.corrupt` 留证：既方便排查，也防止它被下一次落盘悄悄盖掉。
     */
    private fun loadInternal(): List<PairedDeviceRecord> {
        val target = file
        val dir = target.parentFile
        val bak = File(dir, FILE_NAME + BAK_SUFFIX)
        if (!target.exists()) {
            // 真文件没了但 `.bak` 在：只可能是原子替换在「删目标 → 改名」之间被打断。
            val fromBak = parseOrNull(bak)
            if (fromBak != null) {
                AppLogger.e(TAG, "$FILE_NAME 缺失，已从 $FILE_NAME$BAK_SUFFIX 恢复 ${fromBak.size} 条配对记录")
                degraded = false
                return fromBak
            }
            // `.corrupt` 是上一次降级留下的证据：说明"配对存储存在过但读不出来"，
            // 这与"从没配对过"是两回事，不能让调用方把它当空存储对待。
            if (File(dir, FILE_NAME + CORRUPT_SUFFIX).exists()) {
                degraded = true
                AppLogger.e(TAG, "$FILE_NAME 缺失且存在 $FILE_NAME$CORRUPT_SUFFIX：仍按降级态处理")
                return emptyList()
            }
            degraded = false
            return emptyList()
        }
        val parsed = parseOrNull(target)
        if (parsed != null) {
            degraded = false
            return parsed
        }
        AppLogger.e(TAG, "$FILE_NAME 为空或无法解析（典型成因：非原子写在截断后被打断）")
        val fromBak = parseOrNull(bak)
        if (fromBak != null) {
            AppLogger.e(TAG, "已从 $FILE_NAME$BAK_SUFFIX 恢复 ${fromBak.size} 条配对记录")
            degraded = false
            return fromBak
        }
        val corrupt = File(dir, FILE_NAME + CORRUPT_SUFFIX)
        if (corrupt.exists()) corrupt.delete()
        val moved = target.renameTo(corrupt)
        degraded = true
        AppLogger.e(
            TAG,
            "配对存储不可读（$FILE_NAME 与 $FILE_NAME$BAK_SUFFIX 均失败）," +
                (if (moved) "坏文件已移到 $FILE_NAME$CORRUPT_SUFFIX 备查" else "坏文件移动失败，仍在原地") +
                "；进入降级态：鉴权按「存储不可用、可重试」回复，不得判客户端未配对"
        )
        return emptyList()
    }

    /**
     * 解析一个候选文件。返回 null = 不存在 / 空白 / 解析失败（三者都当"不可用"）。
     *
     * 空白也算失败：正常的空存储写出来是 `[]`，长度 0 的文件只可能是截断残留。
     */
    private fun parseOrNull(candidate: File): List<PairedDeviceRecord>? {
        if (!candidate.exists()) return null
        return try {
            val text = candidate.readText()
            if (text.isBlank()) null else json.decodeFromString<List<PairedDeviceRecord>>(text)
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析 ${candidate.name} 失败: ${e.message}")
            null
        }
    }

    /**
     * 落盘：**原子 + 持久**。
     *
     * 2026-09-08 事故的直接修复点。原实现是 `file.writeText(text)` —— 先把真文件截断到 0
     * 再写，且不 fsync。[touch] 每 30s 就在后台线程做一次全量重写（记录里含 pubKey/tokenHash，
     * 不只是 lastSeen），进程死在截断与写完之间就留下半截 JSON，下次启动读不出任何设备。
     *
     * 现在的步骤，顺序有意义：
     * 1. 全量写 `.tmp`，`flush()` 后 `fd.sync()` —— 只有 sync 返回才代表字节真的到了闪存，
     *    否则断电时"改名成功但内容还在 page cache"，得到的仍是一个空/半截文件；
     * 2. 把当前真文件**复制**成 `.bak`（复制而不是改名：改名会让真文件短暂消失）；
     * 3. `renameTo` 原子替换（Android/Linux 同目录 → rename(2)，读者只会看到旧内容或新内容）。
     *
     * 注意：这里没法在 Java 层 fsync 目录项，极端断电下可能丢掉"改名"这一步本身 ——
     * 但那种情况下读到的是**完整的旧文件**，凭据仍然可用，正是我们要的失败模式。
     */
    private fun saveInternal() {
        val target = file
        val dir = target.parentFile
        val tmp = File(dir, FILE_NAME + TMP_SUFFIX)
        val bak = File(dir, FILE_NAME + BAK_SUFFIX)
        try {
            val text = json.encodeToString(records)
            dir?.mkdirs()
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (target.exists()) {
                runCatching { target.copyTo(bak, overwrite = true) }
                    .onFailure { AppLogger.w(TAG, "备份 $FILE_NAME$BAK_SUFFIX 失败: ${it.message}") }
            }
            if (!tmp.renameTo(target)) {
                // 同目录 rename(2) 正常必成功，返回 false 只可能是权限/占用等异常。
                // 先删目标再试一次；仍失败就保留 .tmp 与 .bak 原样并报 ERROR，
                // **绝不**回退成截断写去"兜底"——那正是本次事故的成因。
                if (!target.delete() || !tmp.renameTo(target)) {
                    AppLogger.e(
                        TAG,
                        "原子替换 $FILE_NAME 失败（tmp 保留=${tmp.exists()}，bak 可用=${bak.exists()}）"
                    )
                    return
                }
            }
            // 写成功即意味着文件内容与内存一致，降级态到此结束（见 [degraded]）。
            // 但**空记录不算恢复**：降级时内存本就是空的，拿空内容盖掉真文件再宣布"已恢复"，
            // 等于把"读不出来"洗成"确实没配对过"。只有写入非空记录才清降级标记。
            if (records.isNotEmpty()) degraded = false
        } catch (e: Exception) {
            AppLogger.e(TAG, "保存 $FILE_NAME 失败（真文件未被改动）: ${e.message}", e)
        }
    }

    private fun migrateFromSettings() {
        val now = System.currentTimeMillis()
        val pairedAt = settings.pairedAt
        val base = if (pairedAt > 0) pairedAt else now
        val migrated = settings.pairedFingerprints.map { fp ->
            PairedDeviceRecord(
                fingerprint = fp,
                deviceName = fp,
                lastSeen = base,
                createdAt = base
            )
        }
        if (migrated.isNotEmpty()) {
            records = migrated
            saveInternal()
            AppLogger.i(TAG, "已从旧 pairedFingerprints 迁移 ${migrated.size} 台设备记录到 paired_devices.json")
        }
    }

    companion object {
        private const val TAG = "PairedDeviceStore"
        private const val FILE_NAME = "paired_devices.json"

        /** 原子替换用的中间文件；只在 [saveInternal] 里出现，读路径不认它。 */
        private const val TMP_SUFFIX = ".tmp"

        /** 上一次落盘前的好内容；真文件读不出来时的第一顺位恢复源。 */
        private const val BAK_SUFFIX = ".bak"

        /** 读不出来的坏文件挪到这里备查（同时作为「存在过存储」的降级标记）。 */
        private const val CORRUPT_SUFFIX = ".corrupt"

        /** [touch] 的落盘节流间隔。 */
        private const val TOUCH_FLUSH_INTERVAL_MS = 30_000L

        /** [shutdown] 等异步落盘线程收尾的上限（服务停止流程不能被这件事卡住）。 */
        private const val SHUTDOWN_WAIT_MS = 2_000L

        @Volatile
        private var instance: PairedDeviceStore? = null

        /**
         * 进程内唯一实例（与 [AppSettings.getInstance] 同一套写法）。
         *
         * 为什么必须唯一：`ComponentFactory.build()` 可以在没 `reset()` 的情况下跑第二遍
         * （它自己就会打 "called while already built" 的 WARN）。每个实例都有自己的
         * `Any()` 锁、自己的内存快照和自己那条活着的 flush 线程 ——
         * 旧实例排队中的一次落盘会拿**它自己的旧快照**做全量重写，
         * 把新实例刚写进去的记录（比如刚配对成功的设备）整条盖掉，而两把锁互不相识，拦不住。
         */
        fun getInstance(context: Context, settings: AppSettings): PairedDeviceStore {
            return instance ?: synchronized(this) {
                instance ?: PairedDeviceStore(context.applicationContext, settings).also { instance = it }
            }
        }

        /**
         * 关停并摘掉进程内实例（服务停止路径调用，见 `BackendService.stopAllComponents`）。
         * 没有实例时是空操作 —— 停止流程不该关心存储有没有被建过。
         */
        fun shutdownInstance() {
            instance?.shutdown()
        }

        /** [shutdown] 回调：只有当前实例才能摘掉自己，避免摘掉重启后新建的那个。 */
        private fun detachInstance(store: PairedDeviceStore) {
            synchronized(this) {
                if (instance === store) instance = null
            }
        }
    }
}

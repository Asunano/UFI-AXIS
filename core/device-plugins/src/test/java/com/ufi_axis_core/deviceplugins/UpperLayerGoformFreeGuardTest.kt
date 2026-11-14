package com.ufi_axis_core.deviceplugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 守门测试：**上层五处的 main 源码里不许出现 `Goform` 这个词**（2026-09-25 批 A4）。
 *
 * 五处 = `core/api`、`core/collector`、`core/scheduler`、`core/controller`、`core/src`。
 * 判据只看**真代码** —— 注释与字符串字面量都先剥掉（见 [codeOnly]）。
 *
 * ## 它守的是哪条接缝
 *
 * 「换设备时改的只有装配层那一行」这个目标（见 `DeviceHub` 的类 KDoc）成立的前提是：
 * 上层只认 `:core:device-spi` 的契约类型（`DeviceTransport` / `DeviceHub` / 五个域接口），
 * 具体协议（goform）只出现在 `:core:goform` 与 `:core:device-plugins` 两个模块里。
 * 这条纪律挡不住编译器（那些类都是 public），所以只能挡在 CI 上 —— 做法与
 * `GoformTransportVisibilityGuardTest` 完全一致，**不重新发明**。
 *
 * ## 为什么必须先剥注释
 *
 * `GoformTransportVisibilityGuardTest` 的第一版直接 `readText().contains(...)`，
 * 结果把 `ComponentFactory` 里那句「本文件**不许**写这个类型名」的注释算成了越界 ——
 * 一句解释边界的话被当成了违反边界。注释里写符号名不仅正常，而且是必要的
 * （不写下来，下一个人不知道这条边界是刻意的）。所以本类的判据是**代码引用**，
 * 不是文字出现。
 *
 * ## 为什么还要剥字符串字面量（本类比那个旧守门多的一步）
 *
 * 五处里有大量 `"Goform client not available"` 这种**日志/响应文案**，以及
 * `/api/device/goform` 这种 route 路径。它们不是类型引用，算越界只会逼人去改对外文案。
 * 所以字符串字面量整段剥掉 —— 但**保留字符串模板 `${'$'}{...}` 里的表达式**：
 * 那里面是真代码（例：`DataScheduler` 的性能日志里就有 `GoformQoS.queryTotalPermits`）。
 *
 * ## allowlist 不是兜底，是一张欠账清单
 *
 * [ALLOWED] 逐文件、逐标识符列出**当前仍然存在**的引用，每一组都写了理由。
 * 断言是**逐文件精确相等**（不是「包含」）：
 * - 多出一个没登记的标识符 → 红（新的越界）；
 * - 登记了但代码里已经没有了 → 也红（欠账还完了要把它从清单里删掉）。
 * 后者刻意保留：一张只增不减的豁免清单一年后就没人看得懂了。
 *
 * ## ⚠ 收尾必须用 `--rerun-tasks`
 *
 * 本类扫的是**别的 module 的源码文件**，Gradle 只把 `:core:device-plugins` 自己的
 * 源码/依赖当作 `testDebugUnitTest` 的输入。所以「有人在 `:core:api` 里写了越界引用」这件事，
 * 在本模块没变的情况下任务会报 `UP-TO-DATE`，**一声不响地不跑**。
 * 本仓因为这一条出过「97/97 全绿」的假绿（同一个坑记在
 * `GoformTransportVisibilityGuardTest` 的文件头）。
 * **纪律**：阶段收尾时用 `.\gradlew.bat :core:device-plugins:testDebugUnitTest --rerun-tasks`
 * 强制跑一次，别只看增量结果。
 *
 * ## 为什么用 `assumeTrue` 而不是直接失败
 *
 * 本类依赖「能从测试工作目录找到仓库根」。换构建方式 / 在别的环境跑时可能找不到 ——
 * 那种情况下**跳过**比**假失败**好：一条永远红的守门测试，最后一定被人加 `@Ignore`。
 */
class UpperLayerGoformFreeGuardTest {

    /**
     * 受本测试管辖的五处 main 源码根（相对仓库根）。
     *
     * `core/src` 写成 `core/src/main` 而不是 `core`：后者会把 `core/goform`、
     * `core/device-plugins` 这两个**允许**认识 goform 的模块一起扫进来。
     * 各子模块路径互不包含，所以不会有文件被扫两遍。
     */
    private val scannedRoots = listOf(
        "core/api/src/main",
        "core/collector/src/main",
        "core/scheduler/src/main",
        "core/controller/src/main",
        "core/src/main",
    )

    /**
     * 欠账清单：`仓库相对路径` → `该文件代码里仍然出现的、含 Goform 的标识符`。
     *
     * 分组理由（逐条都是「为什么这一项现在还不能消掉」）：
     *
     * ### A. `:core:common` 的公共设施，名字里带 Goform 但与插件化无关
     *
     * `GoformQoS`（并发许可 + 传输层缓存）与 `GoformSessionLog`（会话日志）住在
     * `:core:common`，不在 `:core:goform` 里。它们是**后端到设备的 HTTP 出向通道**的
     * QoS 与日志设施，任何协议的设备都要用。消掉它们靠的是改名（协议中立的名字），
     * 不是搬代码 —— 而改名会动 `AppSettings` 的配置键 `qos_goform_*` 与
     * `/api/config` 的对外字段，属于独立的一次对外变更，不在 A 系列批次里。
     *
     * ### B. `AppSettings` 的配置项名（`qosGoform*`）
     *
     * 同 A：配置键是对外契约（app / web 都在读），改名要连两端一起改。
     *
     * ### C. `DeviceTransport.updateGoformPassword`
     *
     * 这是**契约层 `:core:device-spi` 自己**的方法名（`DeviceTransport` 的 14 个方法之一），
     * 不是 goform 模块的类型。上层调它一点问题都没有，只是名字还带着协议味。
     * 同 A：消掉它 = 契约层改名，独立一批。
     *
     * ### D. 各模块自己的局部名字（不是 goform 模块的类型）
     *
     * `GoformNetworkInfo`（`:core:api` 自己的 DTO）、`GoformApplyResult` /
     * `InvalidGoformConfig` / `validateGoformSettings` / `persistGoformSettings`
     * （`PairingManager` 的私有 sealed class 与私有方法，说的是「设备后台连接配置」）、
     * `collectGoformTraffic` / `preFetchedGoform` / `collectLayer1GoformFields` /
     * `resolveGoformSmsId`（私有方法名 / 参数名）、`etGoform*` / `btnToggleGoformPwd`
     * （`SettingsActivity` 的控件字段，对应的 `R.id` 在布局 XML 里）。
     * 它们**不产生任何跨模块耦合**，纯粹是命名。留着不影响换设备。
     *
     * ### E. 真正的跨模块耦合 —— 这才是要还的债
     *
     * - `GoformWifiClient`（`RouteContext` / `DataHub` / `ComponentGraph`）：批 C2 起
     *   **已经还完** —— WiFi 读侧（状态 / 已连客户端 / 二维码 / 接入控制名单）与
     *   `setAccessControlList` 整体迁进 `WifiControl`，`AclEntry` / `AclSnapshot` 两个
     *   协议无关的类型搬到 `:core:device-spi`，三处上层消费点改收 `deviceHub.wifi`。
     *   写侧在批 A2b 就迁完了。
     * - `GoformSmsClient`（`DataScheduler` / `SmsController` / `LocalSmsDelivery` /
     *   `LocalSmsChannel` / `ComponentGraph`）：批 C1 起**已经还完** ——
     *   sms 域整体迁进 `SmsControl`，4 个上层消费点改收契约层类型，
     *   `ComponentGraph.NetworkGraph` 不再有 `smsClient` 字段。
     * - `ComponentFactory` 的 8 个：`ZteGoformAdapter` + `GoformClient` + 6 个
     *   `Goform*Client` 的构造。批 A3 本来要清掉它们，**没做成** —— 当时的原因
     *   （装配层必须把 `GoformWifiClient` / `GoformSmsClient` 交给上层消费点）
     *   到批 C2 已经不存在了：现在装配层只是**造**这 6 个客户端，然后一并交给
     *   `ZteGoformAdapter`，没有任何一个再流向上层。所以这 8 项留着的理由只剩
     *   「`new` 还没搬进插件」这一件事本身 —— `transport as? GoformClient` 那句转型
     *   要等插件交出整条链（A3 的收束）才删得掉，**本文件因此仍然是 8 项**。
     */
    private val ALLOWED: Map<String, Set<String>> = mapOf(
        // ── A / B / C / D：命名遗留，无跨模块耦合 ──
        "core/api/src/main/java/com/ufi_axis_core/api/routes/QoSRoutes.kt" to
            setOf("GoformQoS"),
        "core/api/src/main/java/com/ufi_axis_core/api/routes/ConfigRoutes.kt" to
            setOf("GoformQoS", "qosGoformQueryMax", "qosGoformSetMax"),
        "core/api/src/main/java/com/ufi_axis_core/api/GoformNetworkInfo.kt" to
            setOf("GoformNetworkInfo"),
        "core/api/src/main/java/com/ufi_axis_core/api/routes/DashboardRoutes.kt" to
            setOf("GoformNetworkInfo"),
        "core/api/src/main/java/com/ufi_axis_core/api/pairing/PairingManager.kt" to
            setOf(
                "GoformApplyResult",
                "InvalidGoformConfig",
                "validateGoformSettings",
                "persistGoformSettings",
            ),
        "core/api/src/main/java/com/ufi_axis_core/api/routes/PairingRoutes.kt" to
            setOf("InvalidGoformConfig", "updateGoformPassword"),
        "core/api/src/main/java/com/ufi_axis_core/api/routes/DeviceRoutes.kt" to
            setOf("updateGoformPassword"),
        "core/collector/src/main/java/com/ufi_axis_core/collector/signal/SignalCollector.kt" to
            setOf("preFetchedGoform", "collectLayer1GoformFields"),
        "core/controller/src/main/java/com/ufi_axis_core/controller/sms/SmsController.kt" to
            setOf("resolveGoformSmsId"),
        "core/scheduler/src/main/java/com/ufi_axis_core/core/scheduler/DataScheduler.kt" to
            setOf("GoformQoS", "collectGoformTraffic"),
        "core/src/main/java/com/ufi_axis_core/SettingsActivity.kt" to
            setOf(
                "etGoformIp",
                "etGoformPort",
                "etGoformPassword",
                "btnToggleGoformPwd",
                "InvalidGoformConfig",
            ),
        "core/src/main/java/com/ufi_axis_core/service/BackendService.kt" to
            setOf("GoformQoS", "GoformSessionLog"),
        "core/api/src/main/java/com/ufi_axis_core/api/DataHub.kt" to
            setOf("GoformNetworkInfo"),

        // ── E：装配层的跨模块耦合（2026-09-25 批 A3 已全部还完）──
        //
        // 原来这里登记了 `ComponentFactory.kt` 的 8 个符号：`ZteGoformAdapter` +
        // `GoformClient` + 6 个 `Goform*Client` 的构造。它们全部是装配层为了
        // `new` 六个协议客户端而不得不认识的具体协议类型。
        //
        // 批 A3 把客户端的 `new` 搬进了插件（`ZteF50Plugin.createAdapter` →
        // `ZteGoformAdapter` 的公开构造函数），`transport as? GoformClient` 那句
        // 向下转型也随之移进了插件。装配层现在只认 `DeviceTransport` 与 `DeviceAdapter`
        // 两个契约类型 —— **`Goform` 这个词在 `ComponentFactory.kt` 的非注释代码里出现 0 次**。
        //
        // 历史：
        //  - 批 A3 第一次核（两轮前）停手 —— 当时 WiFi 读侧（`GoformWifiClient`）与
        //    sms 域（`GoformSmsClient`）还没有域接口，装配层必须把它们交给 5 个上层消费点。
        //  - 批 C1（`76b0ebd`）还完 sms 那一半。
        //  - 批 C2（`afb94d0`）还完 WiFi 那一半。
        //  - 批 A3（本批）把最后 8 项从此处删除。E 组清零。
    )

    private fun repoRoot(): File? {
        var dir: File? = File("").absoluteFile
        repeat(8) {
            val d = dir ?: return null
            if (File(d, "settings.gradle.kts").isFile) return d
            dir = d.parentFile
        }
        return null
    }

    /** 五处 main 源码下所有 Kotlin 文件（排除 build 产物与历史残留 build.old 前缀目录）。 */
    private fun scannedSources(root: File): List<File> =
        scannedRoots
            .map { File(root, it) }
            .filter { it.isDirectory }
            .flatMap { dir ->
                dir.walkTopDown()
                    .onEnter { d ->
                        val n = d.name
                        n != "build" && !n.startsWith("build.old")
                    }
                    .filter { it.isFile && it.extension == "kt" }
                    .toList()
            }

    /**
     * 只留**真代码**：注释、字符串字面量、字符字面量全部剥掉，
     * 但保留字符串模板里的表达式（那是代码）。
     *
     * 逐条口径：
     * - 行注释与块注释都剥。Kotlin 的块注释**可以嵌套**，所以按深度计数 ——
     *   见到第一个星号加斜杠就收工会把后面的真代码一起吃掉。
     * - 三引号与单引号字符串都剥内容，只把 `${'$'}{ ... }` 与 `${'$'}ident`
     *   两种模板里的部分留下来。理由见类 KDoc：日志文案不算越界，模板里的表达式算。
     * - 字符字面量（形如单引号包一个字符）整段剥掉。必须处理它：代码里存在
     *   「单引号里包一个双引号」的写法，不认它会把后面整段代码当成字符串。
     */
    private fun codeOnly(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        var blockDepth = 0
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else ' '
            when {
                blockDepth > 0 -> when {
                    c == '/' && next == '*' -> { blockDepth++; i += 2 }
                    c == '*' && next == '/' -> { blockDepth--; i += 2 }
                    else -> i++
                }
                c == '/' && next == '*' -> { blockDepth = 1; i += 2 }
                c == '/' && next == '/' -> { while (i < src.length && src[i] != '\n') i++ }
                src.startsWith("\"\"\"", i) -> i = scanLiteral(src, i + 3, "\"\"\"", out)
                c == '"' -> i = scanLiteral(src, i + 1, "\"", out)
                c == '\'' -> i = skipCharLiteral(src, i + 1)
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /**
     * 从 [from] 开始扫一段字符串字面量，把模板表达式追加到 [out]，返回字面量结束后的下标。
     *
     * @param terminator 单引号那种是一个双引号，三引号那种是三个双引号。
     */
    private fun scanLiteral(src: String, from: Int, terminator: String, out: StringBuilder): Int {
        var i = from
        while (i < src.length) {
            if (src.startsWith(terminator, i)) return i + terminator.length
            val c = src[i]
            when {
                // 转义只在单引号字符串里有意义；三引号里没有转义，但 `\` 后面跟别的字符
                // 也不会误吃 terminator，所以这里统一处理不会出错。
                c == '\\' && terminator == "\"" -> i += 2
                // 单引号字符串不跨行。真出现未闭合（只可能是前面哪一步解析跑偏了）时
                // 在行尾收手，而不是把文件剩下的全当字面量吃掉 —— 那会让主断言静默变绿。
                c == '\n' && terminator == "\"" -> return i + 1
                c == '$' && i + 1 < src.length && src[i + 1] == '{' -> {
                    var depth = 1
                    var j = i + 2
                    while (j < src.length && depth > 0) {
                        when (src[j]) {
                            '{' -> depth++
                            '}' -> depth--
                        }
                        if (depth > 0) out.append(src[j])
                        j++
                    }
                    out.append(' ')
                    i = j
                }
                c == '$' && i + 1 < src.length && (src[i + 1].isLetter() || src[i + 1] == '_') -> {
                    var j = i + 1
                    while (j < src.length && (src[j].isLetterOrDigit() || src[j] == '_')) {
                        out.append(src[j]); j++
                    }
                    out.append(' ')
                    i = j
                }
                else -> i++
            }
        }
        return i
    }

    /** 跳过一个字符字面量，返回结束后的下标（[from] 指向左单引号之后的第一个字符）。 */
    private fun skipCharLiteral(src: String, from: Int): Int {
        var i = from
        if (i < src.length && src[i] == '\\') i += 2 else i++
        if (i < src.length && src[i] == '\'') i++
        return i
    }

    /** 该文件代码里所有**含 `Goform` 的完整标识符**。 */
    private fun goformIdentifiers(f: File): Set<String> =
        IDENTIFIER_WITH_GOFORM.findAll(codeOnly(f.readText()))
            .map { it.value }
            .toSet()

    @Test
    fun `上层五处的代码里不许出现 Goform`() {
        val root = repoRoot()
        assumeTrue("找不到仓库根（settings.gradle.kts），跳过跨模块扫描", root != null)
        val sources = scannedSources(root!!)

        // 自检：扫描器本身得真的扫到东西，否则下面的断言是恒真式空转。
        // 2026-09-25 实测五处共 129 个 .kt（api 77 / collector 5 / scheduler 5 / controller 30 / core 12）。
        assertTrue("五处 main 源码下扫到的 .kt 文件数异常偏少：${sources.size}", sources.size >= 100)

        val actual = sources
            .associate { f -> f.relativeTo(root).path.replace(File.separatorChar, '/') to goformIdentifiers(f) }
            .filterValues { it.isNotEmpty() }

        // 逐文件精确比对（理由见类 KDoc 的 allowlist 一节：新增要红，还完账也要红）。
        val diff = (actual.keys + ALLOWED.keys).sorted().mapNotNull { path ->
            val found = actual[path].orEmpty()
            val allowed = ALLOWED[path].orEmpty()
            if (found == allowed) null else {
                val extra = (found - allowed).sorted()
                val gone = (allowed - found).sorted()
                buildString {
                    append(path)
                    if (extra.isNotEmpty()) append("\n    新增越界（要么迁走，要么登记进 ALLOWED 并写理由）：$extra")
                    if (gone.isNotEmpty()) append("\n    已经不在代码里（把它从 ALLOWED 里删掉）：$gone")
                }
            }
        }

        assertEquals(
            "五处（core/api、core/collector、core/scheduler、core/controller、core/src）的 main 代码里" +
                "不许出现 Goform —— 具体协议只许住在 :core:goform 与 :core:device-plugins。" +
                "⚠ 增量构建不会因别的 module 改动而重跑本类，收尾请用 --rerun-tasks。\n" +
                diff.joinToString("\n"),
            emptyList<String>(),
            diff,
        )
    }

    @Test
    fun `剥注释与字符串字面量的实现本身可用`() {
        // 这条是给上面那条用例的**判据本身**做的自检：剥错了会让主断言恒绿。
        // 每一行都对应 GoformTransportVisibilityGuardTest 踩过或本类新增的坑。
        val src = """
            // 行注释里写 GoformAlpha 不算越界
            /* 块注释里写 GoformBeta /* 嵌套 GoformGamma */ 仍在注释里 */
            import com.ufi_axis_core.controller.goform.GoformDelta
            val msg = "字面量里的 GoformEpsilon 不算"
            val tpl = "模板里的 ${'$'}{GoformZeta.permits} 要算"
            val simple = "简单模板 ${'$'}GoformEta 也算"
            val ch = '"'
            val after = GoformTheta
        """.trimIndent()
        val code = codeOnly(src)
        val ids = IDENTIFIER_WITH_GOFORM.findAll(code).map { it.value }.toSet()
        assertEquals(
            "剥注释/字面量的结果不对（剥出来的代码是：$code）",
            setOf("GoformDelta", "GoformZeta", "GoformEta", "GoformTheta"),
            ids,
        )
    }

    private companion object {
        /**
         * 含 `Goform` 的完整标识符。
         *
         * 匹配整个标识符而不是只匹配 `Goform` 这 6 个字符：报错信息里要能直接看出
         * 越界的是哪个符号（`GoformWifiClient` 与 `updateGoformPassword` 是两回事）。
         * 大小写敏感 —— 小写的 `goform`（包名段、配置键、route 路径）**不在判据里**。
         */
        private val IDENTIFIER_WITH_GOFORM = Regex("[A-Za-z0-9_]*Goform[A-Za-z0-9_]*")
    }
}

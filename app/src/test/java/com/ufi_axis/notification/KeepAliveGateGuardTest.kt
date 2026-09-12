package com.ufi_axis.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「前台服务保活」闸门的**回归护栏**（2026-09-05）。
 *
 * ## 被守护的不变量
 * 状态栏那条常驻通知（id 9001 / channel `ufi_notify_keepalive`）**存在 ⟺ 用户开着
 * 「后台守护 → 前台服务保活」**。两个方向都要成立：关着不许有；开着必须有。
 * 本仓明文规则：UI 默认值必须与所有闸门一致；权限型开关显示状态 = 本地开关 AND 系统放行。
 *
 * ## 这次回归是怎么发生的（两轮）
 * 1. 上午之前：`NotifyBootReceiver` 有一次口径变更（其 KDoc 原文记载：「此前要求
 *    `guard_enabled && guard_foreground_keepalive_enabled` 双开关同时为 true，两者默认都是
 *    false，导致开机自启实际从不生效」）。那次**只该放宽 `guard_enabled`**（它管的是
 *    WorkManager 周期任务，与前台服务无关），却把保活键一起摘掉了，于是 `shouldRun` 只看
 *    「系统通知推送」—— 用户没开保活，通知栏仍常驻一条通知（关不掉）。
 * 2. 上午的修复过了头：判据被写成 `alert_notification_enabled && 保活键`。前者默认 false，
 *    于是**开也开不起来**——用户打开保活，开关显示为开、服务当场被自己的闸门挡掉。
 *    这条 AND 的理由（「总闸关掉时服务跑着也发不出任何通知」）与代码不符：`:ufi_notify`
 *    是短信 / 验证码推送的唯一订阅方，那两条只看 `sms_notification_enabled`（默认 true）。
 *    最终口径：**闸门只看保活键**（真值表见 `:app:data` 的 `KeepAliveGateTest`）。
 *
 * ## 为什么是源码级断言
 * 与 `NavInsetHandoffGuardTest` / `CapsuleRegressionGuardTest` 同一理由：这些不变量没有
 * 纯 JVM 的运行期出口 —— 取值要 `Context` + `SharedPreferences`，`NotifyPrefs.isNotifyProcess()`
 * 还会调 `android.app.Application.getProcessName()`（JVM 单测里未 mock 会直接抛），
 * 服务生命周期与 Compose 状态更要真实 Android 运行时。这里退一步做**源码不变量护栏**：
 * 纯文件读取 + 正则，零依赖、毫秒级，红灯时附上原因。
 * 判据**本体**（两个布尔 → 该不该跑）已抽成纯函数 `KeepAliveGate.shouldRun`，由真值表单测覆盖。
 */
class KeepAliveGateGuardTest {

    // ── 被守护的源文件（相对仓库根） ────────────────────────────────────────

    private val notifyServicePath =
        "app/src/main/java/com/ufi_axis/notification/NotifyService.kt"

    private val notifyPrefsPath =
        "app/data/src/main/java/com/ufi_axis/data/notification/NotifyPrefs.kt"

    private val configClientPath =
        "app/data/src/main/java/com/ufi_axis/data/notification/NotificationConfigClient.kt"

    private val dispatchReceiverPath =
        "app/data/src/main/java/com/ufi_axis/data/notification/NotifyDispatchReceiver.kt"

    private val backgroundGuardScreenPath =
        "app/feature-settings/src/main/java/com/ufi_axis/ui/screens/BackgroundGuardScreen.kt"

    private val notificationsGuardScreenPath =
        "app/feature-settings/src/main/java/com/ufi_axis/ui/screens/NotificationsGuardScreen.kt"

    /**
     * 定位源文件。
     *
     * Gradle 单测的工作目录默认是**模块目录**（`app`），IDE / 其他 runner 可能从仓库根启动，
     * 所以从当前目录逐级上溯直到能命中以仓库根为基准的相对路径。
     */
    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError(
            "定位不到源文件 '$relative'（起点：${File(".").absolutePath}）。" +
                "若模块路径发生变化，请同步更新本测试中的相对路径。"
        )
    }

    /**
     * 剥掉块注释与行注释，只留**可执行代码**。
     *
     * 这几个文件把「为什么必须两个开关都判」写进了大段注释，键名与函数名在注释里出现多次，
     * 直接全文搜索必然误报。
     */
    private fun executableCode(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    /** 取 `fun <name>(` 之后到下一个成员函数声明之前的代码片段。 */
    private fun functionBody(code: String, name: String): String {
        val start = code.indexOf("fun $name(")
        assertTrue("在源码中找不到 fun $name(（可能已被重命名或删除）", start >= 0)
        val rest = code.substring(start)
        val next = Regex("""\n\s{4}(override|private|internal)?\s*fun\s""")
            .find(rest, startIndex = 1)?.range?.first
        return if (next != null) rest.substring(0, next) else rest
    }

    // ── ① 闸门只看保活键，且必须委托到那份唯一纯判据 ─────────────────────────

    /**
     * `NotifyPrefs.keepAliveShouldRun` 必须：
     * - 读 `KEY_GUARD_FOREGROUND_KEEPALIVE`（默认显式 false）；
     * - 委托给纯函数 `KeepAliveGate.shouldRun`（真值表单测在 `:app:data`）；
     * - **不得**把「系统通知推送」`&&` 进判定结果。
     *
     * 少了保活键 → 用户把保活关着也会有常驻通知（「假开关」）。
     * 把总闸 AND 进来 → 用户打开保活却起不来服务，且 `:ufi_notify` 负责的短信 / 验证码
     * 实时推送一起失效（那两条只看 `sms_notification_enabled`，与告警总闸无关）。
     */
    @Test
    fun keepAliveGate_mustDependOnlyOnKeepAliveSwitch() {
        val body = functionBody(executableCode(source(notifyPrefsPath)), "keepAliveShouldRun")
        assertTrue(
            "NotifyPrefs.keepAliveShouldRun 必须读 KEY_GUARD_FOREGROUND_KEEPALIVE ——" +
                "常驻通知是用户可见代价，必须由「前台服务保活」开关授权，" +
                "少了它就是「没开保活却有常驻通知」（2026-09-05 上午实测事故）。",
            body.contains("NotificationCenter.KEY_GUARD_FOREGROUND_KEEPALIVE")
        )
        assertTrue(
            "保活键的默认值必须显式写 false（与 MIRRORED_BOOL_KEYS / NotificationConfigDto " +
                "/ NotificationConfigSync.readLocal 逐字一致），不得省略或写成 true。",
            Regex("""KEY_GUARD_FOREGROUND_KEEPALIVE\s*,\s*false""").containsMatchIn(body)
        )
        assertTrue(
            "判据本体必须委托给纯函数 `KeepAliveGate.shouldRun(...)` —— " +
                "它是唯一有 JVM 真值表单测兜着的地方（`:app:data` KeepAliveGateTest）。",
            body.contains("KeepAliveGate.shouldRun")
        )
        assertFalse(
            "闸门不得对两个开关取 `&&`：「系统通知推送」默认 false，AND 进来的直接后果是" +
                "「用户打开保活，开关显示为开、服务却被自己的闸门挡掉」" +
                "（2026-09-05 上午的二次回归）。它只决定服务跑起来之后发不发告警类通知。",
            body.contains("&&")
        )
        assertFalse(
            "闸门里不许再出现 `||`（历史上没出现过，写死以防有人用它绕过授权）。",
            body.contains("||")
        )
    }

    /**
     * `NotifyService.shouldRun` 必须委托到那份唯一判据，不得自己再拼一遍。
     *
     * 两份拷贝迟早分叉 —— 这次回归的成因正是「每个入口各自复查开关」，其中一处漏了保活键。
     */
    @Test
    fun notifyServiceShouldRun_mustDelegateToSingleGate() {
        val code = executableCode(source(notifyServicePath))
        assertTrue(
            "NotifyService.shouldRun 必须写成 `NotifyPrefs.keepAliveShouldRun(context)`：" +
                "判据只能有一份（`:app:data` 的 NotificationConfigClient 也要用它，" +
                "而它不能反向依赖 `:app`）。",
            Regex("""fun\s+shouldRun\s*\(\s*context\s*:\s*Context\s*\)\s*:\s*Boolean\s*=\s*NotifyPrefs\.keepAliveShouldRun\(\s*context\s*\)""")
                .containsMatchIn(code)
        )
        assertFalse(
            "NotifyService.shouldRun 又回到「只读 KEY_ALERT_NOTIF 一个键」的形态 —— " +
                "这正是「用户没开保活、通知栏仍有一条固定通知」的直接成因。",
            Regex("""fun\s+shouldRun[^=]*=\s*NotifyPrefs\.switchOn\([^)]*KEY_ALERT_NOTIF[^)]*\)\s*\n""")
                .containsMatchIn(code)
        )
    }

    // ── ② 所有启动入口都必须过闸门 ──────────────────────────────────────────

    /** `NotifyService.startKeepAlive` 是闸门收口点，必须自己判一次。 */
    @Test
    fun notifyServiceStartKeepAlive_mustGateInternally() {
        val body = functionBody(executableCode(source(notifyServicePath)), "startKeepAlive")
        assertTrue(
            "NotifyService.startKeepAlive 内部必须有 `if (!shouldRun(context)) return`：" +
                "闸门收口在这里，调用点（Application / 开机 / 设置页 / core 回显）才不用各自复查 —— " +
                "「各自复查」就是这次漏掉保活键的方式。",
            Regex("""if\s*\(\s*!\s*shouldRun\(\s*context\s*\)\s*\)""").containsMatchIn(body)
        )
    }

    /** `:app:data` 侧的 `NotificationConfigClient.startKeepAlive` 是另一条入口，同样必须判。 */
    @Test
    fun configClientStartKeepAlive_mustGateInternally() {
        val body = functionBody(executableCode(source(configClientPath)), "startKeepAlive")
        assertTrue(
            "NotificationConfigClient.startKeepAlive 内部必须有 " +
                "`if (!NotifyPrefs.keepAliveShouldRun(context)) return`：" +
                "它是 NotificationsGuardScreen 与 NotificationConfigSync 用的入口，" +
                "不经过 `:app` 那个同名函数。",
            Regex("""if\s*\(\s*!\s*NotifyPrefs\.keepAliveShouldRun\(\s*context\s*\)\s*\)""")
                .containsMatchIn(body)
        )
    }

    /**
     * `NotifyDispatchReceiver` 转发连接参数时**不得**无条件 `startForegroundService`。
     *
     * 它绕过 `startKeepAlive` 直接起服务，于是每次重新配对 / 改 IP 端口都会把常驻通知复活。
     */
    @Test
    fun dispatchReceiver_mustNotStartServiceUnconditionally() {
        val code = executableCode(source(dispatchReceiverPath))
        val onReceive = functionBody(code, "onReceive")
        val gateAt = onReceive.indexOf("NotifyPrefs.keepAliveShouldRun(context)")
        val startAt = onReceive.indexOf("startForegroundService(")
        assertTrue(
            "NotifyDispatchReceiver.onReceive 必须先判 `NotifyPrefs.keepAliveShouldRun(context)` " +
                "再转发连接参数：它走的是裸 startForegroundService，不经过 startKeepAlive 的闸门。",
            gateAt >= 0
        )
        assertTrue("找不到 startForegroundService(，本条护栏的前提已不成立。", startAt >= 0)
        assertTrue(
            "闸门必须排在 startForegroundService 之前（当前 gate@$gateAt / start@$startAt）。",
            gateAt < startAt
        )
    }

    // ── ③ 服务自查自停：堵住 START_STICKY 重建 ──────────────────────────────

    /**
     * `onCreate` **不得**发常驻通知。
     *
     * 两条理由（都在源码注释里）：
     * 1. 纯 `bind`（`BIND_AUTO_CREATE`）也会触发 onCreate，而「后台守护」页一进页面就绑 ——
     *    在 onCreate 发通知等于「打开那一页就凭空多一条常驻通知」，与保活开关无关；
     * 2. onCreate 早于 `onStartCommand` 的快照落地，此刻 `mirror_` 里可能还是上一次关闭时
     *    写下的 false。若在 onCreate 据此自停，「用户重新打开保活」这条最常见路径会失败
     *    （页面正绑着服务 → `startForegroundService` 只触发 onStartCommand，而实例已判死），
     *    还会因为「FGS 启动请求未被 startForeground 满足」而抛
     *    ForegroundServiceDidNotStartInTimeException。
     */
    @Test
    fun onCreate_mustNotPostOngoingNotification() {
        val body = functionBody(executableCode(source(notifyServicePath)), "onCreate")
        assertFalse(
            "onCreate 不得调用 enterForeground()：纯 bind 也会走到这里（「后台守护」页一进页面就绑），" +
                "会凭空多一条常驻通知；且此刻 mirror_ 里的保活开关可能是过期的 false，" +
                "在这里自停会让「重新打开保活」失败。常驻通知只在 onStartCommand 里发。",
            body.contains("enterForeground()")
        )
    }

    /**
     * `onStartCommand` 必须：`enterForeground()` → `applySnapshot` → 判闸门 → 不过则撤通知 + 自停
     * 且返回 `START_NOT_STICKY`。
     *
     * - enterForeground 必须最先：满足系统「startForegroundService 后 5s 内必须 startForeground」
     *   的契约，哪怕紧接着就撤掉；
     * - 闸门必须排在 applySnapshot 之后：本进程读的是自己那份 `mirror_` 副本，
     *   这条 Intent 携带的快照可能刚把保活开关刷成 false（或刚刷成 true）。
     */
    @Test
    fun onStartCommand_mustEnterForegroundThenRecheckAfterSnapshot() {
        val body = functionBody(executableCode(source(notifyServicePath)), "onStartCommand")
        val fgAt = body.indexOf("enterForeground()")
        val snapshotAt = body.indexOf("NotifyPrefs.applySnapshot(")
        val gateAt = body.indexOf("shouldRun(this)")
        assertTrue(
            "onStartCommand 里找不到 enterForeground() —— 常驻通知必须在这里发（唯一发射点），" +
                "否则 startForegroundService 的 5s 契约无人满足。",
            fgAt >= 0
        )
        assertTrue("onStartCommand 里找不到 NotifyPrefs.applySnapshot(。", snapshotAt >= 0)
        assertTrue(
            "onStartCommand 必须自查 `shouldRun(this)`：START_STICKY 的系统重建路径不带 Intent、" +
                "也不查开关，只有服务自己能兜住 —— 少了这一句，进程被回收后系统重建服务就会" +
                "重新发出常驻通知。",
            gateAt >= 0
        )
        assertTrue(
            "顺序错了：enterForeground() 必须排在最前（当前 fg@$fgAt / snapshot@$snapshotAt），" +
                "否则抛 ForegroundServiceDidNotStartInTimeException。",
            fgAt < snapshotAt
        )
        assertTrue(
            "顺序错了：applySnapshot 必须排在 shouldRun 复查之前" +
                "（当前 snapshot@$snapshotAt / gate@$gateAt），否则判的是过期镜像。",
            snapshotAt < gateAt
        )
        assertTrue(
            "自查未通过时必须返回 START_NOT_STICKY —— 返回 START_STICKY 会让系统再把它重建回来。",
            body.contains("START_NOT_STICKY")
        )
    }


    /**
     * `NotifyService` 必须存在 `stopForeground(STOP_FOREGROUND_REMOVE)`。
     *
     * 两个理由：
     * 1. 只要还有客户端 `BIND_AUTO_CREATE` 绑着（「后台守护」页在页内一直绑着），
     *    `stopService` 不销毁服务、`onDestroy` 不跑，通知会挂到用户离开页面；
     * 2. 部分国产 ROM 上 FGS 通知不随服务/进程消失，必须显式 REMOVE。
     */
    @Test
    fun notifyService_mustRemoveOngoingNotificationOnStop() {
        val code = executableCode(source(notifyServicePath))
        val hits = Regex("""stopForeground\(\s*STOP_FOREGROUND_REMOVE\s*\)""").findAll(code).count()
        assertTrue(
            "NotifyService 必须调用 `stopForeground(STOP_FOREGROUND_REMOVE)`（当前 $hits 处）：" +
                "少了它，关掉保活开关后那条常驻通知会残留（绑定中不销毁服务 / ROM 残留）。",
            hits >= 1
        )
        assertTrue(
            "自停路径必须同时 `stopSelf()`，只撤通知会留下一个空转的服务实例。",
            code.contains("stopSelf()")
        )
        assertTrue(
            "必须保留 AIDL `setForegroundKeepAlive` 实现 —— 它是「页面仍绑定时立即撤回常驻通知」" +
                "的唯一通道（stopService 在绑定期间不会销毁服务）。",
            Regex("""fun\s+setForegroundKeepAlive\(""").containsMatchIn(code)
        )
    }

    // ── ④ UI 不许是「假开关」 ───────────────────────────────────────────────

    /**
     * 「前台服务保活」开关的 `checked` 必须 = 本地开关 **AND** 系统放行，且 `ON_RESUME` 重检。
     *
     * 同文件的无障碍开关 2026-08-20 已经踩过 `remember{}` 一次性快照的坑（从系统设置返回后
     * 状态不刷新）并修好，唯独这个开关当时没修。
     */
    @Test
    fun keepAliveSwitch_checkedMustAndSystemPermission() {
        val code = executableCode(source(backgroundGuardScreenPath))
        assertTrue(
            "保活开关的 `checked` 必须写成 `keepAliveEnabled && notifAllowed`：" +
                "没有通知权限时 startForeground 根本起不来（本页自己也只弹 Toast 不启动服务），" +
                "只读 prefs 就是「写着已开启、实际没生效」的假开关。",
            Regex("""checked\s*=\s*keepAliveEnabled\s*&&\s*notifAllowed""").containsMatchIn(code)
        )
        assertFalse(
            "保活开关的 `checked` 又变回只读 prefs 的 `keepAliveEnabled` —— 假开关回归。",
            Regex("""checked\s*=\s*keepAliveEnabled\s*,""").containsMatchIn(code)
        )
        assertTrue(
            "系统放行必须用最终判据 `areNotificationsEnabled()`（不是只查 checkSelfPermission：" +
                "用户可能在系统设置里关掉本应用通知，权限仍是 granted）。",
            code.contains("areNotificationsEnabled()")
        )
        assertTrue(
            "必须在 `Lifecycle.Event.ON_RESUME` 重检权限：remember{} 一次性快照会让用户" +
                "从系统设置返回后看到旧状态（同页无障碍开关 2026-08-20 修过同一个坑）。",
            code.contains("Lifecycle.Event.ON_RESUME") && code.contains("notifAllowed = readNotifAllowed()")
        )
        assertTrue(
            "ON_RESUME 还必须重读 prefs（core 回显 / 别的页面 / 别端都可能改掉它），" +
                "否则页面显示会与真源分叉。",
            Regex("""keepAliveEnabled\s*=\s*viewModel\.backgroundGuard\.isForegroundKeepAlive\(\)""")
                .containsMatchIn(code)
        )
    }

    /**
     * 打开保活时必须**先写 prefs 再** `startKeepAlive` —— 闸门读的就是那个 pref。
     *
     * 顺序反了会被自己刚要打开的开关挡掉（改动 2 给 startKeepAlive 加了前置判断之后，
     * 旧顺序会变成「点开关没反应」）。
     */
    @Test
    fun keepAliveSwitch_mustWritePrefBeforeStartingService() {
        val code = executableCode(source(backgroundGuardScreenPath))
        val flat = code.replace(Regex("""\s+"""), " ")
        val writeAt = flat.indexOf("viewModel.backgroundGuard.setForegroundKeepAlive(true)")
        val startAt = flat.indexOf("NotificationConfigClient.startKeepAlive(context)")
        assertTrue("找不到 setForegroundKeepAlive(true)。", writeAt >= 0)
        assertTrue("找不到 NotificationConfigClient.startKeepAlive(context)。", startAt >= 0)
        assertTrue(
            "顺序错了：`setForegroundKeepAlive(true)` 必须排在 `startKeepAlive(context)` 之前" +
                "（当前 write@$writeAt / start@$startAt）—— startKeepAlive 的闸门读的正是这个 pref。",
            writeAt < startAt
        )
        assertTrue(
            "关闭分支必须经 AIDL `configClient.setForegroundKeepAlive(false)` 让服务当场撤回常驻通知：" +
                "本页在页内一直 BIND_AUTO_CREATE 绑着服务，单靠 stopKeepAlive(stopService) 不会销毁它。",
            flat.contains("configClient.setForegroundKeepAlive(false)")
        )
    }

    /**
     * 「系统通知推送」（告警总闸）**不得**停掉保活服务。
     *
     * 那是拿 A 开关去停 B 开关授权的服务：用户明明还开着「前台服务保活」（那一页也还显示为开），
     * 常驻通知却消失；并且 `:ufi_notify` 是短信 / 验证码推送的唯一订阅方，那条实时通道
     * 会一起断掉。总闸只决定「发不发告警类通知」（`maybeNotifyNewAlerts` /
     * `NotificationCenter.notify` 自己会 return），不决定「守护进程要不要活着」。
     */
    @Test
    fun alertMasterSwitch_mustNotStopKeepAliveService() {
        val code = executableCode(source(notificationsGuardScreenPath))
        assertFalse(
            "NotificationsGuardScreen 里不许出现 `NotificationConfigClient.stopKeepAlive` —— " +
                "关掉「系统通知推送」不等于用户撤销了「前台服务保活」的授权。",
            code.contains("NotificationConfigClient.stopKeepAlive")
        )
        assertTrue(
            "总闸变化后必须把开关快照推给 `:ufi_notify`（`NotifyDispatchReceiver.dispatchSwitchSnapshot`）：" +
                "那个进程读开关只认自己那份 mirror_ 副本，不推的话它会继续按旧值发（或不发）告警通知。",
            code.contains("NotifyDispatchReceiver.dispatchSwitchSnapshot(context)")
        )
    }

    /**
     * 关闭方向的收口点 `NotificationConfigClient.stopKeepAlive` 必须先同步镜像再 `stopService`。
     *
     * 唯一还没堵住的「关掉之后又自己回来」窗口：用户在 `:ufi_notify` 已被杀时关开关 ——
     * `stopService` 对一个不存在的服务是 no-op，而镜像里留着上次的 `true`，
     * 之后任何启动路径（START_STICKY 重建 / 开机 / 重新配对）自查都会通过，常驻通知复活。
     * `NotifyDispatchReceiver` 在 Manifest 里声明于该进程，广播能把它拉起来落地镜像。
     */
    @Test
    fun stopKeepAlive_mustSyncMirrorBeforeStoppingService() {
        val body = functionBody(executableCode(source(configClientPath)), "stopKeepAlive")
        val syncAt = body.indexOf("NotifyDispatchReceiver.dispatchSwitchSnapshot(context)")
        val stopAt = body.indexOf("stopService(")
        assertTrue(
            "stopKeepAlive 必须调用 `NotifyDispatchReceiver.dispatchSwitchSnapshot(context)`：" +
                "否则通知进程不在线时关开关，镜像会留着陈旧的 true。",
            syncAt >= 0
        )
        assertTrue("找不到 stopService(，本条护栏的前提已不成立。", stopAt >= 0)
        assertTrue(
            "顺序错了：镜像同步必须排在 stopService 之前（当前 sync@$syncAt / stop@$stopAt）。",
            syncAt < stopAt
        )
    }
}

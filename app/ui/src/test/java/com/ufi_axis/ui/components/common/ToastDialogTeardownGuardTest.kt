package com.ufi_axis.ui.components.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「弹窗销毁期间不得改动 decor / 窗口状态」这条不变量的**回归护栏**。
 *
 * ## 被守护的崩溃（2026-09-05，Xiaomi 2211133C / API 36，benchmark 包）
 * ```
 * java.lang.NullPointerException: Attempt to invoke virtual method
 *   'void android.view.View.dispatchDetachedFromWindow()' on a null object reference
 *     at android.view.ViewGroup.dispatchDetachedFromWindow(ViewGroup.java:4018)
 *     at android.view.ViewRootImpl.doDie / die
 *     at android.view.WindowManagerGlobal.removeViewLocked / removeView
 *     at android.view.WindowManagerImpl.removeViewImmediate
 *     at android.app.Dialog.dismissDialog / dismiss
 *     at androidx.compose.ui.window.AndroidDialog_androidKt$Dialog$1$1$…$onDispose$1.dispose()
 *     at androidx.compose.runtime.DisposableEffectImpl.onForgotten()
 *     at androidx.compose.runtime.internal.RememberEventDispatcher.dispatchRememberObservers()
 *     at androidx.compose.runtime.CompositionImpl.applyChangesInLocked / applyChanges
 *     at androidx.compose.runtime.Recomposer$runRecomposeAndApplyChanges$2…
 *     at androidx.compose.ui.platform.AndroidUiFrameClock…doFrame
 * ```
 *
 * ## 机制（一句话）
 * `Dialog.dismiss()` 会**同步**走到 `DecorView.dispatchDetachedFromWindow()`，该方法在
 * 循环前把 `mChildrenCount` / `mChildren` 快照进局部变量；循环到 Compose 的 `DialogLayout`
 * 时会触发 `disposeComposition()`，于是弹窗子组合的 `onDispose` 全部在**遍历中途**执行。
 * 那时若有人 `removeView`（`UfiToastOverlay` 摘 toast），`ViewGroup.removeViewInternal`
 * 会把**同一个数组**前移并把末位置成 `null` —— 而循环用的 `count` 还是旧的，
 * 下一轮 `children[count-1]` 读到 null 直接 NPE。崩溃栈里看不到任何 App 帧，
 * 正是这个「空洞在别的分支里被埋下」的指纹。
 *
 * ## 为什么是源码级断言
 * 要在行为层面复现，必须真实 `WindowManager` + Compose UI Test（`:app:ui` 只声明了
 * `testImplementation(libs.junit)`，见 `app/ui/build.gradle.kts`）。这条不变量的本质是
 * 「某个函数体里**不许出现**某类调用」，源码断言恰好能精确表达，且零依赖、毫秒级。
 * 与本模块既有的 `CapsuleRegressionGuardTest` 同一范式。
 */
class ToastDialogTeardownGuardTest {

    private val toastOverlayPath =
        "src/main/java/com/ufi_axis/ui/components/common/UfiToastOverlay.kt"

    private val dialogShellPath =
        "src/main/java/com/ufi_axis/ui/components/common/UfiDialogShell.kt"

    /**
     * 定位模块源码文件。
     *
     * Gradle 单测的工作目录默认是模块目录（`app/ui`），IDE / 其他 runner 可能从仓库根启动，
     * 故逐级上溯并同时尝试 `app/ui/` 前缀。
     */
    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/ui/$relative"))) {
                if (candidate.isFile) return candidate.readText()
            }
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
     * 本轮修复把「为什么不能这么写」的反例（`removeView(toast)` / `clearFlags(...)`）
     * 大段写进了 KDoc，直接全文搜索必然误报；必须先去注释再断言。
     */
    private fun executableCode(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    /**
     * 用花括号配对截取某个作用域的**函数体 / lambda 体**。
     *
     * 不用「收尾 `}` 顶格」那套判据：本测试要看的函数都在 `object UfiToastOverlay` 内部
     * （缩进 4 空格），也要看嵌在 `DisposableEffect` 里的 `onDispose { … }` lambda。
     *
     * @param signaturePrefix 能唯一命中目标的签名前缀；从它之后的第一个 `{` 开始配对。
     */
    private fun bodyOf(code: String, signaturePrefix: String): String {
        val at = code.indexOf(signaturePrefix)
        if (at < 0) {
            throw AssertionError("源码中找不到 `$signaturePrefix` —— 它可能已被重命名或删除。")
        }
        val open = code.indexOf('{', at)
        if (open < 0) throw AssertionError("`$signaturePrefix` 之后找不到左花括号。")
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
            }
            i++
        }
        throw AssertionError("`$signaturePrefix` 的花括号不配对，无法截取函数体。")
    }

    // ── 不变量 1：popDialogHost 是「零触碰」的 ────────────────────────────────

    /**
     * `popDialogHost` 跑在窗口销毁流程**中途**，因此不得触碰视图树，也不得触碰将死的 Window。
     *
     * 具体封死三类写法：
     * - `removeView` / `dismissActive`：直接就是崩溃的那一行（改 children 数组）；
     * - `decorView`：`PhoneWindow.getDecorView()` 带 `installDecor()` 副作用，
     *   detach 中途重建 decor 是未定义行为；
     * - `.parent`：读 `toast.parent` 只为做归属判断，而这件事已由 `activeToastHost`
     *   在挂载时记下，销毁期不需要再问视图树。
     */
    @Test
    fun popDialogHost_mustNotTouchViewTreeOfDyingWindow() {
        val body = bodyOf(executableCode(source(toastOverlayPath)), "fun popDialogHost(window: Window)")

        for (forbidden in listOf("removeView", "dismissActive", "decorView", ".parent")) {
            assertFalse(
                "popDialogHost 里出现了 `$forbidden` —— 它跑在 DecorView." +
                    "dispatchDetachedFromWindow() 的遍历中途，改动 children / 重建 decor 会让" +
                    "平台快照的数组出现 null 空洞，下一轮迭代必然 NPE（见本测试类 KDoc 的真机栈）。",
                body.contains(forbidden)
            )
        }
        assertTrue(
            "popDialogHost 不再调用 releaseFromDyingHost() —— 销毁期的收尾必须走「只清引用」" +
                "那条路径，否则要么泄漏 activeToast，要么又回到 removeView。",
            body.contains("releaseFromDyingHost()")
        )
        assertTrue(
            "popDialogHost 的归属判据必须是 `activeToastHost === window`（挂载时记下的引用比较），" +
                "不得改回向视图树 / Window 提问。",
            body.contains("activeToastHost === window")
        )
    }

    // ── 不变量 2：releaseFromDyingHost 不做任何视图层级 / 布局改动 ─────────────

    /**
     * 销毁期收尾只允许做四件事：撤回调、停动画、清引用、把 `onDismiss` post 回主线程
     * （后者只改 Compose state，见 [releaseFromDyingHost_mustFireOnDismissExactlyOnce]）。
     *
     * 连 `visibility` / `requestLayout` 都封死：它们会向**已进入 `doDie()` 的** ViewRootImpl
     * 排一次新的 traversal，属于同一类「在窗口销毁流程里动它」的错误，只是没那么容易崩。
     */
    @Test
    fun releaseFromDyingHost_mustOnlyClearReferences() {
        val body = bodyOf(
            executableCode(source(toastOverlayPath)),
            "private fun releaseFromDyingHost()"
        )

        for (forbidden in listOf("removeView", "addView", "requestLayout", "visibility", "invalidate")) {
            assertFalse(
                "releaseFromDyingHost 里出现了 `$forbidden` —— 该函数的全部意义就是" +
                    "「宿主窗口正在死，什么都别动，只清自己的引用」。",
                body.contains(forbidden)
            )
        }
        assertTrue(
            "releaseFromDyingHost 必须清空 activeToast（否则单例会一直持有随窗口死亡的 View）。",
            body.contains("activeToast = null")
        )
        assertTrue(
            "releaseFromDyingHost 必须清空 activeToastHost，否则下一次 popDialogHost 会误判归属。",
            body.contains("activeToastHost = null")
        )
    }

    // ── 不变量 2b：releaseFromDyingHost 必须回调 onDismiss（且只回调一次）──────────

    /**
     * 「只清引用」不等于「不通知调用方」。
     *
     * `UfiToastHost` 是 `LaunchedEffect(toastMessage)`，契约是 `onDismiss` 里把 state 置 null；
     * 而 `ToastMessage` 是 data class ⇒ **同一条** toast 再触发时 key 相等、effect 不重启。
     * 因此宿主销毁时若不回调 `onDismiss`，调用方的 state 永久停在非 null，
     * **那条 toast 再也不显示**（2026-09-05 修复）。
     *
     * 回调只改 Compose state、不触碰视图树，所以与「将死窗口零触碰」不冲突；
     * 取值必须走 `consumeDismissCallback()`（取完即清）以保证幂等 ——
     * `popDialogHost` 与 View 自身的 detach 看门狗都可能进入本函数。
     */
    @Test
    fun releaseFromDyingHost_mustFireOnDismissExactlyOnce() {
        val code = executableCode(source(toastOverlayPath))

        assertTrue(
            "releaseFromDyingHost 没有回调 onDismiss —— 宿主销毁后调用方的 toastMessage " +
                "永久停在非 null，同一条 toast 再也弹不出来（LaunchedEffect key 不变）。" +
                "必须写成 `postDismiss(consumeDismissCallback())`。",
            bodyOf(code, "private fun releaseFromDyingHost()")
                .contains("postDismiss(consumeDismissCallback())")
        )
        assertTrue(
            "show 必须把 onDismiss 与 activeToast 同源存进单例（`activeToastOnDismiss = onDismiss`），" +
                "否则 releaseFromDyingHost / dismissActive 根本拿不到它。",
            bodyOf(code, "fun show(").contains("activeToastOnDismiss = onDismiss")
        )
        assertTrue(
            "consumeDismissCallback 必须取完即清（`activeToastOnDismiss = null`）—— " +
                "它是「onDismiss 恰好回调一次」的唯一幂等闸门。",
            bodyOf(code, "private fun consumeDismissCallback()")
                .contains("activeToastOnDismiss = null")
        )
    }

    // ── 不变量 3：dismissActive 仍是「窗口存活」时的正常收起路径 ────────────────

    /**
     * `dismissActive` 必须**保留** `removeView` —— 它是窗口存活时让 toast 真正从屏幕消失的
     * 唯一手段。本条防的是「为了修崩溃，把 removeView 一并删掉」的过度修复：
     * 那会让 toast 在正常路径下留在屏幕上不走。
     */
    @Test
    fun dismissActive_mustStillRemoveViewOnLiveWindow() {
        val body = bodyOf(executableCode(source(toastOverlayPath)), "fun dismissActive()")

        assertTrue(
            "dismissActive 丢了 removeView —— 窗口存活时不摘 View，toast 会永久留在屏幕上。",
            body.contains("removeView")
        )
        assertTrue(
            "dismissActive 必须同时清空 activeToastHost，否则宿主引用会比 View 活得更久。",
            body.contains("activeToastHost = null")
        )
    }

    // ── 不变量 4：宿主容器与 activeToastHost 必须同源写入 ─────────────────────

    /**
     * `show` 必须在**同一次决策**里拿到容器并记下它所属的窗口。
     *
     * 若分两次解析（旧 `resolveHostContainer` + 另算一次栈顶），中间插入的
     * `pushDialogHost` / `popDialogHost` 会让「记下的宿主」与「实际挂载的容器」不一致，
     * 于是 `popDialogHost` 的相等判据失真：要么漏清理（泄漏），要么误清理（toast 早退）。
     */
    @Test
    fun show_mustRecordHostWindowFromSameResolution() {
        val code = executableCode(source(toastOverlayPath))
        val body = bodyOf(code, "fun show(")

        assertTrue(
            "show 里找不到 `val host = resolveHost(context)` —— 容器与宿主窗口必须同源解析。",
            body.contains("resolveHost(context)")
        )
        assertTrue(
            "show 必须把宿主窗口记进 activeToastHost（`activeToastHost = host.window`）。",
            body.contains("activeToastHost = host.window")
        )
        assertFalse(
            "resolveHostContainer 又回来了 —— 它只返回容器、不返回窗口，会让两者漂移。",
            code.contains("resolveHostContainer")
        )
    }

    // ── 不变量 5：UfiDialogShell 的 onDispose 不改窗口状态 ────────────────────

    /**
     * `UfiDialogShell` 的 `DisposableEffect(window).onDispose` 与崩溃现场是**同一个栈帧区间**
     * （都在 `Dialog.dismiss()` → detach → `disposeComposition()` 里面），因此除了
     * 「把自己从 toast 宿主栈注销」之外，什么都不许做。
     *
     * 尤其封死原来那段 `clearFlags(FLAG_BLUR_BEHIND)` + `attributes = …`：它会走
     * `Window.setFlags` → `Dialog.onWindowAttributesChanged` → `WindowManager.updateViewLayout`
     * → `ViewRootImpl.setLayoutParams` + `scheduleTraversals`，即给一个已经进入 `doDie()`
     * 的 ViewRootImpl 排新 traversal；而它本身是死代码 —— `window` 与本弹窗同寿命，
     * 窗口销毁时模糊自然消失。
     */
    @Test
    fun dialogShellDispose_mustNotMutateDyingWindow() {
        val body = bodyOf(executableCode(source(dialogShellPath)), "onDispose {")

        assertTrue(
            "UfiDialogShell 的 onDispose 丢了 popDialogHost —— toast 宿主栈会残留已死窗口。",
            body.contains("popDialogHost")
        )
        for (forbidden in listOf(
            "clearFlags", "addFlags", "attributes =", "removeView",
            "setBackgroundDrawable", "setFormat", "setElevation"
        )) {
            assertFalse(
                "UfiDialogShell 的 onDispose 里出现了 `$forbidden` —— 该 onDispose 执行时" +
                    "窗口正在 removeViewImmediate/doDie 流程中，改写窗口属性会给已死的" +
                    "ViewRootImpl 排新 traversal（且对已销毁的窗口毫无意义）。",
                body.contains(forbidden)
            )
        }
    }

    // ── 不变量 6：宿主销毁看门狗必须存在，且注销必须早于 removeView ──────────────
    //
    // 上一轮修的是「loading toast 泄漏 Activity」：isLoading = true 的 toast 不注册
    // postDelayed 自动移除，若挂在 Activity.window.decorView 上而 Activity 被销毁
    // （旋转 / finish），exitAndRemove / dismissActive / popDialogHost 一条都不走
    // ⇒ 单例长期持有该 View 及其 Activity context。
    //
    // 修法是给 toast 自己挂一个 OnAttachStateChangeListener，detach 时走
    // releaseFromDyingHost()（只清引用、不碰视图树）。这引入了第二条不变量：
    // 正常移除路径必须**先注销 listener 再 removeView**，否则 removeView 触发的
    // detach 会与该路径自己的收尾叠加（onDismiss 回调两次），且 listener 自己
    // 变成新的泄漏源。
    //
    // 这两条此前只有 KDoc 保护，本测试把它们钉成红线。

    /**
     * 看门狗的三段式契约：**注册（show）→ 幂等判据（detach 回调）→ 先注销后摘 View（收尾路径）**。
     *
     * 缺任一段的后果：
     * - 不注册 ⇒ loading toast 在 Activity 销毁后仍被单例持有（原始泄漏复发）；
     * - detach 回调没有 `activeToast === v` 幂等判据 ⇒ 已被替换的旧 toast 也能触发收尾，
     *   把**新** toast 的 activeToast / onDismiss 清掉（toast 早退 + onDismiss 错配）；
     * - `clearHostDetachWatcher` 晚于 `removeView` ⇒ removeView 同步触发 detach，
     *   看门狗抢在本路径收尾前跑一次 releaseFromDyingHost（onDismiss 回调两次）。
     */
    @Test
    fun loadingToastDetachWatcher_mustBeRegisteredAndClearedBeforeRemoveView() {
        val code = executableCode(source(toastOverlayPath))

        // ① 字段本身：@Volatile —— 注册发生在主线程，dismissActive 是 public API 可能来自其他线程
        assertTrue(
            "找不到 `@Volatile private var hostDetachWatcher` 字段 —— 看门狗必须存在且以 " +
                "@Volatile 发布：注册在主线程，而 dismissActive 是 public API、不能假设调用线程，" +
                "非 volatile 时可能读到过期引用 ⇒ 漏注销（listener 泄漏 + 二次收尾）。",
            Regex("""@Volatile\s+private\s+var\s+hostDetachWatcher""").containsMatchIn(code)
        )

        // ② show 必须注册看门狗，且 detach 回调带幂等判据
        val showBody = bodyOf(code, "fun show(")
        assertTrue(
            "show 里没有 `addOnAttachStateChangeListener` —— isLoading = true 的 toast 不注册 " +
                "postDelayed 自动移除，Activity 被销毁（旋转 / finish）时 exitAndRemove / " +
                "dismissActive / popDialogHost 一条都不走 ⇒ 单例长期持有该 View 与 Activity context。",
            showBody.contains("addOnAttachStateChangeListener")
        )
        assertTrue(
            "show 里必须把看门狗存进 `hostDetachWatcher`，否则收尾路径无从注销它。",
            showBody.contains("hostDetachWatcher = detachWatcher")
        )
        assertTrue(
            "detach 回调缺少 `activeToast === v` 这类幂等判据 —— 没有它，已被下一次 show 替换掉的" +
                "旧 toast 在 detach 时也会走 releaseFromDyingHost，把**新** toast 的 " +
                "activeToast / activeToastHost / onDismiss 一并清掉（新 toast 早退 + onDismiss 错配）。",
            showBody.contains("activeToast === v")
        )

        // ③ clearHostDetachWatcher：先置 null 再摘 listener（顺序即幂等）
        val clearBody = bodyOf(code, "private fun clearHostDetachWatcher(")
        val nullAt = clearBody.indexOf("hostDetachWatcher = null")
        val removeListenerAt = clearBody.indexOf("removeOnAttachStateChangeListener")
        assertTrue(
            "clearHostDetachWatcher 里找不到 `hostDetachWatcher = null`（实测：$nullAt）—— " +
                "只摘 listener 不清字段会让单例一直持有监听器对象。",
            nullAt >= 0
        )
        assertTrue(
            "clearHostDetachWatcher 里找不到 `removeOnAttachStateChangeListener`（实测：" +
                "$removeListenerAt）—— 不摘 listener 时 View 与单例互相持有，泄漏源换了个名字。",
            removeListenerAt >= 0
        )
        assertTrue(
            "clearHostDetachWatcher 的顺序错了：必须**先** `hostDetachWatcher = null`（位置 " +
                "$nullAt）**再** removeOnAttachStateChangeListener（位置 $removeListenerAt）。" +
                "反过来的话，removeOnAttachStateChangeListener 之后字段仍非 null，" +
                "重入本函数会拿到已摘掉的 watcher 再摘一次，幂等闸门失效。",
            nullAt < removeListenerAt
        )

        // ④ 两条「窗口存活」收尾路径：注销必须早于 removeView
        for ((signature, label) in listOf(
            "fun dismissActive()" to "dismissActive",
            "private fun exitAndRemove(" to "exitAndRemove"
        )) {
            val body = bodyOf(code, signature)
            val clearAt = body.indexOf("clearHostDetachWatcher")
            val removeViewAt = body.indexOf("removeView(")
            assertTrue(
                "$label 里找不到 `clearHostDetachWatcher`（实测：$clearAt）—— 这条正常收尾路径" +
                    "自己会回调 onDismiss，不先注销看门狗就会被 removeView 触发的 detach 再收尾一次。",
                clearAt >= 0
            )
            assertTrue(
                "$label 里找不到 `removeView(`（实测：$removeViewAt）—— 窗口存活时不摘 View，" +
                    "toast 会永久留在屏幕上。",
                removeViewAt >= 0
            )
            assertTrue(
                "$label 的顺序错了：`clearHostDetachWatcher`（位置 $clearAt）必须早于 " +
                    "`removeView`（位置 $removeViewAt）。removeView 会**同步**派发 " +
                    "onViewDetachedFromWindow，此时 activeToast 仍 === 该 View ⇒ 看门狗抢先跑一次 " +
                    "releaseFromDyingHost，consumeDismissCallback 被它取走，" +
                    "本路径的收尾与之叠加成两次 onDismiss（且 activeToast 被提前清空）。",
                clearAt < removeViewAt
            )
        }
    }
}

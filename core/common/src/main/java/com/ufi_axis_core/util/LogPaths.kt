package com.ufi_axis_core.util

/**
 * core 侧「用户可见日志」路径的**唯一真源**。
 *
 * 为什么要有这个对象：这套路径此前在 core 里被手抄了 5 份（[AppLogger] 的日志根、
 * [DownloadLog]、[GoformSessionLog]、`UpdateManager` 的 core/watchdog 目录、
 * `InstallService` 的 install 目录）。取值本身是对的，但 5 份字面量意味着
 * 「换根目录」= 改 5 处，漏一处不会编译失败、也不会报错，只表现为
 * **日志散落在两个目录**（app 侧同样手抄了 3 份，已经这样漂移过）。
 * 所以这里只做一件事：把根目录与各组件子目录集中到一处，调用方一律拼接而不再写字面量。
 *
 * ## 完整布局
 *
 * ```
 * <base>/Download/UFI-AXIS/                      ← [appRoot]，应用根目录（update/ 放安装包，不属日志）
 * └── log/                                       ← [logRoot]，所有日志的根
 *     ├── core/                                  ← [Component.CORE]
 *     │   ├── <yyyy-MM-dd>/app.log(.1)           AppLogger 应用日志（按天分目录，5MB 轮转）
 *     │   ├── <yyyy-MM-dd>/at.log(.1)            AppLogger AT 指令日志
 *     │   ├── <yyyy-MM-dd>/error.log(.1)         AppLogger 错误日志
 *     │   ├── update.log                         UpdateManager 更新关键事件
 *     │   ├── launcher.log                       UpdateManager 启动/拉起调试
 *     │   ├── crash.log                          DownloadLog（崩溃堆栈镜像）
 *     │   └── goform-session.log                 GoformSessionLog 会话诊断
 *     ├── install/install.log                    ← [Component.INSTALL]，InstallService 安装诊断
 *     ├── watchdog/watchdog.log                  ← [Component.WATCHDOG]，ufi_update.sh 运行日志
 *     ├── keepalive/keepalive.log                ← [Component.KEEPALIVE]，ufi_keepalive.sh 运行日志
 *     └── _archive/                              ← [Component.ARCHIVE]，旧版散乱日志的归档目标
 * ```
 *
 * 同一个 `log/` 下还有 `app/` 与 `export/` 两支，那是**手机 APP 侧**的地盘
 * （`UfiLogPaths`，见下），core 一律不碰，所以本对象的 [Component] 里没有它们。
 *
 * `<base>` 有两种：[SDCARD_BASE]（默认）与 [EMULATED_BASE]。部分调用方按
 * 「emulated 优先、sdcard 兜底」的顺序探测可写目录（见 [dirCandidates]），
 * 因为不同 ROM 上只有一种布局能创建成功。
 *
 * ## 本对象管不到的三处副本 —— 改根目录时必须一起改
 *
 * 下面两个 shell 脚本各自持有一份同样的路径，**Kotlin 常量引用不到 shell**：
 * - `core/src/main/assets/shell/ufi_update.sh:20-21`（`LOG_DIR=.../log/watchdog`）
 * - `core/src/main/assets/shell/ufi_keepalive.sh:34-35`（`LOG_DIR=.../log/keepalive`）
 *
 * 这两处是 watchdog / keepalive 两个组件目录的**真正生产者**，Kotlin 这边（如
 * `UpdateManager` 读 watchdog.log 的 RESULT 行）只是消费者。改了这里不改脚本 =
 * 脚本继续往老目录写、Kotlin 去新目录读，表现为「更新结果永远读不到」。
 *
 * 第三处是 **app 侧的同名真源**：`app/data/src/main/java/com/ufi_axis/util/UfiLogPaths.kt`
 * （`UfiLogPaths.BRAND_DIR` / `LOG_DIR`，管 `log/app` 与 `log/export` 那两支及其子目录）。
 * app 与 core 各持一份是刻意的 —— 两个进程、两个模块，app 不该 import core 的实现类；
 * 那边的 KDoc 也反向点了本文件，两边互指就是为了别只改一边。代价同上：
 * 只改一边不会编译失败，只会让日志**静静地分家**，一半在新根一半在旧根。
 */
object LogPaths {

    /** 默认基路径（绝大多数 ROM 上的软链）。 */
    const val SDCARD_BASE = "/sdcard"

    /** 显式的多用户存储路径；`/sdcard` 不可用或不可写时的另一种布局。 */
    const val EMULATED_BASE = "/storage/emulated/0"

    /** 应用在公共存储里的目录名（相对 base）。放在公共 Download 下是为了让文件管理器能直接翻。 */
    private const val APP_DIR = "Download/UFI-AXIS"

    /** 日志根目录名（相对 [appRoot]）。 */
    private const val LOG_DIR = "log"

    /**
     * 日志按组件分子目录。目录名一旦改动，对应组件的历史日志就"消失"了（旧目录还在但没人写/读），
     * 所以这里是清单而不是任意字符串。
     */
    enum class Component(val dirName: String) {
        /** core 进程自身：AppLogger / DownloadLog / GoformSessionLog / UpdateManager。 */
        CORE("core"),

        /** APK 安装诊断（InstallService）。 */
        INSTALL("install"),

        /** 更新守护脚本 `ufi_update.sh`（Kotlin 侧只读它的 RESULT 行）。 */
        WATCHDOG("watchdog"),

        /** 保活脚本 `ufi_keepalive.sh`（Kotlin 侧不读写，列在此处是为了布局完整）。 */
        KEEPALIVE("keepalive"),

        /** 旧版散乱在应用根目录的日志文件的归档目标（见 `ComponentFactory.migrateLegacyLogsOnce`）。 */
        ARCHIVE("_archive")
    }

    /** 应用根目录，如 `/sdcard/Download/UFI-AXIS`。 */
    fun appRoot(base: String = SDCARD_BASE): String = "$base/$APP_DIR"

    /** 日志根目录，如 `/sdcard/Download/UFI-AXIS/log`。 */
    fun logRoot(base: String = SDCARD_BASE): String = "${appRoot(base)}/$LOG_DIR"

    /** 某组件的日志目录，如 `/sdcard/Download/UFI-AXIS/log/core`。 */
    fun dir(component: Component, base: String = SDCARD_BASE): String =
        "${logRoot(base)}/${component.dirName}"

    /** 某组件目录下的文件，如 `/sdcard/Download/UFI-AXIS/log/watchdog/watchdog.log`。 */
    fun file(component: Component, fileName: String, base: String = SDCARD_BASE): String =
        "${dir(component, base)}/$fileName"

    /**
     * 目录探测顺序：[EMULATED_BASE] 优先、[SDCARD_BASE] 兜底。
     *
     * 顺序不能反：调用方是「第一个能 mkdirs 成功的就用」，两者在正常 ROM 上指向同一份存储，
     * 但显式路径的可写性判定更可靠，因此先试它。
     */
    fun dirCandidates(component: Component): List<String> =
        listOf(dir(component, EMULATED_BASE), dir(component, SDCARD_BASE))

    /** 文件探测顺序，与 [dirCandidates] 同序。 */
    fun fileCandidates(component: Component, fileName: String): List<String> =
        dirCandidates(component).map { "$it/$fileName" }
}

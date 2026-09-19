package com.ufi_axis.app.navigation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 路由绑定护栏（2026-09-16）。
 *
 * ## 守的是什么
 * `MainNavGraph` 取页面内容的写法是 `screens[appRoute.route]?.invoke(entry, navController)`
 * —— **可空调用**。于是「在 `NavScreens.appRoutes` 里加了一条 DETAIL 路由，却忘了在
 * `AppScreens.rawAppScreens` 里登记对应页面」既不编译报错也不崩，只会得到一个白屏；
 * 跳转成功、返回键还能用，非常难当场看出来。
 *
 * 这条测试就是那个缺口的兜底：每一条 DETAIL 路由都必须能在绑定表里找到 key。
 *
 * ## 为什么是文本断言而不是直接引用两个符号
 * 与 `notification` 包下那几个 `GuardTest` 同一风格（KDoc 里不写 `notification` + 斜杠 + 星号，
 * 那会在块注释里再开一个注释，直接编译不过）：`rawAppScreens` 是 `private`、且构造它需要一个
 * `MainViewModel`（要 Android 环境）。这里只需要"名字对得上"，读源码文本足够，
 * 也不会因为要测这件事而把生产代码的可见性放宽。
 */
class RouteBindingGuardTest {

    private val navScreensPath =
        "app/ui/src/main/java/com/ufi_axis/ui/navigation/NavScreens.kt"
    private val appScreensPath =
        "app/src/main/java/com/ufi_axis/app/navigation/AppScreens.kt"

    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("定位不到源文件 '$relative'")
    }

    /**
     * `appRoutes` 里所有 DETAIL 路由的常量名。
     *
     * 两种书写形态都要认：单行 `AppRoute(Routes.X, TransitionType.DETAIL)`，
     * 与带 navArgument 的多行 `AppRoute(route = Routes.X, transition = TransitionType.DETAIL, ...)`。
     */
    private fun detailRouteNames(): Set<String> {
        val code = source(navScreensPath)
        val block = code.substringAfter("val appRoutes")
        val names = mutableSetOf<String>()
        Regex("""AppRoute\(\s*Routes\.(\w+)\s*,\s*TransitionType\.DETAIL""")
            .findAll(block)
            .forEach { names += it.groupValues[1] }
        Regex("""AppRoute\(\s*route\s*=\s*Routes\.(\w+)\s*,\s*transition\s*=\s*TransitionType\.DETAIL""")
            .findAll(block)
            .forEach { names += it.groupValues[1] }
        return names
    }

    /** `rawAppScreens` 里登记了页面的路由常量名。 */
    private fun boundRouteNames(): Set<String> {
        val code = source(appScreensPath)
        return Regex("""Routes\.(\w+)\s+to\s*\{""")
            .findAll(code)
            .map { it.groupValues[1] }
            .toSet()
    }

    @Test
    fun everyDetailRouteHasScreenBinding() {
        val declared = detailRouteNames()
        assertTrue("没解析到任何 DETAIL 路由，说明正则与 NavScreens 的写法脱节了", declared.isNotEmpty())
        val bound = boundRouteNames()
        val missing = (declared - bound).sorted()
        assertTrue(
            "这些 DETAIL 路由没有在 AppScreens.rawAppScreens 登记页面，进去会是白屏: $missing",
            missing.isEmpty()
        )
    }

    /** 反向：绑定表里不该留下已经删掉的路由（`media/center` 拆页时就差点漏删）。 */
    @Test
    fun everyBoundRouteStillExists() {
        val code = source(navScreensPath)
        val bound = boundRouteNames()
        val unknown = bound.filterNot { code.contains("const val $it") }.sorted()
        assertTrue("绑定表里这些路由常量在 Routes 里已经不存在: $unknown", unknown.isEmpty())
    }
}

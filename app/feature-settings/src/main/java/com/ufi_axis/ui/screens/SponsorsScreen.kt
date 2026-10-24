package com.ufi_axis.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.ufi_axis.data.api.RetrofitClient
import com.ufi_axis.ui.components.common.ToastMessage
import com.ufi_axis.ui.components.common.ToastType
import com.ufi_axis.ui.components.common.UfiDivider
import com.ufi_axis.ui.components.common.UfiGroupHeader
import com.ufi_axis.ui.components.common.UfiPageBackground
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiSettingsGroup
import com.ufi_axis.ui.components.common.UfiSettingsItem
import com.ufi_axis.ui.components.common.UfiToastHost
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.util.AppPreferences
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * **赞助与感谢**（2026-10-14 新建；同日拆页，本页只留名单）。
 *
 * ## 为什么单独一页（而不是继续用关于页那个弹窗）
 * 原来只有一颗「赞赏」按钮 + 一个滚动弹窗，里面是一张 600×1716 的赞赏码。现在多了一份
 * **会长的名单**（`sponsors.json`）：名单要有排序、要有匿名计数、要能"取到更新的一份"，
 * 而弹窗（82% 屏高、可滚动）本来就不是放列表的地方。入口留在关于页（行卡 + 那颗赞赏按钮），
 * 内容搬到这里。
 *
 * ## 赞赏码的归属（2026-10-14 反复了一轮，别再搬）
 * 本页原来上半是赞赏码、下半是名单。两边的宽度需求互斥（码要够大才扫得出来，名单会一直长），
 * 窄屏上只能二选一 —— 用户的原话是「防止内容挤压」。中途一度把码整块搬去 `DonateScreen.kt`
 * （路由 `detail/donate`），但用户随后明确「**不需要额外的赞赏入口，只需要按钮即可**」，
 * 于是那颗按钮改回**打开关于页的赞赏弹窗**、`DonateScreen.kt` 与 `detail/donate` 整条路由
 * 一并删除，码的渲染点回到 `AboutDeviceScreen` 里的 [com.ufi_axis.ui.screens.AboutDeviceScreen]
 * 赞赏弹窗（唯一一处）。
 * 本页自始至终只负责名单：`DETAIL_SPONSORS` 与数据层**一行都没动**。
 *
 * ## ★ 纵向滚动只能有一层（2026-10-14 崩溃根因）
 * `UfiPageBackground` **自身**就是一个 `Column(Modifier.verticalScroll(...))`
 * （见 `UfiBackground.kt`）。本页此前在它里面又套了一个 `Column(verticalScroll)`，
 * 于是内层滚动容器被外层以 `maxHeight = Constraints.Infinity` 测量，Compose 直接抛
 * 「Vertically scrollable component was measured with an infinity maximum height constraints」。
 * **不要**再往 `UfiPageBackground` 里放任何自带纵向滚动的组件（`verticalScroll` /
 * `LazyColumn`）—— 页面要滚动就靠它这一层。同类页面的正确写法见 `MonitorSettingsScreen`。
 *
 * ## 数据：保底 + 更新两层（与 web / core 同一份源文件）
 *  · **保底层**：构建期由 `feature-settings/build.gradle.kts` 的 `copySponsorsJson` 把仓库根
 *    `sponsors.json` 复制成 assets，页面进组合时**同步读、立即渲染** —— 不白屏、不等网络，
 *    core 没起来也有一份完整名单；
 *  · **更新层**：`GET /api/sponsors`（core 侧 12 小时一次从 main 分支拉，见 `SponsorRoutes`）
 *    成功且校验通过才替换；core 不可达就停在保底那份。
 *
 * ## 两层都必须校验，不合规整份丢弃
 * core 那份来自运行时拉取，**不经过编译期校验**；内置那份虽由构建期复制，但"复制到一半 /
 * 被别的工具改写"是可能的。所以两层走**同一个** [parseSponsors]（远端先 `toString()` 回文本，
 * 就为了让两层走同一条校验路径，而不是一个走 JsonElement、一个走字符串 —— 那样迟早分叉）。
 * 两层唯一的差别是 `version`：内置那份是仓库根原文、一定有它，所以强制校验；core 的响应
 * **不带** `version`（它在服务端已经校验过一次），在那里继续要求会让更新层永久静默失效。
 * 不合规时**保留上一层**（远端坏了退内置，内置坏了整块不渲染），绝不渲染半截。
 *
 * ## 渲染上的五条硬要求（都写在界面上，别"顺手补全"）
 *  · **不显示日期**：`at` 只用于排序，它不是"加入时间"这种用户可读的语义；
 *  · **不加"（按加入时间倒序）"这类说明行**：排序结果自己会说话；
 *  · **一行一个名字**（2026-10-14 用户明确要求，此前是 `FlowRow` 横排折行）：
 *    行样式用 `UfiSettingsItem` + `UfiDivider`，不自己搓 `Row`；理由与取舍见下方名单块的注释；
 *  · **匿名条目只计数**：末尾一行「另有 N 位匿名赞助者」，只在 N > 0 时出现，
 *    且它**不参与时间排序**（匿名条目没有可展示的位置）；
 *  · **空名单整块不渲染**，绝不显示"暂无赞助者" ——
 *    "没有内容"和"名单是空的"是两件事，后者不该被说出来。
 *
 * ## 排序为什么是 `at` 倒序 → 同级 `name` 升序
 * 只按 `at` 倒序时，同一个月内的条目顺序取决于**远端数组的顺序**；那份数组由人手工维护，
 * 插入一条新赞助就可能让整个月的名字重新洗牌。加一条 `name` 升序作次级键之后，
 * 远端顺序怎么变，屏幕上的名单都不跳。
 */
@Composable
fun SponsorsScreen(navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalResolvedPalette.current
    val prefs = remember(context) { AppPreferences(context) }
    val api = remember(prefs) { RetrofitClient.getApiService(prefs) }

    // 保底层：同步读 assets。读不到 / 不合规 → null（= 名单块整块不渲染，页面上只剩标题）。
    val bundled = remember(context) { readBundledSponsors(context) }
    // 更新层：null = 还没拿到合规的远端名单，此时用保底那份。
    var remote by remember { mutableStateOf<List<SponsorEntry>?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }

    /** 拉一次远端；[notifyFailure] 只在用户**手动**刷新时为 true（自动那次失败要静默）。 */
    suspend fun fetchRemote(notifyFailure: Boolean) {
        refreshing = true
        val fetched = runCatching { api.getSponsors() }.getOrNull()
        // 远端也走同一个校验器（条目规则逐条一致），只是它的响应不带 version —— 见 parseSponsors
        val parsed = fetched?.let { parseSponsors(it.toString(), requireVersion = false) }
        refreshing = false
        if (parsed != null) {
            remote = parsed
        } else if (notifyFailure) {
            // 校验不过与请求失败在这里是同一句：对用户来说都是"没拿到能用的名单"
            toastMessage = ToastMessage("没拿到可用的名单，继续显示内置那份", ToastType.ERROR)
        }
    }

    // 进页面自动拉一次。失败**静默**：保底那份已经在屏幕上，弹一句红条只会让人以为页面坏了。
    LaunchedEffect(Unit) { fetchRemote(notifyFailure = false) }

    // 远端优先，其次保底。两者都空 → 下面的名单块整块不渲染。
    val entries = remote ?: bundled.orEmpty()
    val named = sortedNamedSponsors(entries)
    val anonymousCount = entries.count { it.anonymous }

    UfiScreenScaffold(
        title = "赞助与感谢",
        navController = navController,
        showBack = true,
        actions = {
            // 手动刷新：core 那份每 12 小时才更新一次，但"刚提交了赞助想立刻看到"是真实需求。
            // 它是**唯一**会弹失败提示的路径（自动那次静默，理由见上面的 LaunchedEffect）。
            IconButton(
                enabled = !refreshing,
                onClick = { scope.launch { fetchRemote(notifyFailure = true) } }
            ) { Icon(Icons.Default.Refresh, contentDescription = "刷新名单") }
        }
    ) { padding ->
        // ★ 只保留一层纵向滚动（2026-10-14 崩溃修复）：
        //   UfiPageBackground 自己就是 `Column(verticalScroll)`（见 UfiBackground.kt），
        //   里面再套一个 `Column(verticalScroll)` 会让内层拿到 maxHeight=Infinity ——
        //   这正是本次崩溃抛的那句 "Vertically scrollable component was measured with an
        //   infinity maximum height constraints"。
        //   本页内容本来就只是"一组卡片"，不需要自己的滚动容器，所以删掉内层那一层，
        //   与 MonitorSettingsScreen / AboutDeviceScreen 等页面的写法对齐（它们都只写
        //   UfiPageBackground，不再自己滚）。分组间距由 UfiPageBackground 的
        //   `spacedBy(Spacing.CardBottomMargin)` 提供。
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            // ═════ 感谢名单 ═════
            // 只有真的有人可谢时才画这一块（空名单整块不渲染，理由见文件头）。
            if (named.isNotEmpty() || anonymousCount > 0) {
                UfiSettingsGroup {
                    UfiGroupHeader("感谢名单")
                    // 一行一个名字（2026-10-14 用户明确要求，替换掉原先的 FlowRow 横排折行）。
                    //
                    // 为什么改成一行一个（原注释写着"名字是词不是行，折行比留一列空白好看"，
                    // 那条取舍在这里不再成立）：
                    //  · 横排时每行的起点随前一个名字的长度漂移，眼睛没有固定落点；名字长短
                    //    不一，同一屏里"谁挨着谁"纯粹是排版偶然。名单会一直变长，扫读价值
                    //    来自"从上往下就是排序结果"，而一列才给得了这个。
                    //  · 横排还会让**一个长名字把整行剩下的位置挤掉**（它不可拆，直接换行），
                    //    于是名单越长、越容易被单个条目整体重排。
                    //  · 这一页要的是"逐个被读到"（感谢名单），不是"一屏塞下尽量多"。
                    //
                    // 行样式用 UfiSettingsItem + UfiDivider —— 仓内既有的列表行写法
                    // （与 PairingConfigScreen 的已配对列表、OnlineDevicesScreen 的设备列表
                    // 同一套），不自己搓 Row：UfiSettingsItem 自带 fillMaxWidth 与 10dp
                    // 上下留白，名字再多也散得开；行间一条 UfiDivider（palette.divider@8%）
                    // 让"一行一个"读得出来是列表而不是一片文字。
                    //
                    // 不加序号：序号会被读成**名次/等级**，而这份名单只有"时间倒序"一种顺序，
                    // 名次没有语义（同月内还按 name 升序兜底，更不是排名）。时间同样不显示
                    // （见文件头第一条硬要求）。
                    named.forEachIndexed { index, sponsor ->
                        if (index > 0) UfiDivider()
                        UfiSettingsItem(
                            title = sponsor.name,
                            // 名字**绝不许省略号**：默认 titleMaxLines = 2 在 320dp 窄屏上会把
                            // 32 字上限（NAME_MAX）的长名字截成 "…"，那等于把一位赞助者的名字弄丢。
                            // 这里不是设置项标题，没有"超过两行说明文案该改"的余地。
                            titleMaxLines = Int.MAX_VALUE
                        )
                    }
                    if (anonymousCount > 0) {
                        // 匿名那一行是**汇总行**，不是名单里的一条：它固定在名单尾部、不参与排序。
                        // 所以先来一条分隔线把"实名名单"和"统计"切开（名单为空时不画，否则卡片
                        // 顶部会凭空多一条线），再降一档字号与颜色（note + textSecondary）——
                        // 一列之后它仍然是最后一行，且一眼看得出"名单到此为止，这行是汇总"。
                        if (named.isNotEmpty()) UfiDivider()
                        Spacer(Modifier.height(Spacing.Medium))
                        // 匿名条目只在这里出现一次（不逐条列、不参与排序）
                        Text(
                            text = "另有 $anonymousCount 位匿名赞助者",
                            style = UfiTextStyles.note,
                            color = palette.textSecondary
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.SectionSpacing))
        }
        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
    }
}

// ────────────────────────── 数据 ──────────────────────────

/**
 * 一条赞助记录。
 *
 * `name` 与 `anonymous` **互斥**（校验里是硬规则）：同时给了两者时"这条算不算实名"没有答案，
 * 任一取舍都会让计数与渲染对不上。所以这里不做 `name` 优先之类的兜底 —— 那会让一条坏数据
 * 变成"看起来正常"。
 *
 * `internal`（而非 private）只为让 [parseSponsors] / [sortedNamedSponsors] 能被单测直接调用
 * —— 这两个函数决定"整份丢弃"与"名单顺序"，是这一页最该有测试的两处纯逻辑。
 */
internal data class SponsorEntry(val name: String, val anonymous: Boolean, val at: String)

/** 当前支持的名单格式版本。与 core 的 `SponsorRoutes.SUPPORTED_VERSION`、仓库根 sponsors.json 同值。 */
private const val SUPPORTED_VERSION = 1

/** 实名条目名长度上限（trim 后）。与 core 的 `SponsorRoutes.NAME_MAX` 同值。 */
private const val NAME_MAX = 32

/** `YYYY-MM` 或 `YYYY-MM-DD`，月份/日期做范围校验（挡掉 `2026-13-99` 这种"形状对、语义错"的值）。 */
private val AT_REGEX = Regex("""^\d{4}-(0[1-9]|1[0-2])(-(0[1-9]|[12]\d|3[01]))?$""")

private val sponsorsJson = Json { ignoreUnknownKeys = true }

/**
 * 校验一份 `sponsors.json` 文本，返回条目列表；**任何一条不满足就整份丢弃**（返回 null）。
 *
 * 逐条规则（与仓库根 sponsors.json 的 `_note`、core 的 `SponsorRoutes.validate` 三处同源）：
 * - 顶层必须是 JSON 对象；
 * - 顶层必须有 `sponsors` 数组；
 * - 每条**恰好**有 `name`（非空、trim 后 ≤ [NAME_MAX]）或 `anonymous: true` 之一；
 * - 每条 `at` 必填且形如 `YYYY-MM` / `YYYY-MM-DD`。
 *
 * `updatedAt` 是**可选**的（缺失不算不合规）—— 这一页根本不显示它（见文件头"不显示日期"），
 * 为它拒掉一整份好名单更不划算。
 *
 * 为什么整份丢弃而不是逐条过滤：名单是**一份**事实（某次发布的产物）。逐条过滤会把
 * "远端给了一份带错别字的新名单"变成"新旧混合的名单"，而且没有任何可见信号。
 *
 * @param requireVersion **两层的唯一差别**，必须显式传，不许给默认值：
 *  · 内置那份是仓库根 `sponsors.json` 的**原文**，一定带 `version` → 传 true，
 *    缺失或不等于 [SUPPORTED_VERSION] 都整份丢弃；
 *  · `GET /api/sponsors` 的响应**不带** `version`（core 侧 `SponsorRoutes.register` 只回
 *    `source` / `updatedAt` / `sponsors` —— 它在服务端已经用同一套规则校验过版本了）→ 传 false。
 *    在这里继续要求它，会让**更新层永久失效**，而且失效是静默的：页面照常显示保底名单，
 *    没有任何信号告诉开发者"远端那份从来没被接受过"。逐条规则两层完全一致，
 *    所以"core 回的那份也可能坏"这条要求并没有被放松。
 */
internal fun parseSponsors(raw: String, requireVersion: Boolean): List<SponsorEntry>? {
    val root = runCatching { sponsorsJson.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
    val versionRaw = (root["version"] as? JsonPrimitive)?.contentOrNull?.trim()
    val versionOk = versionRaw?.toIntOrNull() == SUPPORTED_VERSION
    if (requireVersion && !versionOk) return null
    val array = root["sponsors"] as? JsonArray ?: return null

    val entries = ArrayList<SponsorEntry>(array.size)
    for (element in array) {
        val obj = element as? JsonObject ?: return null
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim()
        val anonymous = (obj["anonymous"] as? JsonPrimitive)?.booleanOrNull == true
        val at = (obj["at"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (at == null || !AT_REGEX.matches(at)) return null
        when {
            // 两者都给 = 互斥被破坏；两者都没给 = 这条不知道该画成什么。都不接受。
            name != null && anonymous -> return null
            name != null -> {
                if (name.isEmpty() || name.length > NAME_MAX) return null
                entries += SponsorEntry(name = name, anonymous = false, at = at)
            }
            anonymous -> entries += SponsorEntry(name = "", anonymous = true, at = at)
            else -> return null
        }
    }
    return entries
}

/**
 * 读构建期打进 assets 的保底名单。
 *
 * 读不到（asset 缺失 / 解析失败 / 校验不过）一律回 null —— 调用方据此"整块不渲染"，
 * 而不是显示一句"暂无赞助者"（那会把"没读到"说成"没有赞助者"）。
 */
private fun readBundledSponsors(context: Context): List<SponsorEntry>? = runCatching {
    context.assets.open(BUNDLED_ASSET_NAME).use { it.readBytes().decodeToString() }
}.getOrNull()?.let { parseSponsors(it, requireVersion = true) }

/** assets 里的文件名（由 `feature-settings/build.gradle.kts` 的 `copySponsorsJson` 生成）。 */
private const val BUNDLED_ASSET_NAME = "sponsors.json"

/**
 * 实名名单的渲染顺序：`at` 倒序 → 同级 `name` 升序（理由见文件头「排序为什么」）。
 *
 * 匿名条目在这里被**滤掉**（它们只参与计数，不逐条列）—— 所以调用方拿到的就是"要画出来的那些"，
 * 页面不必自己再 filter 一次（那会让"匿名条目不参与排序"这条规则散在两个地方）。
 *
 * 提成独立函数是为了能被单测直接钉住：这条顺序一旦漂移，表现是"远端数组顺序一变，
 * 屏幕上的名单就跳"，而那正是加次级键要防的事。
 *
 * ⚠ 次级键用的是 Kotlin 默认的 `String` 比较（UTF-16 码位序），**刻意不用 `Collator`**：
 * 按拼音排序确实更"像给人看的"，但 `Collator` 的结果随设备 Locale 变 —— 同一份名单在
 * 中文机与英文机上顺序不同，而这条次级键的**全部意义**就是"任何情况下顺序都一样"。
 * 名单本身按 `at` 倒序排（新赞助在前），同级内部谁先谁后不影响可读性，稳定压倒"更自然"。
 */
internal fun sortedNamedSponsors(entries: List<SponsorEntry>): List<SponsorEntry> =
    entries.filter { !it.anonymous }
        .sortedWith(compareByDescending<SponsorEntry> { it.at }.thenBy { it.name })

// 2026-10-14 移出本文件：`DONATE_QR_WIDTH_FRACTION` / `rememberDonateQrBitmap` /
// `decodeDownsampledBitmap` 三个私有件随赞赏码位图搬走，最终落在 `AboutDeviceScreen.kt`
// 的赞赏弹窗里（同模块、`R.drawable.img_donate_alipay` / `img_donate_wechat` 不动）。
// 本页现在**一张图都不渲染** —— 那两张 1260px 级的位图全仓只该有一个解码点，
// 多一处就会让「打开赞赏就崩」（见 AboutDeviceScreen 里那三个件的注释）有第二个复发入口。


package com.ufi_axis.viewmodel.state

import com.ufi_axis_core.contract.Capability

// ══════════════════════════════════════════════════════════════════════════
// 设备能力集在 app 侧的形状（设备插件化计划书 §7 阶段 3 的 3.5）
// ══════════════════════════════════════════════════════════════════════════
//
// 数据来源：`GET /api/device/capabilities`，形状
//   { "plugin_id": "zte-f50", "capabilities": ["sms", "band_lock", …] }
// 是**数组**而不是 map —— 旧客户端遇到不认识的名字忽略即可，于是「新增能力」与
// 「这台设备不支持」能分得开（见 :core:contract 的 Capability 冻结区注释）。
//
// core 侧对这些域各有 route 门禁，缺能力一律 501 + ErrorCode.NOT_SUPPORTED。
// 在 3.5 之前的状态是「core 会拒，但 app 上的开关照样能点，点了才收到 501」；
// 本类的唯一职责就是把这件事**前置到 UI**：控件在点之前就灰掉。
//
// ── ⚠ 拉取失败必须当成「全部支持」，绝不许当成「全部不支持」 ──
//
// [loaded] = false 表示「本进程还没成功拿到过能力集」，它同时覆盖两种情形：
// 还没拉、以及拉失败了。两者对 UI 的结论是同一个：**保持现状行为，全部可点**。
//
// 反过来做（拉不到就全部灰掉）的后果：一次网络抖动就能把整个设置页灰掉。
// 而「设备支不支持某个功能」与「这一次请求通不通」毫无关系 ——
// 把后者渲染成前者，等于把「通道不可用」（503 UNAVAILABLE，可重试）
// 冒充成「设备不支持」（501 NOT_SUPPORTED，不可恢复），
// 那正是计划书 §11.6 明令要分开的两件事。
//
// ── 为什么是 Set<Capability> 而不是 Set<String> ──
//
// [Capability] 枚举来自 `:core:contract`，app 侧通过 `:app:data` 的
// `api(project(":core:contract"))` 已经能直接引用（`NetworkMode` 走的同一条路）。
// 直接用枚举 = 两端共享同一份 wire 值定义，不必在 app 侧再抄一份常量镜像 ——
// 镜像一多，改一个 wire 名就变成"三处同时改，漏一处编译器不报"。
//
// 认不出的 wire 名不丢弃、收进 [unknownWires]：那意味着**新 core 配旧 app**，
// 按冻结区口径忽略即可，但留着能让排查时看见"core 新增了能力而 app 还没跟上"。
data class DeviceCapabilityState(
    // 本进程是否已成功拿到过一次能力集。false = 还没拉 / 拉失败了（两者都按全部支持处理）。
    val loaded: Boolean = false,
    // core 声明支持的域。只在 [loaded] = true 时有意义。
    val supported: Set<Capability> = emptySet(),
    // core 下发了、但本版 app 的 Capability 枚举里没有的 wire 名。只用于排查，不影响判定。
    val unknownWires: List<String> = emptyList()
) {
    // 某个功能域在不在能力集里。
    //
    // [loaded] = false 时**恒为 true**：降级到「全部支持」，保持 3.5 之前的行为
    // （理由见本文件头部那段）。这条判定是本批的核心逻辑，也是单测的主要目标。
    fun supports(capability: Capability): Boolean = !loaded || capability in supported

    companion object {

        // 未知态：还没拉到，或者拉失败了。判定结果是「全部支持」。
        val UNKNOWN = DeviceCapabilityState()

        // 把 core 下发的 wire 名数组解析成本状态。
        //
        // 只要这个函数被调用过，就说明请求成功回来了 —— 所以 [loaded] 恒为 true，
        // 哪怕 [wires] 是空数组（那是"这台设备一个域都不支持"的合法表达，不是失败）。
        fun fromWires(wires: List<String>): DeviceCapabilityState {
            val known = LinkedHashSet<Capability>()
            val unknown = ArrayList<String>()
            for (wire in wires) {
                val cap = Capability.fromWire(wire)
                if (cap != null) known.add(cap) else unknown.add(wire)
            }
            return DeviceCapabilityState(
                loaded = true,
                supported = known,
                unknownWires = unknown
            )
        }
    }
}

// 置灰原因的统一口径（计划书 §7 / §11.6：三种「不可用」不许混成一句话）。
//
// 本函数只说**设备不支持**这一种。另两种各有自己的出口，不要复用这句：
// - 通道不可用（503 UNAVAILABLE，可重试）：走全局错误横幅 / Toast；
// - 参数越界（400 OUT_OF_RANGE）：走输入框自己的 errorMessage。
//
// 刻意不写「功能未开启」或「权限不足」：这两种说法会把用户引向"我去哪儿开一下"，
// 而这台设备压根没有这个能力 —— 没有任何地方可开，去找只会白费时间。
//
// @param feature 用户看得懂的功能名（如「文件共享」「基站锁定」），不要写端点或 SettingKey 名。
fun deviceUnsupportedNote(feature: String): String = "这台设备不支持$feature"

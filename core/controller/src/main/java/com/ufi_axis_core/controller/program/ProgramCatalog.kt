package com.ufi_axis_core.controller.program

import com.ufi_axis_core.util.AppLogger

/**
 * 可用程序的清单目录（内存注册表）。
 *
 * ## 数据从哪来（外挂式分发的元数据那一侧）
 *
 * 三级来源，越靠前越权威：
 *
 * 1. **内置骨架**（APK `assets/programs/catalog.json`）：只有 id / 名称 / 一句话说明。
 *    作用是"断网时商店不是白页"——用户看得到有哪些程序，只是装不了。
 * 2. **远端清单**（`version.json` 的 `programs` 段）：版本 / URL / sha256 / 运行时参数 / 阈值。
 *    这是**唯一信任锚点**：`sha256` 必须来自我们发的清单，不能来自上游 Release 页。
 * 3. **本地状态**：已经有什么版本（[ProgramStore.readMeta]），不在这里混。
 *
 * **二进制一律不内置** —— 这条在 `ProgramManifest` 的 KDoc 里也写了一遍，
 * 因为它是整个商店的体积与合规前提。
 *
 * ## 为什么是可替换的整体快照
 *
 * 刷新清单时"逐条合并"会让并发读到"一半旧一半新"的视图（比如 id 指向旧版本、
 * sha256 指向新版本）。这里用不可变 Map 整体替换，读侧拿到的永远是某一代的完整快照。
 */
class ProgramCatalog {

    @Volatile
    private var manifests: Map<String, ProgramManifest> = emptyMap()

    /** APK 内置的完整清单；远端清单只在其上覆盖，不会被"远端暂时没有"清空。 */
    @Volatile
    private var builtIn: Map<String, ProgramManifest> = emptyMap()

    /** 内置骨架（id → 展示名），用于"远端清单还没有时也能列出来" */
    @Volatile
    private var skeleton: Map<String, SkeletonEntry> = emptyMap()

    data class SkeletonEntry(val name: String, val summary: String)

    fun all(): List<ProgramManifest> = manifests.values.sortedBy { it.id }

    fun get(id: String): ProgramManifest? = manifests[id]

    fun skeletonOf(): Map<String, SkeletonEntry> = skeleton

    /**
     * 替换 APK 内置清单（启动时调用一次）。空列表合法，表示内置清单解析失败。
     */
    fun replaceBuiltIn(newManifests: Collection<ProgramManifest>) {
        val map = toMap(newManifests)
        builtIn = map
        manifests = map
        AppLogger.i(TAG, "内置程序清单：${map.size} 条（${map.keys.joinToString()}）")
    }

    /** 兼容旧调用：等同于 [replaceBuiltIn]。 */
    fun replace(newManifests: Collection<ProgramManifest>) = replaceBuiltIn(newManifests)

    /**
     * 用远端清单覆盖内置清单。
     *
     * - 远端没有的 id 保留内置版本（作为 APK 自带的兜底，不会因为远端短暂缺项就消失）；
     * - 远端有的 id 整体覆盖（远端是唯一可信锚点）；
     * - 远端新增的 id 直接加入；
     * - 空列表不清空内置清单（断网/清单拉取失败时商店仍然可用）。
     */
    fun replaceRemote(newManifests: Collection<ProgramManifest>) {
        val remote = toMap(newManifests)
        if (remote.isEmpty()) {
            AppLogger.w(TAG, "远端清单为空，保留内置 ${builtIn.size} 条")
            return
        }
        val merged = LinkedHashMap<String, ProgramManifest>(builtIn)
        var overridden = 0
        for ((id, m) in remote) {
            if (merged.containsKey(id)) overridden++
            merged[id] = m
        }
        manifests = merged
        AppLogger.i(
            TAG,
            "远端程序清单已合并：${remote.size} 条（覆盖 $overridden，总 ${merged.size} 条：${merged.keys.joinToString()}）"
        )
    }

    private fun toMap(items: Collection<ProgramManifest>): Map<String, ProgramManifest> {
        val map = LinkedHashMap<String, ProgramManifest>()
        for (m in items) {
            if (map.containsKey(m.id)) {
                AppLogger.w(TAG, "清单里 ${m.id} 重复，保留第一条")
                continue
            }
            map[m.id] = m
        }
        return map
    }

    fun replaceSkeleton(entries: Map<String, SkeletonEntry>) {
        skeleton = entries
    }

    /**
     * 商店列表用的 id 全集：远端清单 ∪ 内置骨架。
     *
     * 只在内置骨架里的条目也能显示，但状态是"暂无可用版本"（离线/清单未拉取）。
     */
    fun idsForDisplay(): List<String> = (manifests.keys + skeleton.keys).sorted()

    private companion object {
        const val TAG = "ProgramCatalog"
    }
}

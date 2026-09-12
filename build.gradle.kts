// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// ═════════════════════════════════════════════════════════════════════════════
// 签名凭据来源：仓库根 keystore.properties（已被 .gitignore 忽略，**严禁入库**）
//
// 为什么不写在 build.gradle.kts 里：此前 :app / :core 的 create("ufi") 把 keystore
// 路径与两个口令写成字符串字面量。构建脚本是**提交候选**，一旦入库，口令就永久留在
// git 历史里；而本项目的更新通道是 version.json.apkUrl 静默自动更新，签名私钥泄露
// = 攻击者可签发验签通过的更新包。
//
// 这里在根项目读一次，子项目经 rootProject.extra["ufiKeystoreProps"] 取用。
// 文件缺失时（CI 全新 checkout、新协作者）得到空 Properties：模块侧据此**不创建**
// ufi 签名配置，benchmark 变体退化为未签名，构建本身不失败。
//
// 格式（四个键，storeFile 可用绝对路径）：
//   ufi.storeFile=C:/Users/<you>/keystores/UFI-AXIS.jks
//   ufi.storePassword=...
//   ufi.keyAlias=UFI-AXIS
//   ufi.keyPassword=...
// ═════════════════════════════════════════════════════════════════════════════
extra["ufiKeystoreProps"] = java.util.Properties().apply {
    val propsFile = file("keystore.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}

// 注意：不要全局禁用 extractDebugAnnotations / extractReleaseAnnotations。
// 在 AGP 9.x 中，syncDebugLibJars（同步 aar jar）把前者产出的 typedefs.txt
// 列为必需输入；禁用后该文件永不存在，会导致所有 library 模块的 assemble 失败。
// 该任务基于编译后的 .class（ASM）提取注解，不会触发 kotlin-compiler 下载，
// 与完整 lint Kotlin 分析无关，无需禁用。

// ═════════════════════════════════════════════════════════════════════════════
// P2e：设计令牌字面量基线卡点
//
// 把「设计令牌不许写字面量」这条治理红线变成可执行检查。口径是**基线 diff**：
// 四类字面量的总数只能降不能升，不是绝对禁止。基线见 config/literal-baseline.properties
//（怎么跑 / 超标了怎么办 / 什么时候允许上调基线，都写在那个文件的头部注释里）。
//
// 纯 Kotlin 实现文件遍历 + 正则，不依赖 rg/grep，跨平台。
// 挂 check 生命周期，**故意不挂 assemble**：出包不该被治理检查卡住。
// ═════════════════════════════════════════════════════════════════════════════

@CacheableTask
abstract class CheckLiteralBaselineTask : DefaultTask() {

    /** 被扫描的 Kotlin 源码；include/exclude 在任务注册处声明（配置期只建 PatternSet，不读文件） */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /**
     * 基线文件内容参与增量判定。
     * 用 [InputFiles] 而非 [InputFile] 是为了**容忍文件缺失**——首次 bootstrap
     * （文件还不存在）时 @InputFile 会在任务动作执行前就报校验错，拿不到友好提示。
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineTracked: ConfigurableFileCollection

    /** 实际读写用的句柄；内容已由 [baselineTracked] 追踪，故标 Internal 避免重复声明 */
    @get:Internal
    abstract val baselineFile: RegularFileProperty

    /** `-PupdateLiteralBaseline`：重写基线文件而不是失败 */
    @get:Input
    abstract val updateBaseline: Property<Boolean>

    /** 仅用于把绝对路径相对化。绝对路径不能进 @Input，否则换目录/换机器就丢缓存 */
    @get:Internal
    abstract val repoRoot: DirectoryProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    private data class Category(val key: String, val title: String, val regex: Regex)

    private data class Hit(val path: String, val line: Int, val text: String)

    @TaskAction
    fun verify() {
        val root = repoRoot.get().asFile
        val baseline = readBaseline(baselineFile.get().asFile)

        // ── 统计 ──────────────────────────────────────────────────────────────
        val hits: Map<String, MutableList<Hit>> = CATEGORIES.associate { it.key to mutableListOf<Hit>() }
        var scanned = 0
        for (file in sources.files.sorted()) {
            if (!file.isFile) continue
            scanned++
            val rel = file.relativeTo(root).invariantSeparatorsPath
            val raw = file.readLines()
            val code = stripCommentsAndStrings(raw)
            for ((i, line) in code.withIndex()) {
                if (line.isBlank()) continue
                for (cat in CATEGORIES) {
                    // duration 允许跨一行：`tween(` / `durationMillis =` 换行后才写数字也要抓到
                    val target = if (cat.key == "duration") withContinuation(code, i) else line
                    val n = cat.regex.findAll(target).count()
                    repeat(n) { hits.getValue(cat.key).add(Hit(rel, i + 1, raw[i].trim())) }
                }
            }
        }

        val totals = CATEGORIES.associate { it.key to hits.getValue(it.key).size }
        val perFile: Map<String, Map<String, Int>> = CATEGORIES.associate { cat ->
            cat.key to hits.getValue(cat.key).groupingBy { it.path }.eachCount()
        }

        writeReport(scanned, totals, perFile)

        // ── 更新模式：写基线后收工 ─────────────────────────────────────────────
        if (updateBaseline.get()) {
            val target = baselineFile.get().asFile
            target.parentFile.mkdirs()
            target.writeText(renderBaseline(totals, perFile))
            logger.lifecycle(
                buildString {
                    appendLine("已重写基线文件：${target.relativeTo(root).invariantSeparatorsPath}")
                    CATEGORIES.forEach { c ->
                        val old = baseline["total.${c.key}"]
                        val new = totals.getValue(c.key)
                        appendLine("  ${c.key.padEnd(9)} $new" + if (old != null && old != new) "（原 $old）" else "")
                    }
                    append("记得把它一起 commit —— 基线变动必须让 review 看得见。")
                }
            )
            return
        }

        if (baseline.isEmpty()) {
            throw GradleException(
                "基线文件不存在或为空：config/literal-baseline.properties\n" +
                    "先生成一次：./gradlew checkLiteralBaseline -PupdateLiteralBaseline"
            )
        }

        // ── 比对 ──────────────────────────────────────────────────────────────
        val exceeded = CATEGORIES.filter { totals.getValue(it.key) > (baseline["total.${it.key}"] ?: 0) }
        val lowered = CATEGORIES.filter { totals.getValue(it.key) < (baseline["total.${it.key}"] ?: 0) }

        logger.lifecycle("字面量基线（扫描 $scanned 个 .kt）：")
        CATEGORIES.forEach { c ->
            val base = baseline["total.${c.key}"] ?: 0
            val now = totals.getValue(c.key)
            val mark = when {
                now > base -> "超出 +${now - base}"
                now < base -> "低于 -${base - now}"
                else -> "持平"
            }
            logger.lifecycle("  ${c.key.padEnd(9)} 基线 ${base.toString().padStart(5)} / 实测 ${now.toString().padStart(5)}   $mark")
        }

        if (exceeded.isNotEmpty()) {
            throw GradleException(renderFailure(exceeded, baseline, totals, perFile, hits))
        }

        if (lowered.isNotEmpty()) {
            logger.lifecycle(
                buildString {
                    appendLine("")
                    appendLine("以下类别已低于基线，基线可下调（请更新基线文件）：")
                    lowered.forEach {
                        appendLine("  ${it.key} 基线可下调至 ${totals.getValue(it.key)}（当前记录 ${baseline["total.${it.key}"]}）")
                    }
                    append("  ./gradlew checkLiteralBaseline -PupdateLiteralBaseline")
                }
            )
        }
        logger.lifecycle("报告：${reportFile.get().asFile.relativeTo(root).invariantSeparatorsPath}")
    }

    // ── 失败信息：必须能直接定位到文件:行号 ───────────────────────────────────
    private fun renderFailure(
        exceeded: List<Category>,
        baseline: Map<String, Int>,
        totals: Map<String, Int>,
        perFile: Map<String, Map<String, Int>>,
        hits: Map<String, List<Hit>>,
    ): String = buildString {
        appendLine("字面量基线卡点失败：以下类别超出基线（口径 = 不许新增，不是绝对禁止）")
        for (cat in exceeded) {
            val base = baseline["total.${cat.key}"] ?: 0
            val now = totals.getValue(cat.key)
            appendLine("")
            appendLine("[${cat.key}] ${cat.title}：基线 $base → 实测 $now（+${now - base}）")
            // 只列"这个文件比基线记录多了"的文件，避免把上千条历史存量全刷出来
            val grown = perFile.getValue(cat.key).filter { (path, count) ->
                count > (baseline["file.${cat.key}.$path"] ?: 0)
            }
            if (grown.isEmpty()) {
                appendLine("  （总数上升但没有单文件超记录，通常是文件被重命名/移动；")
                appendLine("    确认无新增后跑 -PupdateLiteralBaseline 重建基线）")
                continue
            }
            for ((path, count) in grown.entries.sortedByDescending { it.value }) {
                val was = baseline["file.${cat.key}.$path"] ?: 0
                appendLine("  $path（基线 $was → 实测 $count）")
                hits.getValue(cat.key).asSequence().filter { it.path == path }.forEach { h ->
                    appendLine("    $path:${h.line}   ${h.text.take(120)}")
                }
            }
        }
        appendLine("")
        appendLine("怎么办（按优先级）：")
        appendLine("  1. 改用令牌：间距/圆角 → UfiSpacing，颜色 → MaterialTheme.colorScheme / 主题调色板，")
        appendLine("     字号 → MaterialTheme.typography，时长 → UfiMotion.Duration。")
        appendLine("  2. 确实是新语义档位 → 先去 app/ui/.../ui/theme（或 UfiMotion）加令牌，再引用。")
        appendLine("  3. 确有正当理由必须写裸值 → 调用点注释说明理由，然后")
        appendLine("     ./gradlew checkLiteralBaseline -PupdateLiteralBaseline")
        append("     并把基线文件的改动一起 commit，让 review 看见基线被上调了。")
    }

    private fun writeReport(scanned: Int, totals: Map<String, Int>, perFile: Map<String, Map<String, Int>>) {
        val out = reportFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(
            buildString {
                appendLine("UFI-AXIS 字面量基线报告")
                appendLine("扫描 .kt 文件数：$scanned")
                appendLine("")
                for (cat in CATEGORIES) {
                    appendLine("[${cat.key}] ${cat.title}")
                    appendLine("  正则：${cat.regex.pattern}")
                    appendLine("  总计：${totals.getValue(cat.key)}")
                    val top = perFile.getValue(cat.key).entries.sortedByDescending { it.value }.take(10)
                    if (top.isEmpty()) appendLine("  （无）") else {
                        appendLine("  集中度 top${top.size}：")
                        top.forEach { appendLine("    ${it.value.toString().padStart(5)}  ${it.key}") }
                    }
                    appendLine("")
                }
            }
        )
    }

    private fun renderBaseline(totals: Map<String, Int>, perFile: Map<String, Map<String, Int>>): String =
        buildString {
            append(BASELINE_HEADER)
            appendLine("# ── 卡点依据：四类总数 ────────────────────────────────────────────────────────")
            CATEGORIES.forEach { appendLine("total.${it.key}=${totals.getValue(it.key)}") }
            for (cat in CATEGORIES) {
                appendLine("")
                appendLine("# ── ${cat.key}：${cat.title} ──")
                perFile.getValue(cat.key).entries.sortedBy { it.key }
                    .forEach { appendLine("file.${cat.key}.${it.key}=${it.value}") }
            }
        }

    private fun readBaseline(file: File): Map<String, Int> {
        if (!file.isFile) return emptyMap()
        return file.readLines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            val idx = line.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val value = line.substring(idx + 1).trim().toIntOrNull() ?: return@mapNotNull null
            line.substring(0, idx).trim() to value
        }.toMap()
    }

    /**
     * 把下一行的**首个 token** 接到本行尾部，用于 duration 的跨行识别。
     *
     * 只在本行以 `(` 或 `=` 结尾时生效，且只接一个 token —— 这是为了既能抓到
     * ```
     * animationSpec = tween(
     *     320,
     * ```
     * 这种换行写法，又不会重复计数：接进来的 token 单独成不了一次匹配
     * （`tween( durationMillis` 不匹配，下一行自己的 `durationMillis = 320` 才匹配）。
     * 仅对 duration 生效；若对 dp 这类也做，`padding(` + `8.dp` 会被算两次。
     */
    private fun withContinuation(code: List<String>, index: Int): String {
        val line = code[index]
        val trimmed = line.trimEnd()
        if (!trimmed.endsWith("(") && !trimmed.endsWith("=")) return line
        val nextToken = code.getOrNull(index + 1)?.trim()?.takeWhile { !it.isWhitespace() }
        return if (nextToken.isNullOrEmpty()) line else "$trimmed $nextToken"
    }

    /**
     * 把注释和字符串/字符字面量的内容抹成空格（保留行数与列位）。
     *
     * 为什么要抹：本仓库 KDoc 里大量写着「120ms」「durationMillis = 300」「16.dp」这类
     * 解释性数字，若不抹，写文档就会把基线顶上去，卡点会变成噪音源。抹字符串顺带解决了
     * `"http://…"` 里的 `//` 被误当行注释、从而吞掉同行后续代码的问题。
     *
     * 已知取舍：三引号原始字符串的跨行部分不做跟踪（状态每行重置），代价只是原始字符串
     * 内部的数字可能被计入——UI 源码里这种情况可忽略，换来的是不会因状态跑飞而漏检整段文件。
     */
    private fun stripCommentsAndStrings(lines: List<String>): List<String> {
        val result = ArrayList<String>(lines.size)
        var inBlockComment = false
        for (raw in lines) {
            val sb = StringBuilder(raw.length)
            var i = 0
            var delimiter: Char? = null   // 当前所处字符串/字符字面量的定界符
            var escaped = false
            while (i < raw.length) {
                val c = raw[i]
                val next = if (i + 1 < raw.length) raw[i + 1] else '\u0000'
                when {
                    inBlockComment -> {
                        if (c == '*' && next == '/') { inBlockComment = false; i += 2 } else i++
                    }
                    delimiter != null -> {
                        sb.append(' ')
                        when {
                            escaped -> escaped = false
                            c == '\\' -> escaped = true
                            c == delimiter -> delimiter = null
                        }
                        i++
                    }
                    c == '/' && next == '/' -> i = raw.length          // 行注释：丢弃本行剩余
                    c == '/' && next == '*' -> { inBlockComment = true; i += 2 }
                    c == '"' || c == '\'' -> { delimiter = c; sb.append(' '); i++ }
                    else -> { sb.append(c); i++ }
                }
            }
            result.add(sb.toString())
        }
        return result
    }

    private companion object {
        /**
         * 四类字面量口径。
         *
         * duration 那条是唯一需要"精确"的：`tween\s*(?:<…>)?\s*\(\s*-?\d` 要求左括号后
         * **紧跟数字**，所以 `tween(UfiMotion.Duration.Base)`（括号后是标识符首字母）天然不匹配，
         * 而 `tween(320)` / 显式泛型的 `tween<Float>(320)` 都匹配——`(?:<[^<>()]*>)?` 这段
         * 就是为了别被 `tween<Float>(` 这种写法绕过去（实测过：漏了它就抓不到）。
         * 命名参数写法 `tween(durationMillis = 320)` 由第二个分支 `durationMillis\s*=\s*-?\d`
         * 兜住（同一处只会命中一个分支，不会重复计数）。
         * `delayMillis = 数字` 是**相位错开量**不是时长令牌，不纳入（UfiMotion 也没有 delay 梯度）。
         *
         * dp/sp 的 `(?<![\w.])` 后顾是为了排除 `x2.dp`、`v1.5.sp` 这类标识符尾部数字，
         * 同时保证 `4.5.dp` 整体只算一次。
         */
        val CATEGORIES = listOf(
            Category("dp", "dp 字面量（12.dp / 4.5.dp）", Regex("""(?<![\w.])\d+(?:\.\d+)?\.dp\b""")),
            Category("color", "硬编码色（Color(0x…)）", Regex("""\bColor\s*\(\s*0[xX]""")),
            Category("sp", "sp 字面量（14.sp / 12.5.sp）", Regex("""(?<![\w.])\d+(?:\.\d+)?\.sp\b""")),
            Category(
                "duration",
                "裸时长字面量（tween(数字) / durationMillis = 数字）",
                Regex("""\btween\s*(?:<[^<>()]*>)?\s*\(\s*-?\d|\bdurationMillis\s*=\s*-?\d"""),
            ),
            // 2026-09-07（P4f/P5 红线）：裸 M3 组件。业务页面应用自研 Ufi* 组件，
            // 否则同一种按钮在不同页面长得不一样（G1 要消灭的正是这个）。
            // `(?<![\w.])` 把 `UfiButton(` / `IconButton(` / `xxx.Card(` 全部排除掉 ——
            // 只剩真正裸用的那 5 个 M3 名字。
            //
            // ⚠ 口径说明：本类**包含 app/ui 组件库内部**的合法使用（`UfiButton` 自己就得包 M3 `Button(`，
            // 弹窗族要包 `AlertDialog(`）。之所以不按目录区分：Category 只有正则、没有路径过滤，
            // 为一个类别加路径维度会把 task 复杂度翻一倍。总量不许上升同样能挡住"业务页面新增裸用"，
            // 而库内部确需新增时按本文件既有规则（写理由 + 同 commit 上调基线）放行。
            Category(
                "m3",
                "裸 M3 组件（Button / OutlinedButton / TextButton / Card / AlertDialog）",
                Regex("""(?<![\w.])(?:OutlinedButton|TextButton|AlertDialog|Button|Card)\s*\("""),
            ),
        )

        val BASELINE_HEADER = """
            # ═══════════════════════════════════════════════════════════════════════════════
            # 设计令牌字面量基线（计划书 P2e 卡点）
            # 本文件由 ./gradlew checkLiteralBaseline -PupdateLiteralBaseline 生成，勿手改数字
            # ═══════════════════════════════════════════════════════════════════════════════
            #
            # 口径：**基线 diff —— 不许新增**，不是绝对禁止。四类总数只能降不能升。
            #
            # 怎么跑
            #   ./gradlew checkLiteralBaseline --console=plain          # 校验（./gradlew check 已带上它）
            #   ./gradlew checkLiteralBaseline -PupdateLiteralBaseline  # 重新生成本文件
            #   详细报告（含各类集中度 top10）：build/reports/literal-baseline/literal-baseline-report.txt
            #   注意：**没有挂进 assemble**，出包不受本卡点影响。
            #
            # 超标了怎么办（按优先级）
            #   1. 改用令牌：间距/圆角 → UfiSpacing，颜色 → MaterialTheme.colorScheme / 主题调色板，
            #      字号 → MaterialTheme.typography，时长 → UfiMotion.Duration。
            #   2. 确实是尚不存在的语义档位 → 先在 theme/（或 UfiMotion）里加令牌，再在调用点引用。
            #   3. 只有以下情况允许**上调基线**：调用点写清"为什么这里必须是裸值"的注释，并把本文件的
            #      改动放进同一个 commit（基线上调必须让 review 看见）。可接受的理由例如：
            #      对齐系统/第三方度量、平台 API 只接受数字、一次性像素级 hack。
            #      不可接受：图省事、"先写死回头再改"。
            #
            # 数字降下来了：task 会提示"基线可下调至 N"，跑 -PupdateLiteralBaseline 更新并提交。
            #
            # 统计范围：各模块 src/main 下的 *.kt。排除：
            #   - app/ui/src/main/java/com/ufi_axis/ui/theme/**  令牌定义本身
            #   - src/test/** 、src/androidTest/**               测试里钉死具体数值是好事
            #   - UfiGalleryScreen.kt 、UfiPageSwitcherPreview.kt 组件画廊 / 预览，不进产物观感
            #   - build/ 、build.old*/ 等构建产物
            #   注释与字符串字面量内部的内容不计入（否则写 KDoc 就会顶高基线）。
            #
            # 键：total.<类别> 是卡点依据；file.<类别>.<相对路径> 仅供失败时定位"哪个文件多了"，
            #     文件重命名只会让失败信息多几行噪音，不会因此把总数卡红。
            #
        """.trimIndent() + "\n"
    }
}

// ── 扫描范围（配置期只声明 PatternSet，不做 IO；文件遍历发生在执行期）────────────
private val literalBaselineSources = fileTree(rootDir) {
    include("**/src/main/**/*.kt")
    exclude(
        // 构建产物 / 缓存
        "**/build", "**/build/**",
        "**/build.old*", "**/build.old*/**",
        "**/.gradle", "**/.gradle/**",
        "**/.kotlin", "**/.kotlin/**",
        ".git", ".git/**",
        // 令牌定义本身
        "app/ui/src/main/java/com/ufi_axis/ui/theme/**",
        // 组件画廊 / 预览：不进产物观感
        "**/UfiGalleryScreen.kt",
        "**/UfiPageSwitcherPreview.kt",
        // 外部参考项目 / 助手工作区（非本仓库源码）
        "UFI-TOOLS-REF/**", "ufi-ui-kit/**",
        ".comate/**", ".codebuddy/**", ".workbuddy/**",
        "web/**",
    )
}

val checkLiteralBaseline = tasks.register<CheckLiteralBaselineTask>("checkLiteralBaseline") {
    group = "verification"
    description = "统计非 theme 源码里的 dp / 硬编码色 / sp / 裸时长字面量，与 config/literal-baseline.properties 比对，超出基线即失败"
    sources.from(literalBaselineSources)
    baselineFile.set(layout.projectDirectory.file("config/literal-baseline.properties"))
    baselineTracked.from(baselineFile)
    repoRoot.set(layout.projectDirectory)
    // 配置缓存友好：通过 providers 读取 -P 开关，Gradle 会把它记为构建输入
    updateBaseline.set(providers.gradleProperty("updateLiteralBaseline").map { true }.orElse(false))
    reportFile.set(layout.buildDirectory.file("reports/literal-baseline/literal-baseline-report.txt"))
}

// ⚠ 显式约束（2026-09-12）：本任务的输入是覆盖**仓库根目录**的 fileTree
// （literalBaselineSources = fileTree(rootDir)），而 :core:network:copyWebDist 的产出目录
// core/src/main/assets/web 正好在根下 —— 两个位置重叠却没有依赖边。后果是二者同处一次调用时
// Gradle 会报 "uses this output of task ':core:network:copyWebDist' without declaring an
// explicit or implicit dependency" 并**硬失败**（实测出现过一次；该问题对任务图形状 /
// UP-TO-DATE 状态敏感，四种方式均未能稳定复现）。
//
// 与 core/build.gradle.kts 给 :core:merge*Assets 加 dependsOn 是同一问题的两种解：
// 那边真的读 assets，必须 dependsOn；这边只需 mustRunAfter —— 仅约束顺序，
// 不会让「只想查字面量」的人被迫跑一遍 npm build。
// 注：CI 两条工作流都不跑本任务，故与发布链路无关，纯本地组合调用体验问题。
checkLiteralBaseline.configure {
    mustRunAfter(":core:network:copyWebDist")
}

// 挂 check 生命周期：`./gradlew check` 会带上它。
// 故意**不**挂 assemble —— 出包不该被治理检查卡住。
tasks.register("check") {
    group = "verification"
    description = "根项目校验聚合：目前只有字面量基线卡点（不影响 assemble）"
    dependsOn(checkLiteralBaseline)
}

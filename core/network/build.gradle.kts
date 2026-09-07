plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ufi_axis_core.lib_network"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // ===== F21 预期分层边界（仅声明对齐，不搬代码）=====
    // 预期：network（HTTP 服务器）只依赖 api 门面 + websocket，不应直连 cache。
    // 当前跳层（计划内治理）：
    //   - :core:cache     → 直连响应缓存（应由 api 层封装后透出）
    //   - :core:websocket → 同层直连（合理，但需注意与 cache 的耦合边界）
    implementation(project(":core:common"))
    implementation(project(":core:api"))
    implementation(project(":core:cache"))
    implementation(project(":core:websocket"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // Ktor Server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.status.pages)
    // 交付层压缩：P2 优化。Ktor 2.3.x 的 Compression 插件 artifact 名为 ktor-server-compression，
    // 运行时按 Accept-Encoding 对静态资源（JS/CSS/JSON/SVG/HTML/woff2）与 API JSON 做 gzip/deflate，
    // 零构建期成本、零 assets/zip 契约改动。
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.serialization.kotlinx.json)
}

// ── Web 前端构建集成 ──
// 链路：buildWeb（npm run build → web/dist）→ copyWebDist（Sync 镜像到 core assets）
//       → :core 的 merge*Assets 打进 APK。
// 通过 -PskipWeb 完全跳过（开发调试后端时避免等待 npm）。
//
// 2026-09-06 修复（四个缺陷，详见各任务注释）：
//   1. copyWebDist 由 Copy 改 Sync —— Copy 只增不删，历次 vite 构建的旧 hash 文件在
//      core/src/main/assets/web 里无限累积（曾达 888 文件 / 17.72 MB，其中 760 个是残留），
//      让 core APK 白涨约 5 MB。
//   2. buildWeb 原来用 `enabled = !webDistIndex.exists()` 做新鲜度判断，实际语义是
//      "dist 一旦存在就永不重建" —— 本地改了前端源码，assets 里那份永远不更新（静默正确性问题）。
//      改为声明真实 inputs/outputs，交给 Gradle 做增量判断。
//   3. Exec 不走 shell，Windows 上 "npm" 不可执行（需要 npm.cmd）—— 按平台选可执行名。
//   4. copyWebDist 写的是 :core 的 assets 源码目录，却只挂在 :core:network 的 preBuild 上。
//      显式约束加在 core/build.gradle.kts（消费侧），见那里的注释。
val webDir = file("${rootProject.projectDir}/web")
val webDistDir = file("${rootProject.projectDir}/web/dist")
val webResDir = file("${rootProject.projectDir}/core/src/main/assets/web")

// -PskipWeb 开关：用 providers.gradleProperty 读取，Gradle 会把这次读取记为
// **配置缓存输入** —— 加/去掉 -PskipWeb 会让配置缓存失效并重新配置，
// 所以在配置期算成 Boolean 是安全的，不会像原来 project.hasProperty 那样被缓存固化。
//
// 为什么不用 onlyIf { }：Kotlin DSL 的脚本顶层 val 编译成脚本类的字段，
// onlyIf 的 lambda 会连带捕获脚本对象，配置缓存序列化直接报
// "cannot serialize Gradle script object references"（已实测失败）。
val skipWeb = providers.gradleProperty("skipWeb").isPresent

// 缺陷 3：Exec 直接 exec，不经 shell。Windows 上 npm 是 npm.cmd，必须区分。
val npmExecutable = if (
    providers.systemProperty("os.name").get().lowercase().startsWith("windows")
) "npm.cmd" else "npm"

val buildWeb = tasks.register<Exec>("buildWeb") {
    group = "web"
    description = "构建 web 前端（vue-tsc + vite）到 web/dist"
    enabled = !skipWeb
    workingDir = webDir
    commandLine(npmExecutable, "run", "build")

    // 缺陷 2：声明真实输入输出，让 Gradle 自己判增量。
    // 源码没变 → UP-TO-DATE 跳过；源码变了 → 自动重建；
    // CI 全新 checkout 没有 dist（web/.gitignore:2 忽略 dist/）→ outputs 缺失 ⇒ 必然执行。
    // outputs.dir 指向被 gitignore 的目录没有任何问题，Gradle 只看文件系统。
    inputs.dir("${webDir}/src")
        .withPropertyName("webSrc")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("${webDir}/public")
        .withPropertyName("webPublic")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        "${webDir}/package.json",
        "${webDir}/package-lock.json",
        "${webDir}/vite.config.ts",
        "${webDir}/index.html",
        "${webDir}/tsconfig.json",
        "${webDir}/tsconfig.node.json",
        "${webDir}/tailwind.config.js",
        "${webDir}/postcss.config.js",
        // vite.config.ts 的 webVersionPlugin 读根 version.json 生成 dist/version.json，
        // 改版本号也必须触发重建。
        "${rootProject.projectDir}/version.json",
    )
        .withPropertyName("webBuildConfig")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(webDistDir).withPropertyName("webDist")
}

// 缺陷 1：Sync 而非 Copy —— 镜像语义（目标目录 = 源目录，多余文件删除）。
//
// ⚠ 为什么可以安全 Sync：core/src/main/assets/web 下 100% 是 vite 产物
//   （顶层只有 index.html / favicon.svg / version.json / assets/，无一例外由 web/dist 生成），
//   .gitignore:67 已忽略整个目录，Kotlin 侧也没有任何硬编码文件名
//   （运行时是 webResourceManager.readAsset(requestPath) 纯动态取）。
// ⚠ 因此：不要往 core/src/main/assets/web 手工放任何文件 —— 下一次构建会被 Sync 删掉。
//   需要内置的静态资源请放 web/public/（会被 vite 带进 dist），或换一个 assets 子目录。
val copyWebDist = tasks.register<Sync>("copyWebDist") {
    group = "web"
    description = "把 web/dist 镜像同步到 core assets（多余的旧 hash 产物会被删除）"
    dependsOn(buildWeb)
    enabled = !skipWeb
    from(webDistDir)
    into(webResDir)
}

tasks.named("preBuild") { dependsOn(copyWebDist) }

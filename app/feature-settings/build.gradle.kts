plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ufi_axis.feature.settings"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    lint { abortOnError = false }
}

dependencies {
    implementation(project(":app:viewmodel"))
    implementation(project(":app:data"))
    implementation(project(":app:ui"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // ★ 单元测试：Webhook 简易输入框与存储之间的前后缀换算（纯函数，无需 Robolectric）
    testImplementation(libs.junit)
}

// ── 赞助名单内置保底（2026-10-14）──
//
// 仓库根 `sponsors.json` 是**唯一源文件**：三端在构建期各自取用同一份（web 进 bundle、
// app 与 core 进 assets），core 另有一条 12 小时的后台刷新从 main 分支拉更新
// （见 core 的 `SponsorRoutes`）。这里负责把它变成 :app:feature-settings 的内置 asset，
// 供「赞助与感谢」页（SponsorsScreen）**零延迟**先渲染一份，再等 `GET /api/sponsors`
// 回来替换 —— 放在本模块是因为两张赞赏码位图
// （res/drawable-nodpi/img_donate_alipay.png / img_donate_wechat.png，供 AboutDeviceScreen 的赞赏弹窗）
// 也在这里，页面的两半数据源不该分居两个模块。
// 2026-10-14：原来这里指向单张 `img_donate_qr.jpg`，它已随「赞赏」与「赞助名单」拆页被删除，
// 注释不改就会指向一个不存在的文件。
//
// 为什么是「Copy 到 build/generated/… 再挂成 assets 源」而不是 `assets.srcDir("$rootDir")`：
//   后者等于把**整个仓库**登记成资源输入（`.git`、`web/node_modules`、`keystore.properties`…），
//   merge*Assets 会把它们统统打进 APK。中间目录是唯一能把"这一个文件"和"仓库根"分开的办法。
//
// 为什么不用 Sync（:core:network 的 copyWebDist 用的是 Sync）：那边的目标目录 100% 是 vite
//   产物，可以安全镜像；这里的 generated 目录将来可能放别的内置资源，镜像语义会删掉不属于
//   本任务的东西。Copy 只增不删，在这里恰好是想要的。
//
// 为什么挂载走变体 API 而不是 `sourceSets["main"].assets.srcDir(...)`：与 `core/api/build.gradle.kts`
//   同一处坑（AGP 9.2.1 + Gradle 9.4.1 下该 Kotlin DSL 访问器在配置期就抛
//   `DefaultAndroidLibrarySourceSet_Decorated cannot be cast to AndroidLibrarySourceSet`）。
//   `addStaticSourceDirectory` 是 AGP 7.3+ 的推荐写法。
val sponsorsJsonFile = file("${rootProject.projectDir}/sponsors.json")
val sponsorsAssetsDir = layout.buildDirectory.dir("generated/sponsorsAssets")

val copySponsorsJson = tasks.register<Copy>("copySponsorsJson") {
    group = "build"
    description = "把仓库根 sponsors.json 复制为 :app:feature-settings 的内置 asset（赞助名单保底数据）"
    from(sponsorsJsonFile)
    // 目标目录由变体 API 通过静态源目录消费；这里再写一次同一个值，是为了让这个任务
    // 能被单独执行（gradlew :app:feature-settings:copySponsorsJson）。
    into(sponsorsAssetsDir)
    // 显式声明输入：源文件被误删时构建**失败**，而不是静默产出一个没有保底数据的 APK
    //（运行期读不到 asset 只会回落到"只显示赞赏码"，那种坏法在打包环节看不出来）。
    inputs.file(sponsorsJsonFile).withPropertyName("sponsorsJson")
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory(sponsorsAssetsDir.get().asFile.absolutePath)
    }
}

// 生产侧 → 消费侧的显式依赖边（照抄 core/api/build.gradle.kts 给同一个文件补边的做法）：
// 静态源目录只是一个路径，AGP 不知道它由哪个任务产出。preBuild 保证本模块任何构建都先
// 生成它；merge*Assets 那条是并行执行 / 配置缓存下顺序的显式约束。
tasks.named("preBuild") { dependsOn(copySponsorsJson) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(copySponsorsJson)
}

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ufi_axis.feature.resources"
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
    /*
     * 只依赖 :app:data + :app:ui，**刻意不依赖 :app:viewmodel**。
     *
     * 资源中心是「一次性读取 + 自持任务轮询」的页面（与 TrafficReportScreen 同一手法）：
     * 它要的数据全在 core 的 /api/components 与 /api/web/… 上，没有第二个页面共享这份状态，
     * 进 MainViewModel 只会多一份需要清理的常驻状态。而 TaskController 的 scope 也不在
     * viewModel 里（见 ResourceTaskController 的 KDoc）。
     *
     * 少一条依赖还有实际收益：:app:feature-tools / :app:feature-settings 都要跳到本页，
     * 它们经 Routes 常量跳转（路由字符串在 :app:ui），**不需要**依赖本模块 —— 三个 feature
     * 模块之间因此没有任何新增的横向依赖。
     */
    implementation(project(":app:data"))
    implementation(project(":app:ui"))

    // 解析 /api/components 与 /api/web/… 的 JsonElement（与 feature-tools / feature-files 同一写法）
    //
    // ⚠ 注释里不要写 `/api/web/*`：Kotlin 的块注释**会嵌套**，那个 `/*` 会把整个文件后半段
    //   吞进注释里，Gradle 报的是"dependencies 那行 Expecting '}'"（2026-10-15 踩过）。
    implementation(libs.kotlinx.serialization.json)
    // ⚠️ okhttp（MultipartBody / RequestBody）不在此声明：经 :app:ui 的
    //    `api(libs.coil.network.okhttp)` 透传到编译类路径，feature-files 的
    //    FilePreviewOverlay（okhttp3.Request）走的就是这条路。不为一个上传体再声明一份，
    //    否则同一个库在依赖图里出现两个声明源，升级时容易只改一处。

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
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
}

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ufi_axis.feature.media"
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
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    // 缩略图（core 的 /api/media/thumbnail），走全局 ImageLoader（带设备签名拦截器）
    implementation(libs.coil.compose)

    /*
     * 媒体播放（Media3 / ExoPlayer）——**唯一一份**。
     *
     * 2026-09-16：原来挂在 :app:feature-files 上（那时只有文件预览浮层用），现在媒体中心也要播，
     * 于是播放器核心（StreamHttpClient / ExoPlayer 构建 / PlayerView 布局）下沉到本模块，
     * feature-files 反过来依赖它 —— 不留两份播放器实现。
     */
    api(libs.media3.exoplayer)
    api(libs.media3.ui)
    api(libs.media3.extractor)
    api(libs.media3.datasource.okhttp)

    /*
     * MediaSession（2026-09-16）：音乐播放必须向系统广播"正在播什么"，否则锁屏、通知栏、
     * 快捷设置里都没有媒体控制，耳机上的暂停键也没人接。
     * 用 MediaSessionService 而不是在页面里自建 MediaSession + 自发通知：后者一旦页面销毁
     * 播放状态就没人维护，通知会与实际播放漂移。
     */
    api(libs.media3.session)
}

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ufi_axis.feature.files"
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
    /*
     * 播放器核心（2026-09-16 下沉到 :app:feature-media）。
     *
     * media3 依赖与 PlayerView 布局原来挂在本模块，媒体中心也要播之后，为了不留两份实现，
     * 播放器搬到 feature-media，这里反过来依赖它 —— 文件预览浮层继续用同一个播放器组件。
     */
    implementation(project(":app:feature-media"))

    // FileManagerScreen 解析 kotlinx.serialization JsonElement（OkHttp 经 coil.network.okhttp 透传）
    implementation(libs.kotlinx.serialization.json)


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

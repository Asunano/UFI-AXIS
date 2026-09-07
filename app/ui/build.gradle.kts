plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ufi_axis.ui"
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
    // ★ Compose 全套：合并自 ui-theme / ui-anim / ui-common / ui-nav 四模块依赖之和
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

    // ★ 协程：主题 ThemeManager（StateFlow）与 UfiToast（delay/launch）等源码直接使用
    implementation(libs.kotlinx.coroutines.android)

    // ★ 时间触发核心：CronParser / ScheduleValue / SchedulePreset（:core:common 经 api 向 ui 透传，
    // 此处显式声明以保证 ScheduleSelector 等公共组件可直接引用 com.ufi_axis_core.util.*）
    implementation(project(":core:common"))

    // ★ T15：ActionRegistry 的网络模式选项取自 contract（别名集与 core 映射同源）
    implementation(project(":core:contract"))

    // ★ 序列化：ActionRegistry 使用 JsonPrimitive
    implementation(libs.kotlinx.serialization.json)

    // ★ 生命周期：文档 A1 指定保留，供后续 UI 复用
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // ★ 图片加载（Coil3）：文档 A1 指定保留；改为 api 向依赖 ui 的 feature / :app 透传
    api(libs.coil.compose)
    api(libs.coil.network.okhttp)

    // ★ 单元测试：搬移 ColorTest 等 JVM 单测（无需 Robolectric）
    testImplementation(libs.junit)
}

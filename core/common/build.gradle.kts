plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ufi_axis_core.module_common"
    compileSdk = 36

    defaultConfig { minSdk = 31 }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // jCIFS-ng: SMB 客户端库，用于从设备自身连接 Samba 共享触发 root preexec（socat root shell）
    implementation(libs.jcifs.ng) {
        exclude(group = "org.slf4j")
    }

    // ===== 单元测试（QA 新增，仅 test 作用域，不影响正式构建） =====
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    // AppSettingsTest(Robolectric) 依赖 androidx.test.core.ApplicationProvider；
    // 该模块原先缺失此依赖，编译期 "Unresolved reference 'core'/'ApplicationProvider'"，
    // 补上后 core:common 单测方可编译运行（见 AppSettingsTest.kt 内注释）。
    testImplementation(libs.androidx.junit)
}

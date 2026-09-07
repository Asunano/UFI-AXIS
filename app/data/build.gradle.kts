plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.ufi_axis.data"
    compileSdk = 36
    defaultConfig {
        minSdk = 31
    }
    compileOptions {
        // 统一为 Java 17（与 core / app 模块保持一致）
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        // RetrofitClient 使用 BuildConfig.DEBUG 控制日志级别
        buildConfig = true
        // AIDL 跨进程接口（独立进程架构 v2 Phase 2）：INotificationConfigService.aidl
        // 生成类供 :app（NotifyService）与 :app:data（NotificationConfigClient）共用
        aidl = true
    }
    lint { abortOnError = false }
}

dependencies {
    // ★ 双端共享契约常量（端点/枚举/单位约定，纯 JVM 无 Android 依赖）
    //   用 api 而非 implementation：feature / viewmodel 模块也要引用同一份契约
    api(project(":core:contract"))

    // ★ 共享数据工具（DownsampledPoint 等，经 api 暴露给 app / feature 模块）
    api(project(":core:common"))

    // Network
    implementation(libs.retrofit.core)
    implementation(libs.okhttp.core)
    // kotlinx.serialization 运行时（C1：app/data 自有 model 迁移至 kotlinx.serialization）
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Room（CacheDatabase）
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // AndroidX 基础（Context / SharedPreferences / ConnectivityManager 等）
    implementation(libs.androidx.core.ktx)

    // WorkManager（后台守护周期任务：BackgroundGuardWorker / GuardScheduler）
    implementation(libs.androidx.work)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
}

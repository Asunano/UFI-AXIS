plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    // kotlinx.serialization 编译器插件：为 @Serializable 的 Bookmark/QuickPath 生成 serializer()，
    // 否则 FileShortcutRepository 中 AppJson.encodeToString<List<Bookmark>> 等无法解析 reified 重载。
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ufi_axis.viewmodel"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        // 统一为 Java 17（与 core / app / data 模块保持一致）
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        // BackgroundManager 含 @Composable rememberBackgroundManager()
        compose = true
    }
    lint { abortOnError = false }
}

dependencies {
    // ★ 共享数据层（DebugLog/NetworkMonitor/AppGson/CacheManager + data.api/model/repository + core:common）
    api(project(":app:data"))

    // OkHttp：module/* 与 state/* 直接依赖（data 模块以 implementation 声明，不会向 viewmodel 透传）
    implementation(libs.okhttp.core)
    // Retrofit：DownloadModule 现通过 UfiAxisApi 调用下载接口，其方法返回 retrofit2.Response<JsonElement>
    // （data 模块以 implementation 声明，不会向 viewmodel 透传），故此处显式引入以访问 Response 类型。
    implementation(libs.retrofit.core)
    // kotlinx.serialization（C1/#3a）：viewmodel 在 WS 热路径消费 WebSocketMessage.data（kotlinx JsonElement），
    // 并调用 AppJson.decodeFromJsonElement；data 模块以 implementation 声明，不会向 viewmodel 透传，故此处显式引入。
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)

    // ===== 单元测试（QA 新增，仅 test 作用域，不影响正式构建） =====
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // androidx.test.ext:junit 传递依赖 androidx.test:core，提供 ApplicationProvider
    // （DashboardModuleConcurrencyTest 等 Robolectric 测试依赖）
    testImplementation(libs.androidx.junit)
}

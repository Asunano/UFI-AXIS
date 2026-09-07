plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ufi_axis_core.controller"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:goform"))
    implementation(project(":core:collector"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    // Room (SmsController 直接使用 :core:database 的 Room DAO / Entity 类型)
    implementation(libs.androidx.room.runtime)
    // mail (for SmsForwardController - javax.mail)
    implementation(libs.javax.mail)
    implementation(libs.javax.activation)
}

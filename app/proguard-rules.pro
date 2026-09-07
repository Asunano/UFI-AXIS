# ── 体积优化尝试（2026-09-02）：已回退 ──
# -repackageclasses '' 在 core 侧被证实会踩死按名反射的第三方库（Netty 的
# ResourceLeakDetector.addExclusions 查 "toLeakAwareBuffer"，混淆后直接类初始化失败，
# 后端 HTTP 永远起不来）。app 侧虽然没复现，但收益只有包名字符串池那点零头，
# 不值得为它承担同类风险，一并撤掉。

# Kotlinx Serialization / Retrofit 通用属性（原 Gson 段仅保留属性，Gson 依赖已移除）
-keepattributes Signature
-keepattributes *Annotation*

# Kotlinx Serialization（app 自有 model）
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** { *** Companion; }
-keepclasseswithmembers class **$$serializer { *** INSTANCE; }

# Retrofit
-keepattributes Exceptions
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

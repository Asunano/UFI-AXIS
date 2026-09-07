# ── 体积优化尝试（2026-09-02）：已回退 ──
# -repackageclasses '' 连同"Netty 只 dontwarn 不 keep"一起，在 release/benchmark 包上
# 直接把后端打死：Netty 的 AbstractByteBufAllocator.<clinit> 里有
# ResourceLeakDetector.addExclusions(AbstractByteBufAllocator.class, "toLeakAwareBuffer")，
# 按**方法名字符串**做反射查找，被混淆改名后抛
# IllegalArgumentException: Can't find '[toLeakAwareBuffer]' in y0
# → ExceptionInInitializerError → NettyApplicationEngine.start() 抛 NoClassDefFoundError
# → HTTP 端口永远不监听，服务反复重启，通知栏一直停在"正在初始化组件..."。
# 体积上省下的是包名字符串池那点零头，不值得再碰。

# Ktor (收窄：仅保留实际用到的子包，移除 blanket io.ktor.** 全量 keep 以减小体积；
# R8 引用可达即保留，仍保留 -dontwarn 以防反射/ServiceLoader 相关告警)
# 保留：Ktor 经反射/ServiceLoader 加载插件，禁用 native transport；运行时冒烟通过前不得删除
-keep class io.ktor.server.** { *; }
-keep class io.ktor.client.** { *; }
-keep class io.ktor.http.** { *; }
-keep class io.ktor.util.** { *; }
-keep class io.ktor.serialization.** { *; }
-keep class io.ktor.websocket.** { *; }
-dontwarn io.ktor.**

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
}
-keepclasseswithmembers class **$$serializer {
    *** INSTANCE;
}

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*

# Netty
# 成员名必须保住：Netty 多处按方法名/字段名做反射（ResourceLeakDetector.addExclusions
# 的 "toLeakAwareBuffer"、PlatformDependent0 的 unsafe 字段探测等），改名即在类初始化阶段崩。
# 只锁名字不阻止 R8 删无用类，比 -keep class io.netty.** { *; } 省体积。
# 只锁名字不够：Netty 还用 ReflectiveChannelFactory 反射 new 出 channel
#（clazz.getConstructor()），R8 认为无参构造无人调用直接删掉，于是启动时抛
# IllegalArgumentException: Class NioServerSocketChannel does not have a public non-arg constructor。
# Netty 的反射面（channel 工厂、unsafe 字段探测、leak detector 排除名单）散落太广，
# 逐个 keep 就是打地鼠，直接整包保留 —— 后端能起来比省 1MB DEX 重要得多。
-keep class io.netty.** { *; }
-dontwarn io.netty.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep Ktor routing annotations
-keep class io.ktor.server.routing.** { *; }

# Keep WebSocket handler
-keep class com.ufi_axis_core.api.websocket.** { *; }

# Keep Room DAOs
-keep class com.ufi_axis_core.core.database.** { *; }

# SLF4J (used by Ktor/Netty, not needed on Android)
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.slf4j.**
-dontwarn reactor.blockhound.**

# JavaMail (SMTP forwarding)
-keep class javax.mail.** { *; }
-keep class com.sun.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.activation.** { *; }
-dontwarn javax.activation.**

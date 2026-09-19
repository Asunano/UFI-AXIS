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

# Keep 通知渠道控制器（controller.notify）
# release / benchmark 包里 R8 会把 WebhookConfig 混淆成 aa2，并在其 companion <clinit>
# （引用同包的 HttpNotifier.METHOD_NAMES / WebhookDelivery.PLACEHOLDERS）阶段抛
# NoClassDefFoundError，导致后端每次投递通知都崩、Webhook 端点（/api/notify/webhook/*）
# 报「后端不支持」。与 core.database / api.websocket 同口径：整包保留，防树摇 / 改名破坏
# 跨类静态初始化链。这类问题表面是"类找不到"，根因是 R8 把只被 <clinit> 间接引用的
# 内部类判断为可达性不足而剥掉 / 改名，与上方 Netty 那次同一类。
-keep class com.ufi_axis_core.controller.notify.** { *; }
# 通知核心域类型（WebhookConfig 的 companion 与 WebhookChannel 直接引用其中的 NotifyLevel /
# ChannelRules 等）。同样整包保留，避免跨包引用在 R8 下被改名 / 剥离而连累上面的初始化链。
-keep class com.ufi_axis_core.notify.** { *; }

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

# FFmpegKit：Java 层随 APK 走（core:api 的 implementation(files("libs/ffmpeg-kit-classes.jar"))），
# native 层（9 个 .so）是可选插件组件，运行时 System.load(绝对路径) 加载。
# keep 是必需的：Java 类只被反射（Class.forName）引用，R8 找不到静态引用会整包剥掉；
# 且 native 方法名/签名必须与 .so 里注册的一致，混淆会让 JNI 注册对不上。
-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**

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

# Apache Commons Net（FTP 存储源，阶段 5）
# DefaultFTPFileEntryParserFactory 对非内置 SYST 关键字走 Class.forName(key) 反射实例化
# 目录解析器（NT / OS400 / MVS / Netware / VMS 等）。R8 看不到这条引用会把它们剥掉，
# 表现为「连上服务器但列目录恒空」—— 与上面 Netty 那次同一类问题（反射面不可达即被删）。
# 整包保留：FTP 解析器一共十来个类，体积代价远低于排查成本。
-keep class org.apache.commons.net.** { *; }
-dontwarn org.apache.commons.net.**

# smbj（SMB/CIFS 存储源）：内部大量用反射解析 SMB 消息结构体与 NtStatus 枚举，
# R8 看不到这些引用；不 keep 的话混淆包能连上但解析响应时抛 NoSuchMethodException。
-keep class com.hierynomus.** { *; }
-dontwarn com.hierynomus.**
# smbj 依赖 bouncycastle 做 NTLM / SMB3 加密。注意：smbj 自带的 bcprov-jdk18on 已在
# core/api/build.gradle.kts 里排掉（与 jcifs-ng 的 bcprov-jdk15on 重类），运行期用的是
# jcifs-ng 那份，smbj 通过 Class.forName("org.bouncycastle.crypto.Digest") 探测后启用
# BCSecurityProvider —— 探测失败会退回 JCE，而 Android 的 JCE 没有 MD4，NTLM 直接不可用。
-dontwarn org.bouncycastle.**
# smbj 用 mbassador 当内部事件总线（连接/会话关闭事件），它按 @Handler 注解反射派发，
# 混淆掉方法名就收不到事件；整包保留（库本身很小）。
-keep class net.engio.mbassy.** { *; }
-dontwarn net.engio.mbassy.**
# mbassador 的 EL 过滤器引用 javax.el（Java EE 可选依赖，Android 上不存在）。
# 我们不用表达式过滤，dontwarn 即可 —— 不加这条 R8 直接以 Missing class 失败。
-dontwarn javax.el.**

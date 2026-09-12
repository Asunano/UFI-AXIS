# UFI-AXIS 手机安装器 ProGuard 规则（release 构建必需）
# app/build.gradle.kts 的 release 块引用本文件；缺失会导致 assembleRelease 直接失败。
# 安装器体量小，优先保证运行正确性，保守保留全部业务类与协议栈。

# Android 四大组件入口
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Application

# 视图绑定 / AndroidX
-keep class androidx.viewbinding.ViewBinding { *; }
-keep class * implements androidx.viewbinding.ViewBinding { *; }
-keep class androidx.** { *; }

# @Keep 注解保护的类与成员
-keep @androidx.annotation.Keep class * { *; }
-keepclasseswithmembers class * { @androidx.annotation.Keep <methods>; }

# 业务层与协议栈：全部保留（小项目，正确性优先于体积）
-keep class com.ufi_axis.installer.** { *; }
-keep class com.ufi_axis.adbcore.** { *; }

# 保留 kotlinx 协程，避免 Flow/协程被误优化导致运行时崩溃
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# 保留 Serializable / Parcelable 模型
-keepclassmembers class * implements java.io.Serializable { *; }
-keepclassmembers class * implements android.os.Parcelable { *; }

# 保留行号，便于崩溃栈定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "UFI-AXIS"
include(":core")  // 后端
include(":app")   // 前端

// ★ Phase 1 模块化重构：低风险模块抽取
include(":core:contract")   // 双端共享契约常量（纯 JVM，无任何依赖）
include(":core:device-schema") // 设备字段映射注册表（纯 JVM，仅依赖 contract）
include(":core:common")     // 工具类 + 基础设施（无依赖）
include(":core:database")   // Room 数据库（无 common 依赖）
include(":core:goform")     // Goform 协议客户端（依赖 common）
include(":core:collector")  // 数据采集器（依赖 common + database + goform）

// ★ Phase 2 模块化重构
include(":core:cache")      // API 响应缓存（依赖 common）
include(":core:websocket")  // WebSocket 连接管理（依赖 common）
include(":core:alert")      // 智能告警引擎（依赖 common + database + websocket）
include(":core:scheduler")  // 数据采集调度器（依赖 common + database + collector + controller + websocket）
include(":core:controller") // 控制器层（依赖 common + database + goform + collector）
include(":core:api")        // API 路由 + 中间件（依赖 common + controller + cache + scheduler 等）
include(":core:network")    // HTTP 服务器（依赖 common + api + websocket）

// ★ Phase 3 App UI 核心层
include(":app:ui")           // 合并后的统一 UI 模块（主题/动画/通用组件/导航骨架）

// ★ Phase 4 App 数据层 + Feature 模块
include(":app:data")        // 数据层（data + util：api/repository/model/cache + 工具类）

// ★ 共享 ViewModel 层（MainViewModel + module/* + state/* + BackgroundManager）
//   feature 模块依赖它而非 :app，避免与入口模块产生循环依赖
include(":app:viewmodel")

// ★ UI 页面 Feature 模块（依赖 :app:viewmodel + :app:data + :app:ui）
include(":app:feature-settings")   // 设置（Settings/ServerConfig/Setup）
include(":app:feature-network")    // 网络（Network + 二级页 + DHCP/WiFi 等弹窗）
include(":app:feature-dashboard")  // 仪表盘（Dashboard + 5 详情子页）
include(":app:feature-tools")      // 工具（Tools/Advanced/Console/SpeedTest/Traffic/Task/DebugLog）
include(":app:feature-files")      // 文件管理（FileManager/Text/Image/Media）
include(":app:feature-download")   // 下载管理（Download）
include(":app:feature-sms")        // 短信（Sms/SmsForward）
include(":app:feature-apps")       // 应用管理（App/Adb）
include(":app:feature-monitor")    // 监控中心（Monitor）

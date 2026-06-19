# UFI-AXIS

> 随身 WiFi 设备的全能管理平台 — 后端服务 + 手机客户端，一体化仓库

UFI-AXIS 由两个 Android 应用组成：**Core**（后端）部署在随身 WiFi 设备上作为系统服务运行，**App**（前端）安装在用户手机上进行远程监控与控制。两者通过 HTTP API 和 WebSocket 实时通信。

## 功能特性

**Core — 设备端后端服务**

- Ktor (Netty) REST API + WebSocket 实时推送
- AT 指令通道直接操控基带（信号查询、短信收发、网络切换）
- 系统数据采集（CPU/内存/温度/电池/流量/信号）与持久化存储
- aria2 下载引擎集成（HTTP/FTP/BT/magnet），智能限速与温控
- 7 种智能告警引擎（流量/温度/CPU/内存/电池/设备上下线）
- Goform 协议兼容（适配中兴设备 Web 管理接口）
- Magisk 模块打包，开机自启、systemless 部署

**App — 手机客户端**

- 实时仪表盘 — 信号、流量、温度、CPU、内存、电池等核心状态
- 信号监控 — RSRP/SINR/RSRQ 实时追踪，5 级信号格 + 网络制式识别
- 流量统计 — 日/月流量记录，历史趋势图表
- 文件管理器 — 远程浏览、上传、下载设备文件
- 下载管理 — 远程操控 aria2，任务增删暂停，Tracker 管理
- 短信管理 — 远程查看、发送、删除短信
- 桌面小组件 — 4×2 / 2×1 / 4×1 多尺寸
- Material You 动态配色、5 种预设主题、深色模式
- WebSocket 订阅，数据变更即时推送到手机

## 系统要求

| 项目 | 要求 |
|------|------|
| Android 版本 | 12+ (API 31) |
| 目标版本 | Android 16 (API 36) |
| JDK | 17+ |
| 构建工具 | Gradle 9.4.1 / AGP 9.2.1 |

## 快速开始

### 1. 部署后端（Core）

将 Core 模块打包为 Magisk 模块，刷入随身 WiFi 设备：

```bash
# 构建 Core APK
./gradlew :core:assembleDebug

# 打包 Magisk 模块（需设备端已安装 Core APK）
./package-magisk.sh
```

### 2. 安装客户端（App）

```bash
./gradlew :app:assembleDebug
# APK 输出: app/build/outputs/apk/debug/app-debug.apk
```

### 3. 配置连接

打开手机端 App，在配置页面填写：

- **后端地址**：随身 WiFi 设备的 IP 地址（如 `192.168.0.1`）
- **端口**：`8088`（默认）
- **Token**：后端配置的认证令牌

### 4. 添加小组件

回到桌面 → 长按 → 添加小组件 → 选择「UFI-AXIS」

## 架构

```
┌──────────────────┐     HTTP/WS (8088)     ┌──────────────────────┐
│   :app (前端)     │ ◄────────────────────► │   :core (后端)        │
│   用户手机        │                        │   随身 WiFi 设备       │
│                  │                        │                      │
│  - 仪表盘/图表    │                        │  - Ktor REST API     │
│  - 文件管理       │                        │  - WebSocket 推送     │
│  - 下载管理       │                        │  - AT/Goform 控制     │
│  - 短信管理       │                        │  - 数据采集/Room DB   │
│  - 桌面小组件     │                        │  - aria2 下载引擎     │
│  - 告警通知       │                        │  - 告警引擎           │
└──────────────────┘                        └──────────────────────┘
```

## 构建

```bash
# 同时构建两个模块
./gradlew assembleDebug

# 仅构建后端
./gradlew :core:assembleDebug

# 仅构建前端
./gradlew :app:assembleDebug
```

APK 输出路径：

- 后端：`core/build/outputs/apk/debug/core-debug.apk`
- 前端：`app/build/outputs/apk/debug/app-debug.apk`

## 项目结构

```
UFI-AXIS/
├── settings.gradle.kts             # 多模块配置
├── build.gradle.kts                # 共享插件声明
├── gradle/libs.versions.toml       # 统一版本目录
├── core/                           # 后端模块
│   └── src/main/java/com/ufi_axis_core/
│       ├── MainActivity.kt         # 服务入口 Activity
│       ├── api/                    # Ktor API 路由
│       ├── collector/              # 系统数据采集
│       ├── controller/             # 业务控制器（下载、网络等）
│       ├── core/                   # 调度器、线程池
│       ├── alert/                  # 告警引擎
│       ├── service/                # Android 前台服务
│       └── util/                   # 工具类
├── app/                            # 前端模块
│   └── src/main/java/com/ufi_axis/
│       ├── MainActivity.kt         # 主界面
│       ├── data/                   # 数据层（API/缓存）
│       ├── ui/                     # Compose UI 组件与页面
│       ├── viewmodel/              # ViewModel
│       └── util/                   # 工具类
├── module/                         # Magisk 模块模板
├── docs/                           # 文档
└── package-magisk.sh               # Magisk 打包脚本
```

## 许可证

[MIT License](LICENSE)

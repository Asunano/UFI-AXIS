# UFI-AXIS Core 安装器（Android 端）

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Platform: Android">
  <img src="https://img.shields.io/badge/minSdk-26%2B-blue.svg" alt="minSdk: 26+">
  <img src="https://img.shields.io/badge/compileSdk-36-blue.svg" alt="compileSdk: 36">
  <img src="https://img.shields.io/badge/Written%20in-Kotlin-orange.svg" alt="Written in: Kotlin">
  <img src="https://img.shields.io/badge/ADB-over--TCP-5555-purple.svg" alt="ADB over TCP 5555">
</p>

> 本目录是 **UFI-AXIS 项目的一部分**，不是独立第三方仓库。它把 UFI-AXIS 配套 PC 端的
> `install.bat` / `install.sh` 命令行安装流程，重做成一个跑在 Android 手机上的图形化安装器，
> 用于把 UFI-AXIS 的 Core 后端（`com.ufi_axis_core`，监听 `0.0.0.0:8088`）推送到设备并拉起。

---

## 一、它是什么 / 解决什么问题

UFI-AXIS 的 Core 后端需要常驻运行在设备或网关上。最省事的部署方式是电脑上跑 `adb` + 安装脚本，
但很多现场场景**手边只有手机、没有电脑**：

- 设备刚刷好系统，希望用手机点一下就把 Core 推上去；
- 现场运维，不想每次都翻出笔记本开 `adb`；
- 想给不懂命令行的用户一个「输入地址 → 点安装 → 看结果」的闭环。

为此，本安装器用一份**纯 JVM 的 ADB 协议栈**在 Android 上重做了整条链路
（`adb connect` / `pm install` / `pm grant` / `am start` / `curl /health`），
UI 复用 UFI-AXIS 主应用（客户端）统一的设计语言。全流程与 PC 端脚本一一对应。

---

## 二、功能特性

- **图形化全流程**：连接 → 推送 → 安装 → 授权 → 启动 → 健康检查，单页完成，进度实时可见。
- **安装健壮性**：推送式 `pm install -r -d` 为主路径；当设备因 SELinux `restorecon` 失败
  报 `INSTALL_FAILED_MEDIA_UNAVAILABLE` 时，自动回退到 **`pm install -S <size>` 流式安装**
  （APK 经 stdin 交给 pm，由 pm 自己打上下文），仍失败再回退推到 `/sdcard`。
- **内置 Core APK**：Core 以 `noCompress` 原样打进 `assets/`，安装器只做分发与安装，不依赖外部文件。
- **三级包名识别**：固定包名 → 第三方包差集 → 手动输入，逐级兜底。
- **权限授予不中断**：12 项权限逐项 `pm grant`，失败自动回退 `appops set`，单项失败不影响整体。
- **统一设计语言**：对齐 UFI-AXIS 主应用（客户端）默认中性皮肤（近黑实底 + 中性灰阶 + 12dp 圆角 +
  柔和灰影），并内置**深色模式**（跟随系统）。
- **前台服务保障**：安装全程挂在前台服务里，锁屏 / 切后台不被冻结，通知栏实时显示进度。
- **可分享日志**：内存 + 落盘 + 界面实时彩显，一键复制 / 分享，格式与 PC 端脚本日志一致。

---

## 三、与 PC 端安装脚本一一对照

| # | PC 端脚本的动作 | 安装器里的对应实现 |
|---|---|---|
| 1 | 检查 `adb.exe` / `core/` / `core/*.apk` | 启动时检查 `assets/ufi-axis-core/`，缺文件直接禁用「开始安装」 |
| 2 | 输入设备地址，无端口补 `:5555`，抽出纯 IP | `AddressParser`，还会剥掉从教程复制的 `adb connect ` 前缀 |
| 3 | `adb connect` + 校验 `device` 状态，失败给检查清单、最多重试 5 次 | `InstallEngine.connectWithRetry()`，失败时弹「重试 / 改地址 / 取消」 |
| 4 | 列出待装 APK，问 Y/N | 安装前 AlertDialog，显示 APK 名 + 大小 + 目标地址 |
| 5 | `pm list packages -3` → 逐个 `adb install -r -d` | 推送 + `pm install -r -d`，带百分比进度；restorecon 失败自动回退 `pm install -S` |
| 6 | 识别包名（固定 → 差集 → 手工） | `InstallEngine.resolvePackageName()` 三级回退 |
| 7 | 12 项权限 `pm grant`，失败回退 `appops set` | `PermissionGranter`，逐项打日志，单项失败不中断 |
| 8 | `cmd package resolve-activity` → `am start`，失败回退 `monkey` | `AppLauncher` |
| 9 | `timeout 5` 后 `curl /health`，6 次 × 5 秒，判 `status` + `ok` | `HealthChecker`，同样 6 次 × 5 秒 |
| 10 | 全程写 `log/install_<时间戳>.log` | `InstallLogger` 落盘 + 界面实时彩显 + 一键分享/复制 |

---

## 四、工程结构

```
UFI-AXIS-Core-install-Android/
├── adbcore/                                 纯 JVM 模块：ADB 协议栈（可单元测试）
│   └── src/main/kotlin/com/ufi_axis/adbcore/
│       ├── AdbProtocol.kt      报文编解码（24 字节头 / CRC32 / 小端）
│       ├── AdbCrypto.kt        RSA 密钥、SHA1withRSA 签名、524 字节公钥编码
│       ├── AdbConnection.kt    TCP + 握手状态机 + 单读线程分发 + 设备能力(features)解析
│       ├── AdbStream.kt        流缓冲与按需读取
│       ├── AdbShell.kt         shell v2（回退 v1）命令执行 + stdin 流式写入
│       ├── AdbSync.kt          push 文件（SEND/DATA/DONE，兼容 sendrecv_v2）
│       ├── AdbClient.kt        门面：装包 / 授权 / 启动 / 查包（含 layered install 回退）
│       └── AdbException.kt     异常体系
│
└── app/                                      Android 应用层
    ├── src/main/java/com/ufi_axis/installer/
    │   ├── InstallerApp.kt       Application
    │   ├── MainActivity.kt       单页界面（XML/View，遵循 UFI-AXIS 设计令牌）
    │   ├── core/
    │   │   ├── AddressParser.kt        地址解析
    │   │   ├── AssetApkProvider.kt     内置 APK 枚举 / 挑选 / 落地
    │   │   ├── PermissionGranter.kt    12 项权限授权
    │   │   ├── AppLauncher.kt          启动应用
    │   │   ├── HealthChecker.kt        /health 探活
    │   │   └── NotificationHelper.kt   前台通知
    │   ├── logging/
    │   │   ├── LogLine.kt              日志行模型
    │   │   └── InstallLogger.kt        内存 + 落盘 + 分享
    │   ├── state/
    │   │   ├── InstallStage.kt         阶段枚举
    │   │   ├── InstallState.kt         状态快照
    │   │   └── InstallEngine.kt        流程编排（单例）
    │   ├── service/
    │   │   └── InstallerService.kt     前台服务
    │   └── ui/
    │       └── LogAdapter.kt           日志列表适配器
    └── src/main/res/values*/          设计令牌（颜色 / 字号 / 圆角 / 暗色主题）
```

**为什么拆两个模块**：`adbcore` 是纯 JVM 的，不依赖任何 Android API，
所以可以用一个假的 ADB 服务端（`FakeAdbServer`）在普通单元测试里跑通全部协议逻辑。
这层拆分让协议实现能在没有真机、甚至没有 Android SDK 的环境下被验证。

---

## 五、构建

### 环境要求

- JDK 17+
- Android SDK（`compileSdk = 36`，`minSdk = 26` 即 Android 8.0+）
- Gradle 8.13+（仓库已带 wrapper，无需另行安装）
- 构建机至少有 ~2GB 可用内存（协议层编译 + 打包 Core APK）

### 命令

```bash
./gradlew :adbcore:test             # 协议层单元测试
./gradlew :app:testDebugUnitTest    # 业务层单元测试
./gradlew :app:assembleDebug        # 调试包
./gradlew :app:assembleRelease      # 发布包（未签名，需另行签名）
```

调试包产物：`app/build/outputs/apk/debug/app-debug.apk`
发布包产物：`app/build/outputs/apk/release/app-release-unsigned.apk`

> **依赖镜像**：`settings.gradle.kts` 已把国内镜像源放在首位。
> 如果你有更快的源，直接改这个文件即可，无需改动其他配置。

---

## 六、把 Core APK 注入安装器

安装器只负责**分发和安装** Core，Core 本身的 APK 由 UFI-AXIS 的 release 工作流产出后注入。

1. 构建出 Core APK；
2. 复制到 `app/src/main/assets/ufi-axis-core/`，建议带版本号：

   ```
   app/src/main/assets/ufi-axis-core/ufi-axis-core-v1.2.0.apk
   ```

3. 重新构建安装器 APK 即可。

**挑选规则**（`AssetApkProvider.pickCore`）：

| 场景 | 结果 |
|---|---|
| 目录里只有一个 apk | 直接选中 |
| 多个，文件名含 `core` | 选中含 `core` 的那个 |
| 多个，含 `ufi` 或 `axis` | 选中那一个 |
| 仍然不唯一 | 报错并要求人工确认 |

如果同时放了 Core 和 App 端的 APK，**务必让 Core 的文件名含 `core`**，否则会挑不出。
按当前约定，安装器**只装 Core，不推 App 端**。

**关键构建配置**（`app/build.gradle.kts`）：

```kotlin
androidResources { noCompress += "apk" }
```

这行是必须的。APK 被 AAPT 压缩后再解出来字节会损坏，
推送过去会报 `INSTALL_FAILED_INVALID_APK`。

---

## 七、设计语言（UI）

UI 复用 UFI-AXIS 主应用（客户端）**统一设计系统**，保证多个端界面观感一致。

- **默认中性皮肤**：accent 为近黑实底（`#222222` 浅色态 / `#B0B0B0` 暗色态），
  页面近白、卡面纯白、分隔线发丝灰；圆角统一 `12dp`；卡片用 1dp 描边 + 柔和灰影
  （`#9CA4AC @30%`）；字号阶梯 22 / 16 / 14 / 12 / 11sp，日志用等宽。
- **深色模式**：`res/values-night/` 提供完整夜间配色，跟随系统深色模式自动切换。
- **设计令牌集中管理**：颜色在 `res/values/colors.xml`，字号/间距/圆角在 `styles.xml` 与
  `drawable/*`，改一套即全站生效。

> UFI-AXIS 主应用内置 6 套彩色皮肤（玫红 / 金黄 / 柠绿 / 翠绿 / 宝蓝 / 紫色）。
> 若你更想要彩色强调色，只改 `colors.xml` 里的 `brand` / `brand_dark` / `white_text`
> 三个值即可（其余令牌无需动）。

---

## 八、真机验证

协议层虽然过了单测，但**单测用的是模拟设备**。首次上真机请按这个顺序走一遍：

### 第 1 步：确认设备侧就绪

```bash
# 在能连到设备的电脑上先手工验证一遍，排除设备问题
adb connect 192.168.0.1:5555
adb devices      # 应显示 device，不是 unauthorized
```

如果这里就不通，安装器也一定不通，先解决设备侧。
常见原因：无线调试没开、不在同一局域网、设备弹窗没点允许。

### 第 2 步：装安装器

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 第 3 步：观察日志

打开 App，点「开始安装」，然后展开底部的「运行日志」。
建议同时开一个终端跑：

```bash
adb logcat | grep -i ufi
```

### 第 4 步：重点盯这几个点

| 阶段 | 正常表现 | 如果卡住 |
|---|---|---|
| 连接设备 | 日志出现 `已连接：device::...` | 检查 5555 是否可达；若提示设备要求允许，去设备上点确认 |
| 推送 APK | 进度条走到 100% | 卡住多半是 sync 协议问题，抓日志 |
| 识别包名 | 出现 `命中固定包名：com.ufi_axis_core` | 若走差集或要求手输，说明包名和预期不符，看日志里的候选 |
| 授予权限 | 12 行 `[n/12] PERMISSION` | 失败项会标 `（失败，已跳过）`，属正常，部分 ROM 不支持个别权限 |
| 启动应用 | `启动指令已发送（am start）` | 回退到 monkey 也可接受 |
| 健康检查 | `健康检查通过（第 1 次）` | 失败会连试 6 次，每次间隔 5 秒，失败时日志会附带「目标进程是否在运行」 |

### 第 5 步：出问题就导出日志

点「分享日志」，日志文件会以 `install_<时间戳>.log` 的格式发出去，
内容和 PC 端脚本的日志风格一致（`[HH:mm:ss.SSS] [LEVEL] message`），便于逐行比对。

---

## 九、实现说明 / 设计取舍

### 安装失败会自动换路径，但优先走推送

主路径是推到 `/data/local/tmp` 再 `pm install -r -d`。部分设备（尤其小米等）的 SELinux 策略
会在 `pm install` 阶段对推送文件做 `restorecon` 上下文重打并失败，报
`INSTALL_FAILED_MEDIA_UNAVAILABLE: Failed to restorecon`。此时自动回退：

1. **流式安装 `pm install -S <size>`**：把 APK 经 shell v2 的 stdin 直接交给 `pm`，
   由 `pm` 自己把文件落到 staging 区并打上正确的 SELinux 上下文，从根上绕开 restorecon。
   仅当设备上报 `features` 含 `shell_v2` 时才启用（stdin 依赖 v2 帧）。
2. 流式仍失败 → 回退推到 `/sdcard` 再安装。

每次推送前都会先 `rm -f` 残留的同名文件，避免上次失败遗留的「坏上下文」文件导致再次 restorecon。

### 明文 HTTP 是全开的

`res/xml/network_security_config.xml` 里 `cleartextTrafficPermitted="true"`。

原因是 **Android 的网络安全配置不支持 CIDR 网段**，只能逐个列举域名/IP，
而设备地址是用户运行时输入的，没法预先枚举。

实际暴露面很小：明文流量只用于局域网内的 ADB 端口 5555 和健康检查端口 8088，
目标由用户自己指定，不涉及任何第三方数据上报。
若要收紧，建议在代码里校验目标是否为私网地址（`10./172.16-31./192.168.`），而不是靠 XML。

### 只支持 5555 直连，不支持无线调试配对

Android 11+ 的「无线调试」用的是 TLS + SPAKE2 配对码，握手流程完全不同。
本安装器只实现了传统 `adb tcpip 5555` 那条路径。
如果设备只有无线调试、没法开 5555，握手阶段会抛 `AdbTlsRequiredException` 并给出提示。

### 连接期间必须保持屏幕

安装全程可能要 1~3 分钟（推 APK + 12 项授权 + 启动等待 5 秒 + 健康检查最多 30 秒）。
这段逻辑挂在**前台服务**里，锁屏或切后台都不会被冻结，通知栏会实时显示进度。

### `am start` 的成功判定是「尽力而为」

`am start` 就算目标 Activity 起不来，exit code 也常常是 0。
所以安装器不把它的返回值当最终结论，真正的判据是之后的健康检查。

---

## 十、测试覆盖

| 模块 | 覆盖内容 |
|---|---|
| `adbcore` | 报文编解码、CRC 字节序、RSA 签名与 524 字节公钥编码、shell v2 分帧、sync 传输、流 ID 映射、设备 `features` 解析、restorecon → `pm install -S` 回退（基于假服务端） |
| `app` | 地址解析（含 PC 端脚本的边界行为）、健康检查（真实 HTTP 往返、非 2xx 判定、关键字大小写） |

跑全部测试：

```bash
./gradlew :adbcore:test :app:testDebugUnitTest
```

> App 层的 UI（`MainActivity`）、前台服务（`InstallerService`）和需要 Android 框架的
> `AssetApkProvider` / `NotificationHelper` 属于**仪器测试**范畴，
> 需要真机或模拟器，当前仓库未包含，必须按上面的「真机验证」步骤走一遍。

---

## 十一、反馈与贡献

本组件随 UFI-AXIS 主仓库一起维护。发现安装异常请优先按第八节导出日志，
并在 UFI-AXIS 仓库提交 Issue；欢迎提交 Pull Request 改进协议层或安装流程。

# 打包说明（Core 安装器 · 给 release 工作流 / 贡献者）

本文档说明如何把 UFI-AXIS 的 Core APK 注入本安装器，以及如何产出可发布的安装器 APK。
本组件是 UFI-AXIS 项目的一部分，源码位于 `scripts/UFI-AXIS-Core-install-Android/`
（独立嵌套 Gradle 工程，自身带 wrapper；因与主仓库 Gradle/AGP 版本代差较大，不并入主构建）。

---

## 一、注入 Core APK

### 目录位置

```
app/src/main/assets/ufi-axis-core/
```

### 步骤

1. 先构建出 Core 的 APK；
2. 复制到上面这个目录，**保留 `.apk` 后缀**；
3. 构建安装器。

### 文件名必须遵循的规则

安装器按以下优先级挑选 Core（`AssetApkProvider.pickCore`）：

| 优先级 | 条件 | 结果 |
|---|---|---|
| 1 | 目录里只有 1 个 apk | 直接选中 |
| 2 | 文件名含 `core`（不分大小写） | 选中它 |
| 3 | 文件名含 `ufi` 或 `axis` | 选中它 |
| 4 | 仍不唯一 | **报错**，要求人工确认 |

> **重要**：如果同时放了 Core 和 App 端的 APK，**Core 的文件名必须含 `core`**，
> 否则会走到第 3 步、甚至第 4 步而挑选失败。

推荐的命名：

```
ufi-axis-core-v1.2.0.apk
```

版本号写进文件名，方便用户从 App 日志里确认装的是哪一版。

---

## 二、构建产物

```bash
# 协议层单元测试
./gradlew :adbcore:test

# 业务层单元测试
./gradlew :app:testDebugUnitTest

# 调试包
./gradlew :app:assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk

# 发布包（开启混淆与资源压缩，未签名）
./gradlew :app:assembleRelease      # → app/build/outputs/apk/release/app-release-unsigned.apk
```

---

## 三、GitHub Actions 集成（已落地 `release.yml`）

UFI-AXIS 主仓库的 `release.yml` 在产出 PC 端一键安装器 ZIP 之后，会自动完成本安装器的构建与发布：

1. 把已签名改名的 Core 包 `UFI-AXIS-core-<backend 版本>.apk`
   注入 `scripts/UFI-AXIS-Core-install-Android/app/src/main/assets/ufi-axis-core/ufi-axis-core-<backend 版本>.apk`；
2. `working-directory: scripts/UFI-AXIS-Core-install-Android` 下执行 `./gradlew :app:assembleRelease`；
3. 用 `r0adkll/sign-android-release` 复用 UFI-AXIS 的 `SIGNING_KEY` 密钥签名；
4. 改名 `UFI-AXIS-Core-install-Android-<backend 版本>.apk` 并作为 Release 附件发布。

> 本地手动构建可参考以下片段（CI 已自动完成，一般无需手动跑）：

```yaml
- name: 注入 Core APK 到安装器
  run: |
    mkdir -p scripts/UFI-AXIS-Core-install-Android/app/src/main/assets/ufi-axis-core
    cp path/to/ufi-axis-core.apk \
       scripts/UFI-AXIS-Core-install-Android/app/src/main/assets/ufi-axis-core/ufi-axis-core-<版本>.apk

- name: 构建安装器
  working-directory: scripts/UFI-AXIS-Core-install-Android
  run: ./gradlew :app:assembleRelease

- name: 上传安装器 APK
  uses: actions/upload-artifact@v6
  with:
    name: ufi-axis-installer
    path: scripts/UFI-AXIS-Core-install-Android/app/build/outputs/apk/release/*.apk
```

---

## 四、两个容易踩的坑

### 1. APK 不能被压缩

`app/build.gradle.kts` 里已有：

```kotlin
androidResources { noCompress += "apk" }
```

**这一行不能删**。若 APK 被 AAPT 压缩，安装器解出来的字节会损坏，
推送过去 `pm install` 会报 `INSTALL_FAILED_INVALID_APK`。

### 2. 空目录不会被打包

AAPT 会丢弃空目录。如果 `assets/ufi-axis-core/` 下没有任何文件，
该目录不会进入 APK，安装器启动时会提示「未检测到内置 APK」。

目录里已保留 `README.txt` 作为占位，**不要删除**。
它不会被识别为 APK（安装器只匹配 `*.apk`），可以安全保留。

---

## 五、发布包签名

`assembleRelease` 产出的是 **unsigned** APK，需要自行签名后才能分发。
在 `app/build.gradle.kts` 里补充 `signingConfigs`，或在工作流里用
`apksigner` / `zipalign` 处理。

调试用的话，直接发 `app-debug.apk` 也可以正常安装。

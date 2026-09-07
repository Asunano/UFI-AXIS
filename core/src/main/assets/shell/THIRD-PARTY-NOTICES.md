# 第三方组件许可声明（Third-Party Notices）

core 随 APK 分发的 `assets/shell/` 目录下包含若干上游项目的预编译二进制（ARM/aarch64），
运行时释放到设备的 `/data/local/tmp/` 下执行。这些文件**不是**本项目的代码，各自适用下方所列许可证。

本项目的构建脚本仅对上述二进制做打包与释放，未做任何修改；各组件的完整许可证文本
与源码均可通过下方"上游"链接获取。

## GPL 系组件

### aria2c — GPL-2.0-or-later（附 OpenSSL 例外条款）

- 用途：多线程下载引擎（下载中心经 RPC 调用）
- 版本：1.37.0（二进制内嵌版本串 `aria2/1.37.0`）
- 上游项目：<https://github.com/aria2/aria2>
- 对应源码：<https://github.com/aria2/aria2/releases/tag/release-1.37.0>

### socat — GPL-2.0（附 OpenSSL 例外条款）

- 用途：端口/套接字转发（隧道与端口映射链路）
- 上游项目：<https://github.com/lilydjwg/socat>
  （完整源码 fork，含 `COPYING` / `COPYING.OpenSSL` / `configure.ac` 及本二进制的
  Android 交叉编译脚本 `socat_buildscript_for_android.sh`）
- 原始上游：<http://www.dest-unreach.org/socat/>

上述 GPL 组件的完整对应源码，可通过"对应源码 / 上游项目"链接自行获取；
本项目的 GitHub Release 亦随附相应源码资产。

## 宽松许可组件

| 组件 | 许可证 | 用途 | 上游 |
| --- | --- | --- | --- |
| `adb` | Apache-2.0 | ADB 客户端。core 经 loopback（`localhost:5555`）连接本机 adbd 获取特权 shell | <https://android.googlesource.com/platform/packages/modules/adb/> |
| `ttyd` | MIT | Web 终端（把 shell 暴露为网页终端） | <https://github.com/tsl0922/ttyd> |
| `curl` | curl license（MIT/X 派生） | shell 侧 HTTP 探测与下载（二进制内嵌 `curl 8.0.1`） | <https://curl.se/> |
| `jq` | MIT | shell 侧 JSON 解析 | <https://github.com/jqlang/jq> |

各组件的许可证全文与版权声明见其上游仓库。

## 本项目自有脚本

`samba_exec.sh` / `ufi_keepalive.sh` / `ufi_update.sh` 为本项目代码，
适用仓库根目录的 `LICENSE`，不属上述第三方组件清单。

---

以上信息仅用于开源许可合规声明，不构成法律意见。

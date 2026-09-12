占位文件 —— 请勿删除。

安装器在构建时（CI release 流程或手动）会把 core APK 注入本目录，
文件名形如 ufi-axis-core-vX.Y.Z.apk。AAPT 会丢弃空目录，缺少本占位会导致
assets/ufi-axis-core/ 不进 APK，运行时报“未检测到内置 APK”。

详见仓库 scripts/UFI-AXIS-Core-install-Android/BUILD.md。

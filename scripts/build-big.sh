#!/usr/bin/env bash
# 只用 P-core（cpu0–cpu15）跑 Gradle。daemon 亲和性检查、并行度上限都在
# ~/Code/bin/build-big 里，这里不再重复实现，避免两份逻辑走样。
#
# 用法：scripts/build-big.sh :app:assembleBenchmark [其它 Gradle 参数]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# 兼容旧变量名
export BIG_CORES="${BIG_CORES:-${UFI_BIG_CORES:-0-15}}"
exec "$HOME/Code/bin/build-big" ./gradlew "$@"

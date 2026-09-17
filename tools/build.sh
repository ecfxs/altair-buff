#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 构建脚本
#
# 用法:
#   bash tools/build.sh              # 构建 debug + release APK
#   bash tools/build.sh clean        # 清理
#   bash tools/build.sh :probe:assembleDebug
#
# 依赖 tools/setup-toolchain.sh 装好的工作区内工具链。
# ---------------------------------------------------------------------------
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TC="$ROOT/.toolchain"

if [ ! -f "$TC/env.sh" ]; then
  echo "!! 工具链未就绪，请先运行: bash tools/setup-toolchain.sh"
  exit 1
fi
# shellcheck source=/dev/null
source "$TC/env.sh"

cd "$ROOT"

if [ "${1:-}" = "clean" ]; then
  "$TC/gradle-8.2/bin/gradle" clean --no-daemon
  exit $?
fi

TASK="${1:-assembleDebug assembleRelease}"
echo "=============================================="
echo " JAVA_HOME = $JAVA_HOME"
echo " SDK       = $ANDROID_SDK_ROOT"
echo " 任务      = $TASK"
echo "=============================================="

# shellcheck disable=SC2086
"$TC/gradle-8.2/bin/gradle" $TASK --no-daemon --stacktrace
RC=$?

echo
if [ $RC -eq 0 ]; then
  echo "======== 构建产物 ========"
  find "$ROOT" -name "*.apk" -not -path "*/intermediates/*" -not -path "*/.gradle/*" \
    -exec ls -lh {} \; 2>/dev/null | awk '{print "  "$9"  "$5}'
else
  echo "构建失败 (exit $RC)"
fi
exit $RC

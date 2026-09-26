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
# 可通过 ALTAIR_BUILD_TASKS 选择任务，并通过 ALTAIR_VERSION_NAME/CODE 覆盖产物版本。
# 正式发布还需 ALTAIR_STORE_FILE/ALTAIR_STORE_PASSWORD/ALTAIR_KEY_ALIAS/ALTAIR_KEY_PASSWORD。
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

TASK="${1:-${ALTAIR_BUILD_TASKS:-assembleDebug assembleRelease}}"
VERSION_ARGS=()
[ -n "${ALTAIR_VERSION_NAME:-}" ] && VERSION_ARGS+=("-PaltairVersionName=$ALTAIR_VERSION_NAME")
[ -n "${ALTAIR_VERSION_CODE:-}" ] && VERSION_ARGS+=("-PaltairVersionCode=$ALTAIR_VERSION_CODE")
echo "=============================================="
echo " JAVA_HOME = $JAVA_HOME"
echo " SDK       = $ANDROID_SDK_ROOT"
echo " 任务      = $TASK"
echo "=============================================="

# shellcheck disable=SC2086
"$TC/gradle-8.2/bin/gradle" $TASK "${VERSION_ARGS[@]}" --no-daemon --stacktrace
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

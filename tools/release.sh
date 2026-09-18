#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 构建 + 发布到 GitHub Release
#
# 用法:
#   bash tools/release.sh 0.4.0              # 用当前 git 仓库
#   bash tools/release.sh 0.4.0 owner/repo   # 指定仓库（不存在则创建）
#
# 发布后得到两个 URL：
#   固定 URL（推荐，永远指向最新）:
#     https://github.com/<owner>/<repo>/releases/latest/download/probe-release.apk
#   版本 URL:
#     https://github.com/<owner>/<repo>/releases/download/v<ver>/probe-release.apk
#
# 固定 URL 配进 App 一次即可，以后发新版自动生效。
# ---------------------------------------------------------------------------
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
# shellcheck source=/dev/null
[ -f "$ROOT/.toolchain/env.sh" ] && source "$ROOT/.toolchain/env.sh"

VER="${1:-}"
REPO="${2:-}"

if [ -z "$VER" ]; then
  echo "用法: bash tools/release.sh <版本号> [owner/repo]"
  exit 1
fi

# ---------------------------------------------------------------- 前置检查
command -v gh >/dev/null || { echo "!! 未安装 gh CLI"; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "!! gh 未登录，先执行 gh auth login"; exit 1; }
OWNER="$(gh api user -q .login 2>/dev/null)"
echo "GitHub 账号: $OWNER"

# ---------------------------------------------------------------- 1) 改版本号
CUR_VER=$(grep -oE 'versionName = "[^"]+"' probe/build.gradle.kts | head -1 | sed 's/.*"\(.*\)"/\1/')
CUR_CODE=$(grep -oE 'versionCode = [0-9]+' probe/build.gradle.kts | head -1 | grep -oE '[0-9]+')
if [ "$CUR_VER" != "$VER" ]; then
  NEW_CODE=$((CUR_CODE + 1))
  sed -i '' "s|versionName = \"$CUR_VER\"|versionName = \"$VER\"|" probe/build.gradle.kts
  sed -i '' "s|versionCode = $CUR_CODE|versionCode = $NEW_CODE|" probe/build.gradle.kts
  echo "版本号: $CUR_VER($CUR_CODE) -> $VER($NEW_CODE)"
  NEW_CODE_FINAL=$NEW_CODE
else
  echo "版本号已是 $VER (code=$CUR_CODE)"
  NEW_CODE_FINAL=$CUR_CODE
fi

# ---------------------------------------------------------------- 1.5) 静态检查
# 先跑静态检查再构建：网页面板的 JS 语法错 Python 检查发现不了，
# 只有把 JS 抠出来真跑一遍才知道（曾因此导致面板永远停在「加载中」）。
echo
echo "==== 静态检查（旧 Python 面板）===="
if ! bash tools/check-assets.sh; then
  echo "!! 静态检查未通过，已中止发布"
  exit 1
fi

# ---------------------------------------------------------------- 1.6) 集控侧验收
# APK 从 0.24 起用集控协议 v1（合并往返/心跳/截图/服务端下发周期），
# 旧 Python 服务端不认识 —— 所以发 APK 之前必须确认新的 altaird 是好的。
# 这段在切换完成后可以删掉 1.5 那一节（旧面板届时已删除）。
echo
echo "==== 集控侧验收（control/）===="
if [ -d "$ROOT/control" ]; then
  if [ -f "$ROOT/.toolchain/env.sh" ]; then
    # shellcheck disable=SC1091
    source "$ROOT/.toolchain/env.sh"
  fi
  if ! command -v go >/dev/null; then
    echo "!! 找不到 go，无法验证集控侧；请先 bash tools/setup-toolchain.sh" >&2
    exit 1
  fi
  ( cd "$ROOT/control" && go build ./... && go vet ./... ) || { echo "!! 集控侧编译失败"; exit 1; }
  ( cd "$ROOT/control" && go test ./... -count=1 ) || { echo "!! 集控侧单测失败"; exit 1; }
  bash "$ROOT/control/scripts/smoke.sh" || { echo "!! 集控端到端验收失败"; exit 1; }
  echo "  ✅ 集控侧验收通过"
  echo
  echo "  ⚠ 注意：本次发布的 APK 使用集控协议 v1，**必须配合 altaird 服务端**。"
  echo "     若线上还在跑旧的 tools/control-server.py，升级 APK 会让设备静默失联。"
  echo "     上线步骤见 control/deploy/RUNBOOK.md。"
else
  echo "  （没有 control/ 目录，跳过）"
fi

# ---------------------------------------------------------------- 2) 构建
echo
echo "==== 构建 ===="
bash tools/build.sh 2>&1 | tail -8 || exit 1
APK="$ROOT/probe/build/outputs/apk/release/probe-release.apk"
[ -f "$APK" ] || { echo "!! 找不到 APK"; exit 1; }
echo "APK: $(du -h "$APK" | cut -f1)"

# ---- 构建后校验关键 manifest 属性 ----
# 有些问题只在**打包后**才暴露（例如 networkSecurityConfig 没被打进去），
# 看源码是看不出来的。这里对成品 APK 做一次硬校验。
AAPT="${ANDROID_SDK_ROOT:-/nonexistent}/build-tools/33.0.1/aapt2"
if [ -x "$AAPT" ]; then
  MT="$("$AAPT" dump xmltree "$APK" --file AndroidManifest.xml 2>/dev/null)"
  chk_attr() {
    if echo "$MT" | grep -q "$1"; then echo "  ✅ $2"; else echo "  ❌ $2 缺失"; MFBAD=1; fi
  }
  echo "  ---- 成品 APK manifest 校验 ----"
  chk_attr "usesCleartextTraffic.*=true" "允许明文 HTTP（集控服务器是 http://）"
  chk_attr "networkSecurityConfig"       "网络安全配置已引用"
  chk_attr "SYSTEM_ALERT_WINDOW"         "悬浮窗权限已声明"
  [ "${MFBAD:-0}" = "1" ] && { echo "!! manifest 校验未通过，已中止发布"; exit 1; }
fi

# ---------------------------------------------------------------- 3) 仓库
if [ -z "$REPO" ]; then
  if git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
    REPO="$(git -C "$ROOT" remote get-url origin 2>/dev/null | sed -E 's#.*github.com[:/]([^/]+/[^/.]+)(\.git)?#\1#')"
  fi
fi
if [ -z "$REPO" ]; then
  REPO="$OWNER/altair-buff"
  echo
  echo "未指定仓库，使用默认: $REPO"
fi

if ! gh repo view "$REPO" >/dev/null 2>&1; then
  echo "仓库 $REPO 不存在，创建中（public）..."
  gh repo create "$REPO" --public --description "冒险岛世界·阿尔泰 自动补BUFF挂机工具" >/dev/null || exit 1
fi
echo "仓库: https://github.com/$REPO"

# ---------------------------------------------------------------- 4) 发布
TAG="v$VER"
echo
echo "==== 发布 $TAG ===="
NOTES="版本 $VER

- 构建时间: $(date '+%Y-%m-%d %H:%M:%S')
- 体积: $(du -h "$APK" | cut -f1)
- 自更新固定地址: https://github.com/$REPO/releases/latest/download/probe-release.apk
"
if gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then
  echo "tag $TAG 已存在，改为上传/覆盖资产"
  gh release upload "$TAG" "$APK#probe-release.apk" -R "$REPO" --clobber
else
  gh release create "$TAG" "$APK#probe-release.apk" -R "$REPO" -t "$TAG" -n "$NOTES"
fi

# ---------------------------------------------------------------- 5) 同步 dist（jsDelivr 源）
#
# 教训：dist/ 曾长期停留在 v0.6.0 —— 因为发布新版时忘了同步，
# 导致 jsDelivr 那条备用源一直提供旧包。现在并入发布流程，不再依赖人工记得。
echo
echo "==== 同步 dist/ 供 jsDelivr 使用 ===="
mkdir -p "$ROOT/dist"
cp "$APK" "$ROOT/dist/probe-release.apk"
# 只提交 dist/ —— 用 git add -A 会把工作区里未提交的源码改动一起吞进
# 一个叫「dist: 同步到 vX」的提交里，提交信息与内容不符，以后翻历史看不懂。
git add dist/
if git diff --cached --quiet; then
  echo "  dist 无变化"
  PUSHED=1
else
  git commit -q -m "dist: 同步到 $TAG"
  PUSHED=0
  for i in $(seq 1 12); do
    if git push origin HEAD 2>/dev/null; then PUSHED=1; break; fi
    echo "  推送重试 $i / 12 ..."
    sleep 15
  done
  [ "$PUSHED" = "1" ] && echo "  ✅ dist 已推送" \
    || echo "  ⚠ dist 推送失败（GitHub 不通），jsDelivr 源会停留在旧版"
fi

# ---------------------------------------------------------------- 6) 清 jsDelivr 缓存
if [ "$PUSHED" = "1" ]; then
  echo -n "  清除 jsDelivr 缓存: "
  if curl -sS --max-time 30 "https://purge.jsdelivr.net/gh/$REPO@main/dist/probe-release.apk" >/dev/null 2>&1; then
    echo "已请求 ✅"
  else
    echo "请求失败（不影响，jsDelivr 会自行过期）"
  fi
fi

# ---------------------------------------------------------------- 7) 多源验证
LATEST="https://github.com/$REPO/releases/latest/download/probe-release.apk"
PINNED="https://github.com/$REPO/releases/download/$TAG/probe-release.apk"
JSD="https://cdn.jsdelivr.net/gh/$REPO@main/dist/probe-release.apk"
AAPT="${ANDROID_SDK_ROOT:-/nonexistent}/build-tools/33.0.1/aapt2"

ver_of() {
  local u="$1"
  rm -f /tmp/_relchk.apk
  curl -sSL --max-time 90 -o /tmp/_relchk.apk "$u" 2>/dev/null
  if [ -s /tmp/_relchk.apk ] && [ -x "$AAPT" ]; then
    "$AAPT" dump badging /tmp/_relchk.apk 2>/dev/null | grep -oE "versionCode='[0-9]+'" | head -1 | tr -dc 0-9
  fi
}

echo
echo "============================================================"
echo " 发布完成   $TAG"
echo "============================================================"
echo " App 内置 4 个更新源（按顺序自动切换）："
echo "   1. gh-proxy  https://gh-proxy.com/$LATEST"
echo "   2. ghfast    https://ghfast.top/$LATEST"
echo "   3. GitHub    $LATEST"
echo "   4. jsDelivr  $JSD"
echo
echo " 回滚用版本地址:"
echo "   $PINNED"
echo
echo " 各源当前提供的 versionCode（期望 = ${NEW_CODE_FINAL}）:"
printf "   %-12s %s\n" "gh-proxy" "$(ver_of "https://gh-proxy.com/$LATEST")"
printf "   %-12s %s\n" "ghfast"   "$(ver_of "https://ghfast.top/$LATEST")"
printf "   %-12s %s\n" "GitHub"  "$(ver_of "$LATEST")"
printf "   %-12s %s\n" "jsDelivr" "$(ver_of "$JSD")"
echo "============================================================"

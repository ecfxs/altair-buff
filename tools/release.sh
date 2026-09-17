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
else
  echo "版本号已是 $VER (code=$CUR_CODE)"
fi

# ---------------------------------------------------------------- 2) 构建
echo
echo "==== 构建 ===="
bash tools/build.sh 2>&1 | tail -8 || exit 1
APK="$ROOT/probe/build/outputs/apk/release/probe-release.apk"
[ -f "$APK" ] || { echo "!! 找不到 APK"; exit 1; }
echo "APK: $(du -h "$APK" | cut -f1)"

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

# ---------------------------------------------------------------- 5) 输出地址
LATEST="https://github.com/$REPO/releases/latest/download/probe-release.apk"
PINNED="https://github.com/$REPO/releases/download/$TAG/probe-release.apk"
echo
echo "============================================================"
echo " 发布完成"
echo "============================================================"
echo " 【固定地址】配进 App 的更新源，一次配好永久有效："
echo "   $LATEST"
echo
echo " 【版本地址】用于回滚到指定版本："
echo "   $PINNED"
echo
echo -n " 可用性检查: "
curl -sSL -o /dev/null -w "HTTP %{http_code}  %{size_download} bytes\n" -r 0-2000 "$LATEST" 2>&1 | tail -1
echo "============================================================"

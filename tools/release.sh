#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 构建 + 发布到 GitHub Release
#
# 用法:
#   bash tools/release.sh 0.29.0             # 从当前 origin 发布已提交版本
#   bash tools/release.sh 0.29.0 owner/repo  # 显式指定已存在的仓库
#   先更新并提交 version.properties，再从干净工作区运行发布。
#   需提供签名属性：ALTAIR_STORE_FILE / ALTAIR_STORE_PASSWORD / ALTAIR_KEY_ALIAS / ALTAIR_KEY_PASSWORD。
#
# 发布后得到两个 URL：
#   固定 URL（推荐，永远指向最新）:
#     https://github.com/<owner>/<repo>/releases/latest/download/probe-release.apk
#   版本 URL:
#     https://github.com/<owner>/<repo>/releases/download/v<ver>/probe-release.apk
#
# 已有安装用户依赖 release signer 更新；切勿改用新 keystore。
# ---------------------------------------------------------------------------
set -euo pipefail

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
if ! [[ "$VER" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]]; then
  echo "版本号必须符合 semver（例如 0.29.0）"
  exit 1
fi
if ! git diff --quiet || ! git diff --cached --quiet || [ -n "$(git ls-files --others --exclude-standard)" ]; then
  echo "!! 发布要求工作区、暂存区及未跟踪源码都已提交"
  exit 1
fi

# ---------------------------------------------------------------- 前置检查
command -v gh >/dev/null || { echo "!! 未安装 gh CLI"; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "!! gh 未登录，先执行 gh auth login"; exit 1; }
OWNER="$(gh api user -q .login 2>/dev/null)"
echo "GitHub 账号: $OWNER"

# ---------------------------------------------------------------- 1) 校验已提交版本清单
CUR_VER=$(sed -n 's/^versionName=//p' version.properties)
CUR_CODE=$(sed -n 's/^versionCode=//p' version.properties)
[ -n "$CUR_VER" ] && [ -n "$CUR_CODE" ] || { echo "!! version.properties 不完整"; exit 1; }
if [ "$CUR_VER" != "$VER" ]; then
  echo "!! version.properties 仍为 $CUR_VER($CUR_CODE)，请求发布 $VER。"
  echo "请先更新 version.properties、递增 versionCode、提交并推送，然后重跑发布。"
  exit 1
fi
NEW_CODE_FINAL="$CUR_CODE"
echo "使用已提交版本: $CUR_VER($CUR_CODE)"

# ---------------------------------------------------------------- 1.5) 自动化质量门禁
EXPECTED_SIGNER="$(sed -n 's/^releaseSignerSha256=//p' version.properties)"
[ -n "$EXPECTED_SIGNER" ] || { echo "!! version.properties 缺少 releaseSignerSha256"; exit 1; }
./gradlew :probe:automationRegression :probe:lintRelease --no-daemon --stacktrace
python3 tools/verify.py

# ---------------------------------------------------------------- 2) 构建
echo
echo "==== 构建 ===="
ALTAIR_BUILD_TASKS=assembleRelease ALTAIR_VERSION_NAME="$VER" ALTAIR_VERSION_CODE="$NEW_CODE_FINAL" bash tools/build.sh 2>&1 | tail -8 || exit 1
APK="$ROOT/probe/build/outputs/apk/release/probe-release.apk"
[ -f "$APK" ] || { echo "!! 找不到 APK"; exit 1; }
python3 tools/verify-artifact.py "$APK"
echo "APK: $(du -h "$APK" | cut -f1)"

# ---- 构建后校验关键 manifest 属性 ----
# 有些问题只在**打包后**才暴露（例如 networkSecurityConfig 没被打进去），
# 看源码是看不出来的。这里对成品 APK 做一次硬校验。
AAPT="${ANDROID_SDK_ROOT:-/nonexistent}/build-tools/33.0.1/aapt2"
if [ ! -x "$AAPT" ]; then
  echo "!! 缺少 aapt2，无法验证 release APK manifest"
  exit 1
fi
MT="$("$AAPT" dump xmltree "$APK" --file AndroidManifest.xml 2>/dev/null)"
if [ -z "$MT" ]; then
  echo "!! 无法读取 release APK manifest"
  exit 1
fi
chk_attr() {
  if echo "$MT" | grep -q "$1"; then
    echo "  ✅ $2"
  else
    echo "  ❌ $2 缺失"
    MFBAD=1
  fi
}
echo "  ---- 成品 APK manifest 校验 ----"
chk_attr "usesCleartextTraffic.*=false" "禁止明文 HTTP"
chk_attr "networkSecurityConfig"       "HTTPS-only 网络安全配置已引用"
chk_attr "SYSTEM_ALERT_WINDOW"          "悬浮窗权限已声明"
APKSIGNER="$ANDROID_SDK_ROOT/build-tools/33.0.1/apksigner"
if [ ! -x "$APKSIGNER" ]; then
  echo "!! 缺少 apksigner，无法验证正式签名"
  exit 1
fi
CERTS="$(JAVA_HOME="${JAVA_HOME:?JAVA_HOME 未设置}" "$APKSIGNER" verify --print-certs "$APK")"
ACTUAL_SIGNER="$(printf '%s\n' "$CERTS" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1 | tr '[:upper:]' '[:lower:]')"
EXPECTED_SIGNER="$(printf '%s' "$EXPECTED_SIGNER" | tr '[:upper:]' '[:lower:]')"
if [ -z "$ACTUAL_SIGNER" ] || [ "$ACTUAL_SIGNER" != "$EXPECTED_SIGNER" ]; then
  echo "!! release APK signer 与 version.properties 中的既有 trust anchor 不匹配"
  exit 1
fi
echo "  ✅ release APK 使用已配置的现有 signer"
if [ "${MFBAD:-0}" = "1" ]; then
  echo "!! manifest 校验未通过，已中止发布"
  exit 1
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
if ! [[ "$REPO" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
  echo "!! 仓库必须使用 owner/repo 格式"
  exit 1
fi

if ! gh repo view "$REPO" >/dev/null 2>&1; then
  echo "!! 仓库 $REPO 不存在或当前账号不可访问；请先确认发布目标"
  exit 1
fi
echo "仓库: https://github.com/$REPO"

# ---------------------------------------------------------------- 3.5) 先推 HEAD
#
# 教训：v0.24.13 / v0.24.14 的 tag 指的是**上一个**版本的提交 —— 建 release 时
# 本地新提交还没推上去，GitHub 只能拿远端 main 当时的位置打 tag。源码提交必须先
# 到远端，tag 才会落在本次发版的那个提交上（v0.26.0 也踩过一次，事后手动修的）。
if git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1 \
   && git -C "$ROOT" remote get-url origin >/dev/null 2>&1; then
  BRANCH="$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)"
  HEAD_SHA="$(git -C "$ROOT" rev-parse HEAD)"
  echo
  # 注意：本机 bash 3.2 + UTF-8 下，`$BRANCH（` 这种「变量名紧贴全角字符」会把
  # 全角字符的首字节吞进变量名，set -u 直接报 unbound 并中止发版。变量名一律
  # 用 ${} 包住（`${BRANCH}（`）才安全。
  echo "==== 推送 ${BRANCH}（$(git -C "$ROOT" rev-parse --short HEAD)）===="
  HEAD_PUSHED=0
  for i in $(seq 1 12); do
    if git -C "$ROOT" push origin "$BRANCH" 2>&1 | tail -2; then HEAD_PUSHED=1; break; fi
    echo "  推送重试 $i / 12 ..."
    sleep 15
  done
  if [ "$HEAD_PUSHED" != "1" ]; then
    echo "!! 源码推送失败，拒绝创建可能错位的 tag"
    exit 1
  fi
  REMOTE_SHA="$(git -C "$ROOT" ls-remote origin "refs/heads/$BRANCH" | awk 'NR==1 {print $1}')"
  if [ "$REMOTE_SHA" != "$HEAD_SHA" ]; then
    echo "!! origin/$BRANCH 并未指向已验证提交 $HEAD_SHA"
    exit 1
  fi
else
  echo "!! 必须配置 origin 远端才能发布"
  exit 1
fi

# ---------------------------------------------------------------- 4) 发布
TAG="v$VER"
if gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then
  echo "!! Release $TAG 已存在；不会覆盖 tag/资产，请递增 versionName/versionCode"
  exit 1
fi
echo
echo "==== 发布 $TAG ===="
NOTES="版本 $VER

- 构建时间: $(date '+%Y-%m-%d %H:%M:%S')
- 体积: $(du -h "$APK" | cut -f1)
- 自更新固定地址: https://github.com/$REPO/releases/latest/download/probe-release.apk
"
gh release create "$TAG" "$APK#probe-release.apk" -R "$REPO" --target "$HEAD_SHA" -t "$TAG" -n "$NOTES"
TAG_SHA="$(gh api "repos/$REPO/git/ref/tags/$TAG" -q '.object.sha' 2>/dev/null)"
TAG_TYPE="$(gh api "repos/$REPO/git/ref/tags/$TAG" -q '.object.type' 2>/dev/null)"
if [ "$TAG_TYPE" = "tag" ]; then
  TAG_SHA="$(gh api "repos/$REPO/git/tags/$TAG_SHA" -q '.object.sha' 2>/dev/null)"
fi
if [ -z "$TAG_SHA" ] || [ "$TAG_SHA" != "$HEAD_SHA" ]; then
  echo "!! tag $TAG 落点与已验证源码不一致，拒绝继续同步和分发"
  exit 1
fi
echo "  ✅ tag $TAG -> $(git -C "$ROOT" rev-parse --short HEAD)"

# ---------------------------------------------------------------- 5) 同步 dist（jsDelivr 源）
# 与正式 release 同步，但不自动提交：工作区保持审阅状态，由操作者确认后提交镜像产物。
echo
echo "==== 同步 dist/ 供 jsDelivr 使用 ===="
mkdir -p "$ROOT/dist"
# dist/ 是备用更新源。只更新工作区，不自动提交/推送任何文件。
cp "$APK" "$ROOT/dist/probe-release.apk"
echo "dist 已更新：请确认并提交镜像 APK 后，jsDelivr 备用源才会同步。"

# ---------------------------------------------------------------- 6) 校验 Release 更新源
LATEST="https://github.com/$REPO/releases/latest/download/probe-release.apk"
PINNED="https://github.com/$REPO/releases/download/$TAG/probe-release.apk"
JSD="https://cdn.jsdelivr.net/gh/$REPO@main/dist/probe-release.apk"
AAPT="${ANDROID_SDK_ROOT:-/nonexistent}/build-tools/33.0.1/aapt2"

ver_of() {
  local label="$1" u="$2" tmp code certs signer
  tmp="$(mktemp "${TMPDIR:-/tmp}/altair-release-check.XXXXXX.apk")"
  if ! curl -fsSL --max-time 90 -o "$tmp" "$u" 2>/dev/null; then
    rm -f "$tmp"
    echo "!! $label 下载失败" >&2
    return 1
  fi
  if ! cmp -s "$tmp" "$APK"; then
    rm -f "$tmp"
    echo "!! $label 与本次验证产物的内容不一致" >&2
    return 1
  fi
  if [ ! -x "$AAPT" ] || [ ! -x "$APKSIGNER" ]; then
    rm -f "$tmp"
    echo "!! 缺少 aapt2/apksigner，无法验证来源 $label" >&2
    return 1
  fi
  code="$("$AAPT" dump badging "$tmp" 2>/dev/null | sed -n "s/.*versionCode='\\([0-9][0-9]*\\)'.*/\\1/p" | head -1)"
  certs="$(JAVA_HOME="${JAVA_HOME:?JAVA_HOME 未设置}" "$APKSIGNER" verify --print-certs "$tmp" 2>/dev/null)"
  signer="$(printf '%s\n' "$certs" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1 | tr '[:upper:]' '[:lower:]')"
  rm -f "$tmp"
  if [ "$code" != "$NEW_CODE_FINAL" ]; then
    echo "!! $label versionCode=${code:-未知}，期望 $NEW_CODE_FINAL" >&2
    return 1
  fi
  if [ "$signer" != "$EXPECTED_SIGNER" ]; then
    echo "!! $label signer 不匹配已信任发布密钥" >&2
    return 1
  fi
  printf '%s' "$code"
}

echo
echo "============================================================"
echo " Release 已创建   $TAG"
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
for source in gh-proxy ghfast GitHub; do
  case "$source" in
    gh-proxy) source_url="https://gh-proxy.com/$LATEST" ;;
    ghfast) source_url="https://ghfast.top/$LATEST" ;;
    GitHub) source_url="$LATEST" ;;
  esac
  source_code="$(ver_of "$source" "$source_url")" || exit 1
  printf "   %-12s %s\n" "$source" "$source_code"
done
echo "============================================================"
echo "注意：jsDelivr 基于已提交的 dist/ 文件；提交 dist 后再验证备用源：$JSD"
echo "GitHub 更新资产 versionCode 验证通过。"

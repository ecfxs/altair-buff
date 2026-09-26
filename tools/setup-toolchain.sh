#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 工具链安装脚本（幂等，可重复运行）
#
# 全部安装到项目工作区内的 .toolchain/，不污染系统目录：
#   .toolchain/jdk-17           Temurin JDK 17 (macOS aarch64)
#   .toolchain/gradle-8.2       Gradle 8.2
#   .toolchain/android-sdk      Android SDK (cmdline-tools + platform-tools + platforms/build-tools)
#   .toolchain/gradle-home      GRADLE_USER_HOME（依赖缓存也留在工作区内）
#   .toolchain/go               Go 工具链（历史维护用；当前 Android 构建不需要）
#   .toolchain/go-path          GOPATH / GOMODCACHE（历史维护用）
#   .toolchain/go-cache         GOCACHE（历史维护用）
#
# 用法: bash tools/setup-toolchain.sh
# ---------------------------------------------------------------------------
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TC="$ROOT/.toolchain"
mkdir -p "$TC"
cd "$TC"

JDK_DIR="$TC/jdk-17"
GRADLE_DIR="$TC/gradle-8.2"
SDK="$TC/android-sdk"
export GRADLE_USER_HOME="$TC/gradle-home"
mkdir -p "$GRADLE_USER_HOME"

log() { echo "[$(date +%H:%M:%S)] $*"; }

# ---------------------------------------------------------------- 1) JDK 17
if [ -x "$JDK_DIR/Contents/Home/bin/java" ]; then
  log "JDK 17 已存在，跳过"
else
  rm -f jdk17.tar.gz
  # 注意：api.adoptium.net 会重定向到很慢的 CDN（实测 52KB/s）。
  # 清华 TUNA 镜像是同一份产物，实测 ~11MB/s。
  JDK_URL="https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/aarch64/mac/OpenJDK17U-jdk_aarch64_mac_hotspot_17.0.20.1_1.tar.gz"
  JDK_URL_FALLBACK="https://api.adoptium.net/v3/binary/latest/17/ga/mac/aarch64/jdk/hotspot/normal/eclipse"
  log "下载 Temurin JDK 17 (macOS aarch64) <- 清华 TUNA ..."
  curl -L --fail --retry 2 -o jdk17.tar.gz "$JDK_URL" \
    || { log "TUNA 失败，回退官方 CDN ..."; curl -L --fail -o jdk17.tar.gz "$JDK_URL_FALLBACK"; } \
    || { log "JDK 下载失败"; exit 1; }
  log "解压 JDK ..."
  mkdir -p "$JDK_DIR"
  tar xzf jdk17.tar.gz -C "$JDK_DIR" --strip-components=1 || { log "JDK 解压失败"; exit 1; }
  rm -f jdk17.tar.gz
fi
export JAVA_HOME="$JDK_DIR/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
log "JDK: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"

# ---------------------------------------------------------------- 2) Gradle
if [ -x "$GRADLE_DIR/bin/gradle" ]; then
  log "Gradle 已存在，跳过"
else
  rm -f gradle.zip
  # services.gradle.org 实测仅 ~107KB/s；腾讯云镜像是同一份产物，实测 ~15MB/s
  GRADLE_URL="https://mirrors.cloud.tencent.com/gradle/gradle-8.2-bin.zip"
  GRADLE_URL_FALLBACK="https://services.gradle.org/distributions/gradle-8.2-bin.zip"
  log "下载 Gradle 8.2 <- 腾讯云 ..."
  curl -L --fail --retry 2 -o gradle.zip "$GRADLE_URL" \
    || { log "腾讯云失败，回退官方 ..."; curl -L --fail -o gradle.zip "$GRADLE_URL_FALLBACK"; } \
    || { log "Gradle 下载失败"; exit 1; }
  log "解压 Gradle ..."
  unzip -q gradle.zip -d "$TC" || { log "Gradle 解压失败"; exit 1; }
  rm -f gradle.zip
fi
log "Gradle: $("$GRADLE_DIR/bin/gradle" --version 2>&1 | grep -i '^Gradle' || echo '?')"

# ---------------------------------------------------------------- 3) Android cmdline-tools
if [ -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  log "cmdline-tools 已存在，跳过"
else
  log "下载 Android cmdline-tools ..."
  curl -L --fail --retry 3 -o cmdtools.zip \
    "https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip" \
    || { log "cmdline-tools 下载失败"; exit 1; }
  mkdir -p "$SDK/cmdline-tools"
  unzip -q cmdtools.zip -d "$SDK/cmdline-tools" || { log "解压失败"; exit 1; }
  rm -f cmdtools.zip
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_HOME="$SDK"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"

# ---------------------------------------------------------------- 4) SDK 组件
#
# 不用 sdkmanager：在本机它连不上仓库清单（"IO exception while downloading manifest"，
# 疑似 Java 走 IPv6 无路由）。而仓库 XML 与各 zip 直链用 curl 都完全可达，
# 所以直接按 AGP 要求的目录布局下载并解压，结果等价且确定。
install_pkg() {
  local name="$1" url="$2" dest="$3"
  if [ -d "$dest" ] && [ -n "$(ls -A "$dest" 2>/dev/null)" ]; then
    log "$name 已就绪，跳过"
    return 0
  fi
  log "下载 $name ..."
  rm -f "$TC/_pkg.zip"
  if ! curl -L --fail --retry 3 -o "$TC/_pkg.zip" "$url"; then
    log "!! $name 下载失败: $url"
    return 1
  fi
  local tmp="$TC/_x"
  rm -rf "$tmp"; mkdir -p "$tmp"
  if ! unzip -q "$TC/_pkg.zip" -d "$tmp"; then
    log "!! $name 解压失败"
    rm -rf "$tmp" "$TC/_pkg.zip"; return 1
  fi
  rm -f "$TC/_pkg.zip"
  local top
  top="$(ls -1 "$tmp" | head -1)"
  if [ -z "$top" ]; then log "!! $name 解压后为空"; rm -rf "$tmp"; return 1; fi
  mkdir -p "$(dirname "$dest")"
  rm -rf "$dest"
  mv "$tmp/$top" "$dest"
  rm -rf "$tmp"
  log "$name -> $dest  ($(du -sh "$dest" 2>/dev/null | cut -f1))"
  return 0
}

SDK_OK=1
install_pkg "platform-tools" \
  "https://dl.google.com/android/repository/platform-tools_r37.0.1-darwin.zip" \
  "$SDK/platform-tools" || SDK_OK=0
install_pkg "build-tools;33.0.1" \
  "https://dl.google.com/android/repository/build-tools_r33.0.1-macosx.zip" \
  "$SDK/build-tools/33.0.1" || SDK_OK=0
install_pkg "platforms;android-33" \
  "https://dl.google.com/android/repository/platform-33-ext3_r03.zip" \
  "$SDK/platforms/android-33" || SDK_OK=0

# AGP 会校验许可文件是否存在
mkdir -p "$SDK/licenses"
cat > "$SDK/licenses/android-sdk-license" <<'EOF'
8933bad161af4178b1185d1a37fbf41ea5269c55
d56f5187479451eabf01fb78af6dfcb131a6481e
24333f8a63b6825ea9c5514f83c2829b004d1fee
EOF
cat > "$SDK/licenses/android-sdk-preview-license" <<'EOF'
84831b9409646a918e30573bab4c9c91346d8abd
EOF

# 语法检查：确认关键可执行文件真的存在且能跑
log "校验 SDK 关键组件 ..."
for bin in "platform-tools/adb" "build-tools/33.0.1/aapt2" "build-tools/33.0.1/d8" \
           "build-tools/33.0.1/zipalign" "build-tools/33.0.1/apksigner"; do
  if [ -x "$SDK/$bin" ]; then
    log "  ✅ $bin"
  else
    log "  ❌ 缺失 $bin"
    SDK_OK=0
  fi
done
if [ -f "$SDK/platforms/android-33/android.jar" ]; then
  log "  ✅ platforms/android-33/android.jar ($(du -h "$SDK/platforms/android-33/android.jar" | cut -f1))"
else
  log "  ❌ 缺失 platforms/android-33/android.jar"
  SDK_OK=0
fi

# ---------------------------------------------------------------- 4.5) 可选 Go 工具链
# 仅用于历史/本地工具维护，不是当前 :probe Android 构建或发布的必需依赖。
GO_DIR="$TC/go"
if [ -x "$GO_DIR/bin/go" ]; then
  log "Go 已存在，跳过"
else
  # 版本动态取官方 VERSION 端点，失败则用下面的兜底版本，避免脚本随版本过期。
  GO_VER_FALLBACK="1.27.1"
  GO_VER="$(curl -fsS --max-time 20 'https://go.dev/VERSION?m=text' 2>/dev/null | head -1)"
  [ -n "$GO_VER" ] || GO_VER="go$GO_VER_FALLBACK"
  case "$(uname -m)" in
    arm64|aarch64) GO_ARCH="arm64" ;;
    x86_64|amd64)  GO_ARCH="amd64" ;;
    *)             log "!! 不认识的架构: $(uname -m)"; GO_ARCH="arm64" ;;
  esac
  GO_URL="https://go.dev/dl/${GO_VER}.darwin-${GO_ARCH}.tar.gz"
  GO_URL_FALLBACK="https://mirrors.aliyun.com/golang/${GO_VER}.darwin-${GO_ARCH}.tar.gz"
  log "下载 Go ${GO_VER} (darwin-${GO_ARCH}) ..."
  rm -f gotool.tar.gz
  curl -L --fail --retry 2 -o gotool.tar.gz "$GO_URL" \
    || { log "官方源失败，回退阿里云镜像 ..."; curl -L --fail -o gotool.tar.gz "$GO_URL_FALLBACK"; } \
    || { log "Go 下载失败"; exit 1; }
  log "解压 Go ..."
  rm -rf "$GO_DIR"
  tar xzf gotool.tar.gz -C "$TC" || { log "Go 解压失败"; exit 1; }
  rm -f gotool.tar.gz
fi
export GOROOT="$GO_DIR"
# 关键：GOPATH/GOMODCACHE/GOCACHE 必须留在工作区内。
# 默认值落在 ~/go 与 ~/Library/Caches/go-build，受限环境下不可写，会让 go build 直接失败。
export GOPATH="$TC/go-path"
export GOMODCACHE="$GOPATH/pkg/mod"
export GOCACHE="$TC/go-cache"
export PATH="$GOROOT/bin:$PATH"
mkdir -p "$GOPATH" "$GOCACHE"
log "Go: $("$GO_DIR/bin/go" version)"

# ---------------------------------------------------------------- 5) 汇总
echo
log "================ 工具链就绪 ================"
echo "JAVA_HOME        = $JAVA_HOME"
echo "ANDROID_SDK_ROOT = $SDK"
echo "GRADLE_USER_HOME = $GRADLE_USER_HOME"
echo "gradle           = $GRADLE_DIR/bin/gradle"
echo "GOROOT           = $GO_DIR"
echo "GOMODCACHE       = $GOPATH/pkg/mod"
echo
echo "已安装 SDK 组件:"
ls "$SDK" 2>/dev/null | sed 's/^/  /'
echo
echo "环境变量（在构建前 source 这一份）:"
mkdir -p "$TC/android-home"
cat > "$TC/env.sh" <<EOF
export JAVA_HOME="$JAVA_HOME"
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_HOME="$SDK"
export GRADLE_USER_HOME="$GRADLE_USER_HOME"
export PATH="\$JAVA_HOME/bin:$SDK/platform-tools:$SDK/build-tools/33.0.1:\$PATH"
# AGP 需要可写的 Android 首选项目录（默认在 ~/.android，受限环境不可写）。
# 只能设 ANDROID_USER_HOME 一个 —— 同时设 ANDROID_SDK_HOME 会让 AGP 报路径冲突。
export ANDROID_USER_HOME="$TC/android-home"
# ---- Go（集控服务端 control/ 用）----
export GOROOT="$GO_DIR"
export GOPATH="$TC/go-path"
export GOMODCACHE="\$GOPATH/pkg/mod"
export GOCACHE="$TC/go-cache"
export PATH="\$GOROOT/bin:\$PATH"
EOF
echo "  已写入 $TC/env.sh"
du -sh "$TC" 2>/dev/null | sed 's/^/总占用: /'

echo
if [ "${SDK_OK:-0}" = "1" ]; then
  log "================ 工具链就绪 ✅ ================"
  exit 0
else
  log "================ 工具链不完整 ❌（见上方 ❌ 项）================"
  exit 1
fi

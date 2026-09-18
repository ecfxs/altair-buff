#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# E2E 用的集控服务：起 altaird + 假设备，供 Playwright 做真实浏览器验收。
#
# 由 playwright.config.ts 的 webServer 拉起；进程退出时连带清掉子进程。
#
# 与 smoke.sh 的分工：
#   smoke.sh  —— 协议与接口层（curl，快，无需浏览器）
#   本脚本 + e2e/panel.spec.ts —— 界面层（真浏览器渲染、点击、截图）
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${E2E_PORT:-8891}"
DIR="$ROOT/data/e2e"
BASE="http://127.0.0.1:$PORT"

# Playwright 拉起的进程不继承我们 shell 里 source 过的工具链环境，这里自己补。
# （本仓库把 Go 装在工作区内，见 tools/setup-toolchain.sh）
if [[ -f "$ROOT/../.toolchain/env.sh" ]]; then
  # shellcheck disable=SC1091
  source "$ROOT/../.toolchain/env.sh"
fi
if ! command -v go >/dev/null; then
  echo "!! 找不到 go：请先 bash tools/setup-toolchain.sh，或把 go 放进 PATH" >&2
  exit 1
fi

say() { printf "\033[36m[e2e]\033[0m %s\n" "$*"; }

# 每次从干净的库与干净的截图目录开始：
# 库不干净会污染断言；截图不干净会让人分不清哪张是本次跑的（跨轮累积过）。
rm -rf "$DIR" "$ROOT/data/e2e-shots"
mkdir -p "$DIR"

say "编译 altaird"
(cd "$ROOT" && go build -o "$DIR/altaird" ./cmd/altaird)

# 用磁盘版前端产物做 E2E：改前端不必重编二进制。
# 但必须**先构建**，否则测的是上一次的产物 —— 这个坑真踩到过
# （给卡片加了 data-testid，测试却找不到，因为 dist 还是旧的）。
say "构建前端（保证 E2E 测的是当前源码）"
if ! (cd "$ROOT/web" && pnpm build >"$DIR/web-build.log" 2>&1); then
  say "!! 前端构建失败"; tail -20 "$DIR/web-build.log"; exit 1
fi
if [[ ! -f "$ROOT/web/dist/index.html" ]]; then
  say "!! 缺少 web/dist/index.html"
  exit 1
fi

say "启动服务 :$PORT"
"$DIR/altaird" --addr "127.0.0.1:$PORT" --db "$DIR/altair.db" --data "$DIR" \
  --static-dir "$ROOT/web/dist" >"$DIR/server.log" 2>&1 &
SRV=$!

cleanup() {
  say "清理"
  kill "${FAKE:-0}" "$SRV" 2>/dev/null || true
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

for _ in $(seq 1 80); do
  curl -fsS -m 1 "$BASE/healthz" >/dev/null 2>&1 && break
  sleep 0.25
done
if ! curl -fsS -m 2 "$BASE/healthz" >/dev/null 2>&1; then
  say "!! 服务没起来"; tail -20 "$DIR/server.log"; exit 1
fi

PW="$(grep -m1 '面板口令' "$DIR/server.log" | awk '{print $2}')"
TOKEN="$(grep -m1 '设备 Token' "$DIR/server.log" | sed 's/.*设备 Token *//' | tr -d ' ')"
if [[ -z "$PW" || -z "$TOKEN" ]]; then
  say "!! 解析凭据失败"; cat "$DIR/server.log"; exit 1
fi
printf '{"password":"%s","token":"%s","port":%s}\n' "$PW" "$TOKEN" "$PORT" >"$DIR/creds.json"
say "凭据已写入 data/e2e/creds.json"

say "启动 3 台假设备"
"$DIR/altaird" fake-device --server "$BASE" --n 3 --interval 5s --token "$TOKEN" \
  >"$DIR/fake.log" 2>&1 &
FAKE=$!

# 等第一批上报落库，避免测试一开始就看到空态。
# 注意：新版面板是**会话 Cookie** 鉴权（旧 Python 版才是 Basic Auth），所以先换一个 Cookie。
JAR="$DIR/jar.txt"
curl -sS -c "$JAR" -X POST "$BASE/api/v1/panel/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$PW\"}" >/dev/null 2>&1 || true

n=0
for _ in $(seq 1 40); do
  n="$(curl -fsS -m 2 -b "$JAR" "$BASE/api/v1/panel/devices" 2>/dev/null | grep -o '"id"' | wc -l | tr -d ' ' || true)"
  if [[ "${n:-0}" -ge 3 ]]; then break; fi
  sleep 0.5
done
say "设备已就绪（${n:-0} 台），E2E 开始"

# 保持存活直到 Playwright 结束（它会杀掉整个进程组）
wait "$SRV"

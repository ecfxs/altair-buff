#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 规模体检：面板接口在「多台设备」下还跟不跟得上。
#
# 起因：handleDevices 早期实现是每台设备补 4~5 次查询（N+1），
# 而 SQLite 连接池是 MaxOpenConns(1) —— 设备一多，面板轮询会串行占住连接，
# 拖慢设备上报路径。台数一直没定，所以先量出来再说，别等线上才发现。
#
# 用法: bash scripts/scale-check.sh [台数] [并发轮数]
# ---------------------------------------------------------------------------
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [[ -f "$ROOT/../.toolchain/env.sh" ]]; then source "$ROOT/../.toolchain/env.sh"; fi

N="${1:-200}"
ROUNDS="${2:-20}"
PORT="${SCALE_PORT:-8892}"
BASE="http://127.0.0.1:$PORT"
DIR="$ROOT/data/scale"

say() { printf "\033[36m[scale]\033[0m %s\n" "$*"; }
cleanup() {
  kill "${FAKE:-0}" "${SRV:-0}" 2>/dev/null || true
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

rm -rf "$DIR"; mkdir -p "$DIR"
say "编译并启动（$N 台假设备，$ROUNDS 轮测量）"
go build -o "$DIR/altaird" ./cmd/altaird || exit 1
"$DIR/altaird" --addr "127.0.0.1:$PORT" --db "$DIR/altair.db" --data "$DIR" >"$DIR/server.log" 2>&1 &
SRV=$!
for _ in $(seq 1 80); do curl -fsS -m 1 "$BASE/healthz" >/dev/null 2>&1 && break; sleep 0.25; done

PW="$(grep -m1 '面板口令' "$DIR/server.log" | awk '{print $2}')"
TOKEN="$(grep -m1 '设备 Token' "$DIR/server.log" | sed 's/.*设备 Token *//' | tr -d ' ')"
JAR="$DIR/jar.txt"
curl -sS -c "$JAR" -X POST "$BASE/api/v1/panel/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$PW\"}" >/dev/null 2>&1 || true

"$DIR/altaird" fake-device --server "$BASE" --n "$N" --interval 60s --token "$TOKEN" >"$DIR/fake.log" 2>&1 &
FAKE=$!

say "等全部设备上报（最多 120s）"
got=0
for _ in $(seq 1 240); do
  got="$(curl -fsS -m 5 -b "$JAR" "$BASE/api/v1/panel/devices" 2>/dev/null | grep -o '"id"' | wc -l | tr -d ' ' || true)"
  [[ "${got:-0}" -ge "$N" ]] && break
  sleep 0.5
done
say "已上报 ${got:-0} 台"

# 测量面板列表接口
say "测量 GET /api/v1/panel/devices ×$ROUNDS"
TIMES=()
for _ in $(seq 1 "$ROUNDS"); do
  t=$(curl -sS -o /dev/null -w '%{time_total}' -b "$JAR" "$BASE/api/v1/panel/devices")
  ms=$(awk -v s="$t" 'BEGIN{printf "%.1f", s*1000}')
  TIMES+=("$ms")
done

python3 - "$N" "${TIMES[@]}" <<'PY'
import statistics, sys
n = sys.argv[1]; xs = sorted(float(x) for x in sys.argv[2:])
print(f"  台数 {n}：p50={statistics.median(xs):.1f}ms  min={xs[0]:.1f}ms  max={xs[-1]:.1f}ms  "
      f"avg={statistics.mean(xs):.1f}ms")
PY

# 测量设备上报路径是否被面板查询拖慢
say "测量 POST /api/v1/device/report ×10（面板重查询下的上报延迟）"
RT=()
for i in $(seq 1 10); do
  t=$(curl -sS -o /dev/null -w '%{time_total}' -X POST "$BASE/api/v1/device/report" \
    -H "X-Altair-Token: $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"deviceId\":\"scale-probe-$i\",\"protocolVersion\":1,\"ts\":$(date +%s000),\"engine\":{\"state\":\"WAITING\",\"buffs\":[]}}")
  RT+=("$(awk -v s="$t" 'BEGIN{printf "%.1f", s*1000}')")
  sleep 3.1   # 躲开同设备上报最小间隔
done
python3 - "${RT[@]}" <<'PY'
import statistics, sys
xs = sorted(float(x) for x in sys.argv[1:])
print(f"  上报：p50={statistics.median(xs):.1f}ms  max={xs[-1]:.1f}ms")
PY

say "库体积：$(du -h "$DIR/altair.db" 2>/dev/null | cut -f1)"
say "完成"

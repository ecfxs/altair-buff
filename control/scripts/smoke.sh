#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 集控端到端冒烟验收
#
# 覆盖：起服务 → 登录鉴权 → 假设备按 v1 协议上报 → 总览聚合 → 启停下发 →
#       配置下发与 revision 语义 → 截图上传与取回 → 审计 → SSE → SPA 静态页
#
# 用法: bash control/scripts/smoke.sh
# ---------------------------------------------------------------------------
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CTRL="$ROOT/control"
cd "$CTRL"

PORT="${PORT:-8899}"
BASE="http://127.0.0.1:$PORT"

# 必须显式建父目录：mktemp -d 不会创建父级，而 data/ 会被清理（这个坑踩过一次）
mkdir -p "$CTRL/data"
WORK="$(mktemp -d "$CTRL/data/smoke.XXXXXX")"
JAR="$WORK/cookies.txt"
SRVLOG="$WORK/server.log"
FAKELOG="$WORK/fake.log"
PASS=0
FAIL=0

ok()   { printf "  ✅ %s\n" "$*"; PASS=$((PASS+1)); }
bad()  { printf "  ❌ %s\n" "$*"; FAIL=$((FAIL+1)); }
say()  { printf "\n\033[36m== %s\033[0m\n" "$*"; }

cleanup() {
  [[ -n "${FAKEPID:-}" ]] && kill "$FAKEPID" 2>/dev/null
  [[ -n "${SRVPID:-}" ]] && kill "$SRVPID" 2>/dev/null
  wait 2>/dev/null
  rm -rf "$WORK"
}
trap cleanup EXIT

# ---------------------------------------------------------------- 1) 构建并起服务
say "1) 构建并启动 altaird"

if ! go build -o "$WORK/altaird" ./cmd/altaird 2>"$WORK/build.log"; then
  bad "Go 构建失败"; tail -20 "$WORK/build.log"; exit 1
fi
ok "Go 构建通过"

"$WORK/altaird" --addr "127.0.0.1:$PORT" --db "$WORK/altaird.db" --data "$WORK" --debug >"$SRVLOG" 2>&1 &
SRVPID=$!
for _ in $(seq 1 40); do
  curl -fsS -m 1 "$BASE/healthz" >/dev/null 2>&1 && break
  sleep 0.25
done
if curl -fsS -m 2 "$BASE/healthz" | grep -q '"ok":true'; then
  ok "/healthz 探活"
else
  bad "/healthz 探活失败"; tail -20 "$SRVLOG"; exit 1
fi

PW="$(grep -m1 '面板口令' "$SRVLOG" | awk '{print $2}')"
TOKEN="$(grep -m1 '设备 Token' "$SRVLOG" | sed 's/.*设备 Token *//' | tr -d ' ')"
[[ -n "$PW" && -n "$TOKEN" ]] && ok "首次启动打印了凭据（口令 / Token 各一份）" \
                             || { bad "凭据未从启动横幅解析出来"; cat "$SRVLOG"; }

# ---------------------------------------------------------------- 2) 鉴权
say "2) 面板鉴权"
code=$(curl -sS -o /dev/null -w '%{http_code}' "$BASE/api/v1/panel/devices")
[[ "$code" == "401" ]] && ok "未登录访问 /devices → 401" || bad "未登录应为 401，实际 $code"

code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/panel/login" \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"wrong"}')
[[ "$code" == "401" ]] && ok "错误口令 → 401" || bad "错误口令应为 401，实际 $code"

# 连续错误口令应最终触发退避（前 3 次宽容，第 4 次起 429）
for _ in 1 2 3 4 5 6; do
  code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/panel/login" \
    -H 'Content-Type: application/json' -d '{"username":"admin","password":"nope"}')
done
[[ "$code" == "429" ]] && ok "连续错误口令触发退避（最后一次 ${code}）" || bad "暴力尝试未被限制，$code"
sleep 1.5

code=$(curl -sS -o /dev/null -w '%{http_code}' -c "$JAR" -X POST "$BASE/api/v1/panel/login" \
  -H 'Content-Type: application/json' -d "{\"username\":\"admin\",\"password\":\"$PW\"}")
[[ "$code" == "200" ]] && ok "正确口令 → 200 且下发会话 Cookie" || bad "登录失败，$code"
grep -q altair_session "$JAR" && ok "Cookie 已写入客户端" || bad "没有拿到会话 Cookie"

code=$(curl -sS -o /dev/null -w '%{http_code}' -H "X-Altair-Token: wrong" -X POST \
  "$BASE/api/v1/device/report" -H 'Content-Type: application/json' -d '{"deviceId":"x","ts":1}')
[[ "$code" == "401" ]] && ok "设备 Token 错误 → 401" || bad "设备 Token 校验失效，$code"

# ---------------------------------------------------------------- 3) 假设备上报
say "3) 假设备按 v1 协议上报"
"$WORK/altaird" fake-device --server "$BASE" --n 2 --interval 5s --token "$TOKEN" >"$FAKELOG" 2>&1 &
FAKEPID=$!
sleep 7

DEVS="$(curl -sS -b "$JAR" "$BASE/api/v1/panel/devices")"
echo "$DEVS" | grep -q '"total":2' && ok "总览识别到 2 台设备" || { bad "设备数不对"; echo "$DEVS" | head -c 500; }
echo "$DEVS" | grep -q '"online":2' && ok "2 台在线（<3min）" || bad "在线数不对"
echo "$DEVS" | grep -q '"auth"' && bad "响应里出现了 auth 字段" || ok "响应是白名单结构（无 auth 键）"
if echo "$DEVS" | grep -qi 'panelPass\|pass_hash\|device_token\|deviceToken'; then
  bad "⚠ 响应泄露了凭据字段！"
else
  ok "不含任何凭据字段（旧版会泄露明文面板口令）"
fi

# ---------------------------------------------------------------- 4) 上报响应语义
say "4) 上报响应：合并往返 / 周期下发 / 配置版本"
REP="$(curl -sS -X POST "$BASE/api/v1/device/report" -H "X-Altair-Token: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"deviceId":"manual01","protocolVersion":1,"ts":'"$(date +%s000)"',"model":"Manual","android":"13","versionName":"0.24.0","foreground":"com.nexon.mod","armed":true,"engine":{"running":false,"state":"IDLE","cycleCount":0,"buffs":[]},"heartbeat":{"batteryPct":88,"thermalC":36.5,"netRttMs":31},"logTail":["手动验收上报"]}')"
echo "$REP" | grep -q '"desired"' && ok "响应带回 desired（一轮往返即可收指令）" || bad "响应缺 desired"
echo "$REP" | grep -q '"configRevision":"r' && ok "响应带回 configRevision" || bad "响应缺 configRevision"
echo "$REP" | grep -q '"nextReportInMs":60000' && ok "响应下发了上报周期（默认 60s）" || bad "nextReportInMs 不对: $REP"

code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/device/report" -H "X-Altair-Token: $TOKEN" \
  -H 'Content-Type: application/json' -d '{"deviceId":"manual01","ts":1}')
[[ "$code" == "429" ]] && ok "同设备高频重复上报 → 429（防打爆）" || bad "上报限流未生效，$code"

# ---------------------------------------------------------------- 5) 启停下发
say "5) 启停下发（期望状态 + rev 去重）"
sleep 3.2   # 等过「同设备上报最小间隔 3s」，否则会被 429 拦住
R1="$(curl -sS -b "$JAR" -X POST "$BASE/api/v1/panel/devices/manual01/command" \
  -H 'Content-Type: application/json' -d '{"action":"start"}')"
echo "$R1" | grep -q '"running":true' && ok "下发启动 → desired.running=true" || bad "启动下发失败: $R1"
R2="$(curl -sS -b "$JAR" -X POST "$BASE/api/v1/panel/devices/manual01/command" \
  -H 'Content-Type: application/json' -d '{"action":"stop"}')"
echo "$R2" | grep -q '"rev":2' && ok "第二次下发 rev 自增（设备据此去重）" || bad "rev 未自增: $R2"

R3="$(curl -sS -X POST "$BASE/api/v1/device/report" -H "X-Altair-Token: $TOKEN" \
  -H 'Content-Type: application/json' -d '{"deviceId":"manual01","protocolVersion":1,"ts":'"$(date +%s000)"',"engine":{"state":"IDLE","buffs":[]}}')"
echo "$R3" | grep -q '"running":false,"rev":2' && ok "设备下次上报即收到 stop（rev=2）" \
  || bad "期望状态没带回: $R3"

# ---------------------------------------------------------------- 6) 配置下发
say "6) 配置下发与 revision 语义"
C1="$(curl -sS -b "$JAR" -X PUT "$BASE/api/v1/panel/configs/default" \
  -H 'Content-Type: application/json' \
  -d '{"targetPkg":"com.nexon.mod","pressMs":90,"inputMethod":"keyevent","skillPoints":[[0.74,0.56]],"buff":[{"idx":1,"enabled":true,"key":1,"durationSec":280},{"idx":2,"enabled":true,"key":2,"durationSec":480},{"idx":3,"enabled":false,"key":3,"durationSec":280}],"notes":"冒烟验收","autoFreeMarket":true,"strollHoldMs":800,"strollJitterMs":45,"strollPressGapMs":120}')"
REV="$(echo "$C1" | sed -n 's/.*"revision":"\(r[0-9]*\)".*/\1/p')"
[[ -n "$REV" ]] && ok "服务端生成 revision=$REV" || bad "没有 generation revision: $C1"

CFG="$(curl -sS -X GET "$BASE/api/v1/device/config?deviceId=manual01" -H "X-Altair-Token: $TOKEN")"
echo "$CFG" | grep -q '"durationSec":480' && ok "设备拉到新配置（BUFF2 时长 480 秒）" || bad "设备配置不对: $CFG"
echo "$CFG" | grep -q '"durationMin":8' && ok "同时下发 durationMin=8（兼容老读者）" || bad "缺 durationMin 兼容字段: $CFG"
echo "$CFG" | grep -q '"strollHoldMs":800' && ok "走动时长随配置下发（800ms）" || bad "走动参数没下发: $CFG"
echo "$CFG" | grep -q '"strollJitterMs":45' && ok "走动抖动随配置下发（45ms）" || bad "抖动参数没下发: $CFG"
echo "$CFG" | grep -q '"autoFreeMarket":true' && ok "回城模式开关随配置下发到设备" || bad "设备没收到 autoFreeMarket: $CFG"
echo "$CFG" | grep -q "$REV" && ok "设备侧 revision 与面板一致" || bad "revision 不一致"

C2="$(curl -sS -b "$JAR" -X PUT "$BASE/api/v1/panel/configs/default" \
  -H 'Content-Type: application/json' -d '{"targetPkg":"com.nexon.mod","pressMs":90,"inputMethod":"keyevent","buff":[],"notes":"第二次"}')"
REV2="$(echo "$C2" | sed -n 's/.*"revision":"\(r[0-9]*\)".*/\1/p')"
[[ "$REV" != "$REV2" && -n "$REV2" ]] && ok "改动产生新 revision（设备才会热重载）" || bad "revision 没变化"

REVS="$(curl -sS -b "$JAR" "$BASE/api/v1/panel/configs/default/revisions")"
n=$(echo "$REVS" | grep -o '"revision"' | wc -l | tr -d ' ')
[[ "$n" -ge 2 ]] && ok "历史版本已归档（$n 条）" || bad "历史版本没归档: $REVS"
RB="$(curl -sS -b "$JAR" -X POST "$BASE/api/v1/panel/configs/default/rollback" \
  -H 'Content-Type: application/json' -d "{\"revision\":\"$REV\"}")"
echo "$RB" | grep -q '"durationSec":480' && ok "回滚到历史版本成功" || bad "回滚失败: $RB"

# ---------------------------------------------------------------- 7) 截图
say "7) 截图上传统"
IMG="$ROOT/tools/testdata/synth_field.png"
if [[ -f "$IMG" ]]; then
  SHOT="$(curl -sS -X POST "$BASE/api/v1/device/screenshot" -H "X-Altair-Token: $TOKEN" \
    -F "meta={\"deviceId\":\"manual01\",\"ts\":$(date +%s000),\"label\":\"冒烟\"}" \
    -F "file=@$IMG;type=image/png")"
  SID="$(echo "$SHOT" | sed -n 's/.*"id":"\(s_[^"]*\)".*/\1/p')"
  [[ -n "$SID" ]] && ok "截图上传成功 id=$SID" || bad "截图上传失败: $SHOT"
  LIST="$(curl -sS -b "$JAR" "$BASE/api/v1/panel/screenshots?deviceId=manual01")"
  echo "$LIST" | grep -q "$SID" && ok "截图列表可见" || bad "截图列表没有这条"
  ct=$(curl -sS -b "$JAR" -o "$WORK/get.png" -w '%{content_type}' "$BASE/api/v1/panel/screenshots/$SID")
  echo "$ct" | grep -qi 'image/png' && ok "取原图返回 $ct" || bad "取图 content-type 不对: $ct"
  code=$(curl -sS -b "$JAR" -o /dev/null -w '%{http_code}' -X DELETE "$BASE/api/v1/panel/screenshots/$SID")
  [[ "$code" == "200" ]] && ok "删除截图" || bad "删除截图失败 $code"
else
  bad "找不到测试图片 $IMG"
fi

# ---------------------------------------------------------------- 8) 历史/日志/审计/设置
say "8) 历史、日志、审计、设置"
H="$(curl -sS -b "$JAR" "$BASE/api/v1/panel/devices/manual01/history?step=60")"
echo "$H" | grep -q '"points"' && ok "曲线数据接口可用（$(echo "$H" | grep -o '"ts"' | wc -l | tr -d ' ') 点）" || bad "history 失败"
L="$(curl -sS -b "$JAR" "$BASE/api/v1/panel/devices/manual01/logs?limit=50")"
echo "$L" | grep -q '手动验收上报' && ok "日志检索到内容" || bad "日志为空: $L"
A="$(curl -sS -b "$JAR" "$BASE/api/v1/panel/audit?limit=20")"
echo "$A" | grep -q 'device.start' && ok "审计记录了启停操作" || bad "审计缺记录: $(echo "$A" | head -c 300)"
ME="$(curl -sS -b "$JAR" "$BASE/api/v1/panel/me")"
echo "$ME" | grep -q '"deviceToken"' && ok "/me 能拿到设备 Token（面板显示用）" || bad "/me 失败: $ME"
S="$(curl -sS -b "$JAR" "$BASE/api/v1/panel/settings")"
echo "$S" | grep -q 'defaultReportIntervalMs' && ok "设置接口可用" || bad "设置接口失败"
curl -sS -b "$JAR" -X PUT "$BASE/api/v1/panel/settings" -H 'Content-Type: application/json' \
  -d '{"defaultReportIntervalMs":30000,"reportRetentionDays":30,"logRetentionDays":5,"screenshotRetentionN":10,"screenshotRetentionDays":30}' >/dev/null
S2="$(curl -sS -b "$JAR" "$BASE/api/v1/panel/settings")"
echo "$S2" | grep -q '"defaultReportIntervalMs":30000' && ok "修改上报周期设置生效" || bad "设置没生效: $S2"
echo "$S2" | grep -q '"logRetentionDays":5' && ok "日志保留期可单独配置（日志比曲线数据占地方）" || bad "日志保留期没生效: $S2"

# ---------------------------------------------------------------- 9) SSE + 静态页
say "9) SSE 实时推送与 SPA 静态托管"
# 注意：macOS 没有 GNU 的 timeout（也没有 gtimeout），所以用 curl 自己的 --max-time
curl -sS -N --max-time 6 -b "$JAR" "$BASE/api/v1/panel/events" >"$WORK/sse.txt" 2>/dev/null &
SSEPID=$!
sleep 2
curl -sS -X POST "$BASE/api/v1/device/report" -H "X-Altair-Token: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"deviceId":"sse01","protocolVersion":1,"ts":'"$(date +%s000)"',"engine":{"state":"WAITING","running":true,"buffs":[]}}' >/dev/null
sleep 2
kill "$SSEPID" 2>/dev/null; wait "$SSEPID" 2>/dev/null
grep -q 'event: hello' "$WORK/sse.txt" && ok "SSE 建连（hello 事件）" || bad "SSE 没连上"
grep -q 'event: report' "$WORK/sse.txt" && ok "SSE 推送了 report 事件" || bad "SSE 没推 report: $(head -c 200 "$WORK/sse.txt")"

code=$(curl -sS -o "$WORK/index.html" -w '%{http_code}' "$BASE/")
[[ "$code" == "200" ]] && ok "根路径返回 SPA（${code}，$(wc -c <"$WORK/index.html" | tr -d ' ') 字节）" || bad "根路径 $code"
code=$(curl -sS -o /dev/null -w '%{http_code}' "$BASE/devices/some-id")
[[ "$code" == "200" ]] && ok "未知路由回退 index.html（SPA 路由可用）" || bad "SPA 回退失败 $code"

# 前端是否真的被打进二进制了
if grep -q '前端尚未构建' "$WORK/index.html"; then
  if [[ "${REQUIRE_WEB:-0}" == "1" ]]; then
    bad "要求内嵌真实前端（REQUIRE_WEB=1），但服务返回的是占位页 —— 先跑 make web"
  else
    ok "前端未构建（占位页）—— Go 侧验收不受影响；跑 make web 后可加 REQUIRE_WEB=1 复验"
  fi
else
  # 真实 SPA：必须引到打包产物，且产物能取到
  asset=$(sed -n 's/.*src="\(\/assets\/[^"]*\.js\)".*/\1/p' "$WORK/index.html" | head -1)
  if [[ -n "$asset" ]]; then
    act=$(curl -sS -o "$WORK/asset.js" -w '%{http_code}' "$BASE$asset")
    size=$(wc -c <"$WORK/asset.js" | tr -d ' ')
    if [[ "$act" == "200" && "$size" -gt 1000 ]]; then
      ok "内嵌前端可用：${asset}（${act}，${size} 字节）"
    else
      bad "前端资源取不到：$asset → ${act}，${size} 字节"
    fi
    ctype=$(curl -sS -o /dev/null -w '%{content_type}' "$BASE$asset")
    grep -qi 'javascript' <<<"$ctype" && ok "资源 content-type 正确（${ctype}）" || bad "资源 content-type 不对：${ctype}"

    # 真正的构建产物必须是合法 JS —— 当年那个「页面永远停在加载中」的故障，
    # 根因就是面板脚本语法失效；那条教训在这里变成一道自动检查。
    if command -v node >/dev/null; then
      cp "$WORK/asset.js" "$WORK/asset.mjs"
      if node --check "$WORK/asset.mjs" 2>"$WORK/syntax.err"; then
        ok "构建产物是合法 JS（node --check 通过）"
      else
        bad "构建产物语法有误：$(head -3 "$WORK/syntax.err" | tr '\n' ' ')"
      fi
    else
      ok "未安装 node，跳过构建产物语法检查"
    fi
  else
    bad "内嵌的 index.html 里找不到打包后的 JS 引用"
  fi
fi

# ---------------------------------------------------------------- 10) 批量 / 周期 / 限额
say "10) 批量启停、上报周期、边界值"
B="$(curl -sS -b "$JAR" -X POST "$BASE/api/v1/panel/devices/batch-command" \
  -H 'Content-Type: application/json' -d '{"ids":["manual01","fake01"],"action":"stop"}')"
echo "$B" | grep -q '"applied"' && ok "批量启停返回 applied 列表" || bad "批量启停失败: $B"
n=$(echo "$B" | grep -o '"manual01"' | wc -l | tr -d ' ')
[[ "$n" -ge 1 ]] && ok "批量结果包含目标设备" || bad "批量结果不含目标设备"

IV="$(curl -sS -b "$JAR" -X PUT "$BASE/api/v1/panel/devices/manual01/interval" \
  -H 'Content-Type: application/json' -d '{"reportIntervalMs":30000}')"
echo "$IV" | grep -q '"reportIntervalMs":30000' && ok "单设备上报周期可改" || bad "改周期失败: $IV"
IV2="$(curl -sS -b "$JAR" -X PUT "$BASE/api/v1/panel/devices/manual01/interval" \
  -H 'Content-Type: application/json' -d '{"reportIntervalMs":1000}')"
echo "$IV2" | grep -q '"reportIntervalMs":15000' && ok "过小周期被钳制到 15s（防止打爆服务器）" || bad "钳制失效: $IV2"
curl -sS -b "$JAR" -X PUT "$BASE/api/v1/panel/devices/manual01/interval" \
  -H 'Content-Type: application/json' -d '{"reportIntervalMs":30000}' >/dev/null
sleep 3.2
R4="$(curl -sS -X POST "$BASE/api/v1/device/report" -H "X-Altair-Token: $TOKEN" \
  -H 'Content-Type: application/json' -d '{"deviceId":"manual01","protocolVersion":1,"ts":'"$(date +%s000)"',"engine":{"state":"IDLE","buffs":[]}}')"
echo "$R4" | grep -q '"nextReportInMs":30000' && ok "改后的周期随响应下发（不用重发 APK）" || bad "周期没下发: $R4"

# 超过 2MB 的截图必须被拒（否则磁盘会被塞满）
head -c 3000000 /dev/zero > "$WORK/big.bin"
code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/device/screenshot" \
  -H "X-Altair-Token: $TOKEN" -F "meta={\"deviceId\":\"manual01\"}" -F "file=@$WORK/big.bin")
[[ "$code" == "400" || "$code" == "413" ]] && ok "超大文件被拒（${code}）" || bad "超大文件未被拒，$code"

code=$(curl -sS -o /dev/null -w '%{http_code}' -b "$JAR" "$BASE/api/v1/panel/devices/不存在的设备")
[[ "$code" == "404" ]] && ok "不存在的设备 → 404" || bad "应为 404，实际 ${code}"

# 路径穿越防护：deviceId 里的 ../ 不能把文件写到 shots/ 之外
EVIL="$(curl -sS -X POST "$BASE/api/v1/device/screenshot" -H "X-Altair-Token: $TOKEN" \
  -F 'meta={"deviceId":"../../../etc/evil","ts":'"$(date +%s000)"'}' \
  -F "file=@$IMG;type=image/png")"
if [[ -e /etc/evil.png || -e "$WORK/../etc" ]]; then
  bad "⚠ 路径穿越成功了！"
else
  ok "deviceId 里的 ../ 不会写到目录外（已收敛为安全目录名）"
fi
grep -q '"ok":true' <<<"$EVIL" && ok "穿越型 deviceId 被记录到安全目录（未拒绝但已收敛）" || ok "穿越型 deviceId 被拒绝"

# 超大 JSON 上报体不能把服务打崩（MaxBytesReader）
python3 -c "print('{\"deviceId\":\"huge\",\"ts\":1,\"logTail\":[\"' + 'x'*20000000 + '\"]}')" > "$WORK/huge.json"
code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/device/report" \
  -H "X-Altair-Token: $TOKEN" -H 'Content-Type: application/json' --data-binary "@$WORK/huge.json")
[[ "$code" == "400" || "$code" == "413" ]] && ok "超大上报体被拒（${code}）" || bad "超大上报体未被拒，${code}"
sleep 0.3
curl -fsS -m 2 "$BASE/healthz" >/dev/null && ok "拒绝超大请求后服务仍健康" || bad "服务在超大请求后不健康"

# ---------------------------------------------------------------- 11) 旧协议安全网
say "11) 旧设备协议安全网（--legacy-device-api）"
LPORT=$((PORT+1))
"$WORK/altaird" --addr "127.0.0.1:$LPORT" --db "$WORK/legacy.db" --data "$WORK/legacy" \
  --legacy-device-api >"$WORK/legacy.log" 2>&1 &
LEGPID=$!
for _ in $(seq 1 40); do
  curl -fsS -m 1 "http://127.0.0.1:$LPORT/healthz" >/dev/null 2>&1 && break
  sleep 0.25
done
LTOKEN="$(grep -m1 '设备 Token' "$WORK/legacy.log" | sed 's/.*设备 Token *//' | tr -d ' ')"
if [[ -n "$LTOKEN" ]]; then
  # 老 APK 打的路径与字段（v0 协议）
  code=$(curl -sS -o "$WORK/legacy-report.json" -w '%{http_code}' -X POST \
    "http://127.0.0.1:$LPORT/api/report" -H "X-Altair-Token: $LTOKEN" \
    -H 'Content-Type: application/json' \
    -d '{"deviceId":"oldapk01","ts":'"$(date +%s000)"',"versionName":"0.23.0","foreground":"com.nexon.mod","armed":true,"engine":{"running":true,"state":"WAITING","cycleCount":1,"buffs":[]},"logTail":["老协议上报"]}')
  [[ "$code" == "200" ]] && ok "旧端点 /api/report 可用（${code}）" || bad "旧端点不可用，$code"
  grep -q '"desired"' "$WORK/legacy-report.json" && ok "旧端点响应带回 desired（老 APK 也能收启停）" || bad "旧端点响应缺 desired"
  code=$(curl -sS -o "$WORK/legacy-cfg.json" -w '%{http_code}' \
    "http://127.0.0.1:$LPORT/api/config?deviceId=oldapk01" -H "X-Altair-Token: $LTOKEN")
  [[ "$code" == "200" ]] && ok "旧端点 /api/config 可用（${code}）" || bad "旧配置端点不可用，$code"
  # 默认关：主实例（没带开关）不应接受旧端点
  code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/api/report" \
    -H "X-Altair-Token: $TOKEN" -H 'Content-Type: application/json' -d '{"deviceId":"x","ts":1}')
  [[ "$code" == "404" || "$code" == "405" ]] && ok "默认关闭：不带开关时旧端点不可用（${code}）" || bad "旧端点默认应是关闭的，$code"
else
  bad "拿不到旧实例的 Token"
fi
kill "$LEGPID" 2>/dev/null

# ---------------------------------------------------------------- 12) CLI 子命令
say "12) 运维子命令"
CLIDB="$WORK/cli.db"
if "$WORK/altaird" show --db "$CLIDB" >"$WORK/cli-show.txt" 2>&1; then
  grep -q '设备 Token' "$WORK/cli-show.txt" && ok "show 打印设备 Token" || bad "show 输出不对"
  grep -q '无法显示' "$WORK/cli-show.txt" && ok "show 不再泄露面板口令（只存哈希）" || bad "show 行为与设计不符"
else
  bad "show 执行失败"
fi

# 这个 bug 真踩到过：Go 的 flag 包遇到第一个位置参数就停止解析，
# `set-token 值 --db 路径` 会把 --db 静默忽略、写到默认库上。
"$WORK/altaird" set-token "cli-token-abcdefgh" --db "$CLIDB" >/dev/null 2>&1
if "$WORK/altaird" show --db "$CLIDB" 2>/dev/null | grep -q 'cli-token-abcdefgh'; then
  ok "set-token 位置参数在前时 --db 生效（不会写错库）"
else
  bad "set-token 忽略了 --db，改动落到了别的库"
fi
"$WORK/altaird" set-token --db "$CLIDB" "cli-token-2-ijklmnop" >/dev/null 2>&1
"$WORK/altaird" show --db "$CLIDB" 2>/dev/null | grep -q 'cli-token-2-ijklmnop' \
  && ok "set-token flag 在前也生效" || bad "flag 在前的写法不生效"

if "$WORK/altaird" set-password abc --db "$CLIDB" >/dev/null 2>&1; then
  bad "过短口令应被拒绝"
else
  ok "过短口令被拒绝（退出码非 0）"
fi

if [[ -f "$CTRL/data/altair.db" ]]; then
  bad "CLI 误在默认路径建了库（说明 --db 被忽略）"
else
  ok "CLI 没有在默认路径误建数据库"
fi

# ---------------------------------------------------------------- 13) 设备备注（面板侧标注）
say "13) 设备备注：写设备表，不碰配置 revision"
rev_of() { curl -sS -X GET "$BASE/api/v1/device/config?deviceId=manual01" -H "X-Altair-Token: $TOKEN" \
  | sed -n 's/.*"revision":"\([^"]*\)".*/\1/p'; }
sleep 3.2
REV_BEFORE="$(rev_of)"
NOTE_JSON='{"notes":"冒烟备注 主教号"}'
N="$(curl -sS -b "$JAR" -X PUT "$BASE/api/v1/panel/devices/manual01" \
  -H 'Content-Type: application/json' -d "$NOTE_JSON")"
echo "$N" | grep -q '冒烟备注' && ok "备注写入成功并回显" || bad "备注写入失败: $N"
echo "$N" | grep -q '"id":"manual01"' && ok "返回更新后的总览条目" || bad "返回结构不对: $N"

REV_AFTER="$(rev_of)"
if [[ -n "$REV_BEFORE" && "$REV_BEFORE" == "$REV_AFTER" ]]; then
  ok "写备注不影响配置 revision（设备不会被白热重载一次）"
else
  bad "备注动了配置 revision：$REV_BEFORE → $REV_AFTER"
fi

curl -sS -b "$JAR" "$BASE/api/v1/panel/devices" | grep -q '冒烟备注' \
  && ok "总览接口带上备注（卡片要显示它）" || bad "总览没带备注"

code=$(curl -sS -o /dev/null -w '%{http_code}' -b "$JAR" -X PUT \
  "$BASE/api/v1/panel/devices/不存在的设备" -H 'Content-Type: application/json' -d "$NOTE_JSON")
[[ "${code}" == "404" ]] && ok "给不存在的设备写备注 → 404" || bad "应为 404，实际 ${code}"

code=$(curl -sS -o /dev/null -w '%{http_code}' -X PUT "$BASE/api/v1/panel/devices/manual01" \
  -H 'Content-Type: application/json' -d "$NOTE_JSON")
[[ "${code}" == "401" ]] && ok "未登录写备注 → 401" || bad "未登录应为 401，实际 ${code}"

# ---------------------------------------------------------------- 10) 收尾
say "结果"
if [[ -f "$SRVLOG" ]]; then
  panic=$(grep -c 'panic' "$SRVLOG" || true)
  [[ "$panic" == "0" ]] && ok "服务端日志无 panic" || bad "服务端出现 panic"
fi
printf "\n  通过 %d 项，失败 %d 项\n" "$PASS" "$FAIL"
[[ "$FAIL" == "0" ]] && { echo "  === 冒烟验收全部通过 ✅ ==="; exit 0; } || { echo "  === 存在失败项 ❌ ==="; exit 1; }

#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 集控服务器部署脚本
#
# 用法（在本机执行，通过 SSH 部署到远端）:
#   bash tools/deploy-control-server.sh root@207.56.20.205
#   bash tools/deploy-control-server.sh root@207.56.20.205 -p 22
#   bash tools/deploy-control-server.sh root@207.56.20.205 -i ~/.ssh/xxx
#
# 用法（已经登在服务器上，直接本机装）:
#   bash tools/deploy-control-server.sh --local
#
# 做的事：
#   1. 建 /opt/altair-control/ 并放入 control-server.py
#   2. 建 systemd 服务 altair-control（开机自启 + 崩溃自动重启）
#   3. 若 ufw 开着则放行端口
#   4. 本机自测一次 /api/config
#   5. 打印要填进 App 的地址
#
# 幂等：可重复执行，会覆盖脚本并重启服务。
# ---------------------------------------------------------------------------
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/tools/control-server.py"
APP_DIR="/opt/altair-control"
SVC="altair-control"
PORT="${PORT:-8899}"

[ -f "$SRC" ] || { echo "!! 找不到 $SRC"; exit 1; }

# ---------------------------------------------------------------- 远端模式
if [ "${1:-}" != "--local" ]; then
  TARGET="${1:-}"
  shift || true
  if [ -z "$TARGET" ]; then
    echo "用法: bash tools/deploy-control-server.sh user@host [-p 端口] [-i 密钥]"
    exit 1
  fi
  SSH_OPTS=(-o ConnectTimeout=15 -o StrictHostKeyChecking=accept-new)
  while [ $# -gt 0 ]; do
    case "$1" in
      -p) SSH_OPTS+=(-p "$2"); shift 2 ;;
      -i) SSH_OPTS+=(-i "$2"); shift 2 ;;
      *)  SSH_OPTS+=("$1"); shift ;;
    esac
  done

  echo "==== 部署到 $TARGET ===="
  echo "--- 1/3 上传脚本 ---"
  scp "${SSH_OPTS[@]}" "$SRC" "$TARGET:/tmp/altair-control.py" || exit 1

  echo "--- 2/3 远端安装 ---"
  ssh "${SSH_OPTS[@]}" "$TARGET" "PORT=$PORT APP_DIR=$APP_DIR SVC=$SVC bash -s" <<'REMOTE'
set -uo pipefail
echo "  主机: $(hostname)  $(uname -srm)"
command -v python3 >/dev/null || { echo "  !! 缺少 python3，请先安装"; exit 1; }
echo "  python3: $(python3 -V 2>&1)"

sudo=""; [ "$(id -u)" != "0" ] && sudo="sudo"

$sudo mkdir -p "$APP_DIR"
$sudo mv /tmp/altair-control.py "$APP_DIR/control-server.py"
$sudo chmod 755 "$APP_DIR/control-server.py"

# systemd 服务：开机自启 + 崩溃自动重启
$sudo tee /etc/systemd/system/$SVC.service >/dev/null <<EOF
[Unit]
Description=Altair Buff Control Server
After=network.target

[Service]
Type=simple
WorkingDirectory=$APP_DIR
ExecStart=/usr/bin/python3 -u $APP_DIR/control-server.py --port $PORT --host 0.0.0.0
Restart=always
RestartSec=5
StandardOutput=append:/var/log/$SVC.log
StandardError=append:/var/log/$SVC.log

[Install]
WantedBy=multi-user.target
EOF

$sudo systemctl daemon-reload
$sudo systemctl enable $SVC >/dev/null 2>&1
$sudo systemctl restart $SVC
sleep 2

# 防火墙
if command -v ufw >/dev/null && $sudo ufw status 2>/dev/null | grep -q "Status: active"; then
  $sudo ufw allow $PORT/tcp >/dev/null 2>&1 && echo "  ufw: 已放行 $PORT"
fi
if command -v firewall-cmd >/dev/null && $sudo firewall-cmd --state >/dev/null 2>&1; then
  $sudo firewall-cmd --permanent --add-port=$PORT/tcp >/dev/null 2>&1
  $sudo firewall-cmd --reload >/dev/null 2>&1 && echo "  firewalld: 已放行 $PORT"
fi

echo "--- 3/3 自测 ---"
sleep 1
curl -sS -m 8 "http://127.0.0.1:$PORT/api/config?deviceId=selftest" | head -c 200
echo
systemctl is-active $SVC >/dev/null && echo "  服务状态: 运行中 ✅" || echo "  服务状态: 未运行 ❌"
REMOTE
  RC=$?

  echo
  echo "============================================================"
  if [ $RC -eq 0 ]; then
    HOST_ONLY="${TARGET#*@}"
    echo " 部署完成 ✅"
    echo
    echo " 在 App 的「集控服务器地址」里填："
    echo "   http://$HOST_ONLY:$PORT"
    echo
    echo " 面板（浏览器打开）：  http://$HOST_ONLY:$PORT/"
    echo " 服务日志：            ssh $TARGET 'tail -f /var/log/$SVC.log'"
    echo
    echo " ⚠ 若云手机连不上，去云厂商控制台的**安全组**放行 $PORT 端口"
    echo "============================================================"
  else
    echo " 部署失败（ssh 返回 $RC）"
    echo "============================================================"
  fi
  exit $RC
fi

# ---------------------------------------------------------------- 本机模式
echo "==== 本机安装（$APP_DIR, 端口 $PORT）===="
sudo=""; [ "$(id -u)" != "0" ] && sudo="sudo"
$sudo mkdir -p "$APP_DIR"
$sudo cp "$SRC" "$APP_DIR/control-server.py"
$sudo chmod 755 "$APP_DIR/control-server.py"
$sudo tee /etc/systemd/system/$SVC.service >/dev/null <<EOF
[Unit]
Description=Altair Buff Control Server
After=network.target

[Service]
Type=simple
WorkingDirectory=$APP_DIR
ExecStart=/usr/bin/python3 -u $APP_DIR/control-server.py --port $PORT --host 0.0.0.0
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
$sudo systemctl daemon-reload
$sudo systemctl enable --now $SVC
$sudo systemctl restart $SVC
sleep 2
echo "自测:"; curl -sS -m 8 "http://127.0.0.1:$PORT/api/config?deviceId=selftest"; echo
echo "监听:"; ss -lntp 2>/dev/null | grep ":$PORT" || true

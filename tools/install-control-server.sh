#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 集控服务器 · 服务器端一键安装
#
# 在**服务器上**执行（不用先下载任何东西）：
#
#   curl -fsSL https://gh-proxy.com/https://raw.githubusercontent.com/ecfxs/altair-buff/main/tools/install-control-server.sh | sudo bash
#
# 或者先把本文件传上去再跑：
#   bash install-control-server.sh
#
# 做的事：下载 control-server.py → 装成 systemd 服务 → 放行防火墙 → 自测
# 幂等：可重复执行。
# ---------------------------------------------------------------------------
set -uo pipefail

PORT="${PORT:-8899}"
APP_DIR="${APP_DIR:-/opt/altair-control}"
SVC="${SVC:-altair-control}"
REPO_RAW="https://raw.githubusercontent.com/ecfxs/altair-buff/main/tools"
# raw.githubusercontent.com 在国内常不通，默认走代理；可用 MIRROR="" 关闭
MIRROR="${MIRROR-https://gh-proxy.com/}"

say() { echo "[$(date +%H:%M:%S)] $*"; }

say "==== 集控服务器安装 ===="
say "主机: $(hostname)  $(uname -srm)"

# ---- python3 ----
if ! command -v python3 >/dev/null; then
  say "未找到 python3，尝试安装 ..."
  if command -v apt-get >/dev/null; then
    apt-get update -qq && apt-get install -y -qq python3
  elif command -v dnf >/dev/null; then
    dnf install -y -q python3
  elif command -v yum >/dev/null; then
    yum install -y -q python3
  fi
fi
command -v python3 >/dev/null || { say "!! 缺 python3 且自动安装失败，请手动装"; exit 1; }
say "python3: $(python3 -V 2>&1)"

# ---- 取服务器脚本 ----
mkdir -p "$APP_DIR"
LOCAL_SRC="$(dirname "$0")/control-server.py"
if [ -f "$LOCAL_SRC" ]; then
  say "使用本地 control-server.py"
  cp "$LOCAL_SRC" "$APP_DIR/control-server.py"
else
  URL1="${MIRROR}${REPO_RAW}/control-server.py"
  URL2="${REPO_RAW}/control-server.py"
  say "下载 control-server.py ..."
  if curl -fsSL --retry 2 --connect-timeout 12 -o "$APP_DIR/control-server.py" "$URL1"; then
    say "  来源: $URL1"
  elif curl -fsSL --retry 2 --connect-timeout 12 -o "$APP_DIR/control-server.py" "$URL2"; then
    say "  来源: $URL2"
  else
    say "!! 下载失败，请手动把 control-server.py 传到 $APP_DIR/"
    exit 1
  fi
fi
chmod 755 "$APP_DIR/control-server.py"
say "已就位: $APP_DIR/control-server.py  ($(wc -c < "$APP_DIR/control-server.py") bytes)"

# ---- systemd ----
if command -v systemctl >/dev/null; then
  say "写入 systemd 服务 $SVC ..."
  cat > /etc/systemd/system/$SVC.service <<EOF
[Unit]
Description=Altair Buff Control Server
After=network.target

[Service]
Type=simple
WorkingDirectory=$APP_DIR
ExecStart=$(command -v python3) -u $APP_DIR/control-server.py --port $PORT --host 0.0.0.0
Restart=always
RestartSec=5
StandardOutput=append:/var/log/$SVC.log
StandardError=append:/var/log/$SVC.log

[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable $SVC >/dev/null 2>&1
  systemctl restart $SVC
  sleep 2
  systemctl is-active $SVC >/dev/null && say "服务状态: 运行中 ✅" || say "服务状态: 未运行 ❌（看 journalctl -u $SVC）"
  say "日志: /var/log/$SVC.log"
else
  say "无 systemd，改用 nohup 后台运行"
  pkill -f "control-server.py" 2>/dev/null
  nohup python3 -u "$APP_DIR/control-server.py" --port "$PORT" --host 0.0.0.0 \
    >> /var/log/$SVC.log 2>&1 &
  sleep 2
  say "已启动 (pid $!)"
fi

# ---- 防火墙 ----
if command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q "Status: active"; then
  ufw allow $PORT/tcp >/dev/null 2>&1 && say "ufw: 已放行 $PORT"
fi
if command -v firewall-cmd >/dev/null && firewall-cmd --state >/dev/null 2>&1; then
  firewall-cmd --permanent --add-port=$PORT/tcp >/dev/null 2>&1
  firewall-cmd --reload >/dev/null 2>&1 && say "firewalld: 已放行 $PORT"
fi

# ---- 自测 ----
say "自测 /api/config ..."
curl -sS -m 8 "http://127.0.0.1:$PORT/api/config?deviceId=selftest" || say "  自测失败"
echo
IP="$(curl -sS -m 6 https://api.ipify.org 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}')"
echo "============================================================"
say "安装完成"
echo "  面板（浏览器）:  http://${IP:-<服务器IP>}:$PORT/"
echo "  App 里填      :  http://${IP:-<服务器IP>}:$PORT"
echo
echo "  ⚠ 若从外网连不上，去云厂商控制台的【安全组】放行 $PORT 端口"
echo "     （本脚本只能放行服务器自身的 ufw/firewalld，管不到云安全组）"
echo "============================================================"

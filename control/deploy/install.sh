#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# altaird 一键安装（在 VPS 上以 root 运行）
#
#   bash install.sh control.example.com [altaird 二进制路径]
#
# 做的事：
#   1. 装 Caddy（缺失时）
#   2. 建 altair 系统用户与 /var/lib/altair 数据目录
#   3. 放二进制到 /opt/altair/altaird
#   4. 写 systemd unit 与 Caddyfile（域名替换）
#   5. 起服务、放行防火墙、自测 /healthz
#   6. 打印面板地址与初始凭据（面板口令只显示这一次！）
#
# 幂等：可以重复运行，不会重复建用户或覆盖数据。
# ---------------------------------------------------------------------------
set -euo pipefail

DOMAIN="${1:-}"
BIN_SRC="${2:-}"
APP_DIR=/opt/altair
DATA_DIR=/var/lib/altair
SVC=altaird
PORT=8788

say() { printf "\n\033[36m== %s\033[0m\n" "$*"; }
ok()  { printf "  ✅ %s\n" "$*"; }
bad() { printf "  ❌ %s\n" "$*"; }

if [[ "$(id -u)" != "0" ]]; then
  echo "请用 root 运行：sudo bash install.sh <域名> [二进制路径]" >&2
  exit 1
fi
if [[ -z "$DOMAIN" ]]; then
  echo "用法: bash install.sh <域名> [altaird 二进制路径]" >&2
  echo "例如: bash install.sh control.example.com ./altaird-linux-amd64" >&2
  exit 1
fi

HERE="$(cd "$(dirname "$0")" && pwd)"
if [[ -z "$BIN_SRC" ]]; then
  for cand in "$HERE/altaird-linux-amd64" "$HERE/altaird" "$HERE/../dist/altaird-linux-amd64"; do
    [[ -f "$cand" ]] && { BIN_SRC="$cand"; break; }
  done
fi
if [[ -z "$BIN_SRC" || ! -f "$BIN_SRC" ]]; then
  bad "找不到 altaird 二进制。请先从开发机跑 make release，把 dist/altaird-linux-amd64 传上来"
  exit 1
fi

# 域名会被写进 Caddyfile，先做格式校验：既避免 sed 被特殊字符搞坏，
# 也避免把明显不是域名的东西（含 / 或空格）写进配置。
if [[ ! "$DOMAIN" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ || "$DOMAIN" != *.* ]]; then
  bad "域名格式看起来不对：$DOMAIN"
  echo "     例：control.example.com（不要带 http:// 、路径或空格）" >&2
  exit 1
fi

for f in "$HERE/altaird.service" "$HERE/Caddyfile"; do
  if [[ ! -f "$f" ]]; then
    bad "缺少模板文件 $f"
    echo "     请把整个 deploy/ 目录一起传上来（scp -r deploy root@VPS:/tmp/）" >&2
    exit 1
  fi
done

# ---------------------------------------------------------------- 1) Caddy
say "1) Caddy"
if command -v caddy >/dev/null; then
  ok "已安装：$(caddy version | head -1)"
else
  if command -v apt-get >/dev/null; then
    apt-get update -qq
    apt-get install -y -qq debian-keyring debian-archive-keyring apt-transport-https curl gnupg >/dev/null
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
      | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
      > /etc/apt/sources.list.d/caddy-stable.list
    apt-get update -qq && apt-get install -y -qq caddy >/dev/null
    ok "Caddy 已安装"
  else
    bad "不是 apt 系统，请自行安装 Caddy 后重跑"
    exit 1
  fi
fi

# ---------------------------------------------------------------- 2) 用户与目录
say "2) 用户与数据目录"
if id altair >/dev/null 2>&1; then
  ok "用户 altair 已存在"
else
  useradd --system --home "$DATA_DIR" --shell /usr/sbin/nologin altair
  ok "已创建系统用户 altair"
fi
mkdir -p "$APP_DIR" "$DATA_DIR/shots"
chown -R altair:altair "$DATA_DIR"
chmod 750 "$DATA_DIR"
ok "$DATA_DIR 就绪（数据库与截图都在这里）"

# ---------------------------------------------------------------- 3) 二进制
say "3) 安装二进制"
if [[ -f "$APP_DIR/altaird" ]]; then
  cp -f "$APP_DIR/altaird" "$APP_DIR/altaird.bak"
  ok "已备份上一版到 altaird.bak"
fi
install -m 0755 "$BIN_SRC" "$APP_DIR/altaird"
ok "$APP_DIR/altaird（$(du -h "$APP_DIR/altaird" | cut -f1)）"

# ---------------------------------------------------------------- 4) systemd + Caddy
say "4) systemd 与 Caddyfile"
install -m 0644 "$HERE/altaird.service" "/etc/systemd/system/$SVC.service"
systemctl daemon-reload
systemctl enable "$SVC" >/dev/null
ok "unit 已安装"

sed "s|control\.example\.com|$DOMAIN|g" "$HERE/Caddyfile" > /etc/caddy/Caddyfile
if ! grep -q "$DOMAIN" /etc/caddy/Caddyfile; then
  bad "Caddyfile 里没写入域名，检查模板是否被改过"
  exit 1
fi
mkdir -p /var/log/caddy && chown caddy:caddy /var/log/caddy 2>/dev/null || true
ok "Caddyfile 已写入（域名 ${DOMAIN}）"

# ---------------------------------------------------------------- 5) 起服务
say "5) 启动"
systemctl restart "$SVC"
sleep 2
if systemctl is-active --quiet "$SVC"; then
  ok "altaird 运行中"
else
  bad "altaird 启动失败，最近日志："
  journalctl -u "$SVC" -n 20 --no-pager || true
  exit 1
fi

if curl -fsS -m 3 "http://127.0.0.1:$PORT/healthz" >/dev/null; then
  ok "本地 /healthz 探活通过"
else
  bad "本地探活失败"
fi

systemctl reload caddy 2>/dev/null || systemctl restart caddy
ok "Caddy 已重载（证书申请可能需要几十秒）"

# 防火墙尽力而为
if command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q active; then
  ufw allow 80/tcp >/dev/null 2>&1 || true
  ufw allow 443/tcp >/dev/null 2>&1 || true
  ok "ufw 已放行 80/443"
fi

# ---------------------------------------------------------------- 6) 凭据
say "6) 凭据（面板口令只显示这一次）"
journalctl --sync 2>/dev/null || true
BANNER=""
for _ in $(seq 1 10); do
  BANNER="$(journalctl -u "$SVC" --no-pager -n 80 2>/dev/null | sed -n '/====/,/====/p' | tail -25)"
  # shellcheck disable=SC2143
  [[ -n "$BANNER" ]] && grep -q "设备 Token" <<<"$BANNER" && break
  sleep 1
done
if [[ -n "$BANNER" ]]; then
  echo "$BANNER"
else
  bad "没能从 journal 里读到启动横幅（凭据没显示）"
  echo "     手动查看：journalctl -u $SVC -n 60 --no-pager" >&2
  echo "     口令忘了就重设：$APP_DIR/altaird set-password 新口令 --db $DATA_DIR/altair.db" >&2
fi

cat <<EOF

  ────────────────────────────────────────────────
   面板地址   https://$DOMAIN/
   设备端     https://$DOMAIN  （填进 App 的「集控服务器」）
   初始口令   见上面 journal 输出；错过了就执行：
                sudo -u altair $APP_DIR/altaird show            # 看 Token
                $APP_DIR/altaird set-password 新口令 --db $DATA_DIR/altair.db
   日志       journalctl -u $SVC -f
   升级       bash upgrade.sh ./altaird-linux-amd64
  ────────────────────────────────────────────────

EOF

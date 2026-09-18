#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 上线前置检查（在 VPS 上运行，装之前先跑这个）
#
#   sudo bash preflight.sh <域名> [altaird 二进制路径]
#
# 它不修改任何东西，只回答一个问题：这台机器现在能不能装上。
# 每条失败都给出具体的下一步命令 —— 别让人对着 "检查失败" 发呆。
# ---------------------------------------------------------------------------
set -uo pipefail

DOMAIN="${1:-}"
BIN="${2:-./altaird-linux-amd64}"
APP_DIR=/opt/altair
DATA_DIR=/var/lib/altair
PORT=8788

PASS=0
FAIL=0
WARN=0

ok()   { printf "  ✅ %s\n" "$*"; PASS=$((PASS+1)); }
bad()  { printf "  ❌ %s\n" "$*"; FAIL=$((FAIL+1)); }
warn() { printf "  ⚠  %s\n" "$*"; WARN=$((WARN+1)); }
say()  { printf "\n\033[36m== %s\033[0m\n" "$*"; }
hint() { printf "     → %s\n" "$*"; }

echo "======================================================================"
echo "  altaird 上线前置检查"
echo "  域名: ${DOMAIN:-（未提供）}   二进制: $BIN"
echo "======================================================================"

# ---------------------------------------------------------------- 系统
say "1) 系统与权限"
if [[ "$(id -u)" == "0" ]]; then ok "以 root 运行"; else
  bad "不是 root"; hint "用 sudo bash preflight.sh ... 重跑（install.sh 需要 root）"
fi

ARCH="$(uname -m)"
case "$ARCH" in
  x86_64|amd64) ok "架构 ${ARCH}（与 amd64 二进制匹配）" ;;
  aarch64|arm64) warn "架构 ${ARCH}：需要 arm64 版二进制，当前提供的是 amd64 时跑不起来" ;;
  *) warn "架构 $ARCH 未验证" ;;
esac

if command -v systemctl >/dev/null; then ok "systemd 可用"; else
  bad "没有 systemd"; hint "本脚本按 systemd 部署；容器里请自行用其他方式托管进程"
fi

# ---------------------------------------------------------------- 二进制
say "2) 待部署的二进制"
if [[ -f "$BIN" ]]; then
  ok "文件存在（$(du -h "$BIN" | cut -f1)）"
  kind="$(file -b "$BIN" 2>/dev/null || echo '?')"
  case "$kind" in
    *ELF*static*) ok "静态链接 ELF（无运行时依赖）" ;;
    *ELF*) warn "是 ELF 但非静态：确认目标机有对应 libc" ;;
    *) bad "不是 Linux ELF：$kind"; hint "用 make release 交叉编译出 dist/altaird-linux-amd64" ;;
  esac
  if [[ -x "$BIN" ]]; then ok "可执行位已设"; else
    warn "没有可执行位（install.sh 会 install -m 0755，不影响）"
  fi
else
  bad "找不到二进制 $BIN"; hint "先在开发机跑 make release，再 scp 上来"
fi

# ---------------------------------------------------------------- 网络
say "3) 域名与端口"
if [[ -n "$DOMAIN" ]]; then
  # 尽力解析：getent / dig / host 三选一
  IP=""
  for tool in "getent hosts" "dig +short" "host"; do
    if command -v ${tool%% *} >/dev/null; then
      IP="$($tool "$DOMAIN" 2>/dev/null | grep -oE '([0-9]{1,3}\.){3}[0-9]{1,3}' | head -1)"
      [[ -n "$IP" ]] && break
    fi
  done
  if [[ -n "$IP" ]]; then
    ok "$DOMAIN 解析到 $IP"
    # 与本机公网 IP 比对（尽力而为，取不到就跳过）
    MYIP="$(curl -fsS -m 5 https://api.ipify.org 2>/dev/null || true)"
    if [[ -n "$MYIP" && "$MYIP" != "$IP" ]]; then
      warn "解析到的 $IP 与本机出口 IP $MYIP 不一致"
      hint "若用了 CDN/反代属正常；否则 Caddy 签证书会失败"
    fi
  else
    bad "$DOMAIN 解析不出 IP"; hint "先加一条 A 记录指向本机，再做后续步骤"
  fi
  case "$DOMAIN" in
    control.example.com) warn "域名还是示例值"; hint "换成你自己的域名" ;;
  esac
else
  warn "未提供域名：Caddy 无法自动签证书"
  hint "bash preflight.sh <你的域名> [二进制路径]"
fi

for p in 80 443; do
  if command -v ss >/dev/null; then
    if ss -ltn 2>/dev/null | grep -q ":$p "; then
      warn "端口 $p 已被占用"; hint "ss -ltnp | grep :$p 看是谁；Caddy 需要它"
    else
      ok "端口 $p 空闲"
    fi
  fi
done
if command -v ss >/dev/null; then
  if ss -ltn 2>/dev/null | grep -q ":$PORT "; then
    bad "端口 $PORT 已被占用"; hint "fuser -k $PORT/tcp 或改 unit 里的 --addr"
  else
    ok "端口 $PORT 空闲（altaird 只监听回环）"
  fi
fi

# ---------------------------------------------------------------- 磁盘
say "4) 磁盘与目录"
AVAIL="$(df -Pk /var 2>/dev/null | awk 'NR==2{print $4}')"
if [[ -n "$AVAIL" ]]; then
  AVAIL_GB=$((AVAIL / 1024 / 1024))
  if [[ "$AVAIL_GB" -lt 2 ]]; then
    bad "/var 可用 ${AVAIL_GB}GB，太小"
    hint "200 台设备 60s 周期约占 2.9GB（30 天）；详见 deploy/RUNBOOK.md 第 0 节"
  elif [[ "$AVAIL_GB" -lt 5 ]]; then
    warn "/var 可用 ${AVAIL_GB}GB，偏紧"
    hint "把设置页的 reportRetentionDays 调小，或扩容"
  else
    ok "/var 可用 ${AVAIL_GB}GB（200 台约需 3GB）"
  fi
fi
if [[ -d "$DATA_DIR" ]]; then
  ok "数据目录已存在：$DATA_DIR"
  if [[ -f "$DATA_DIR/altair.db" ]]; then
    warn "已存在数据库（这次是升级而不是首装）"
    hint "用 deploy/upgrade.sh 而不是 install.sh；迁移会在启动时自动跑"
  fi
else
  ok "数据目录不存在（install.sh 会创建）"
fi
if [[ -d "$APP_DIR" && -x "$APP_DIR/altaird" ]]; then
  warn "$APP_DIR/altaird 已存在（重复安装会先备份为 altaird.bak）"
fi

# ---------------------------------------------------------------- Caddy
say "5) Caddy"
if command -v caddy >/dev/null; then
  ok "已安装：$(caddy version 2>/dev/null | head -1)"
else
  if command -v apt-get >/dev/null; then
    ok "未安装，但 apt-get 可用（install.sh 会自动装）"
  else
    bad "未安装 Caddy 且不是 apt 系统"; hint "自行安装 Caddy 后重跑"
  fi
fi
if [[ -f /etc/caddy/Caddyfile ]]; then
  warn "/etc/caddy/Caddyfile 已存在，install.sh 会覆盖它"
  hint "先备份：cp /etc/caddy/Caddyfile /etc/caddy/Caddyfile.bak"
fi

# ---------------------------------------------------------------- 汇总
echo
echo "======================================================================"
printf "  通过 %d 项，警告 %d 项，失败 %d 项\n" "$PASS" "$WARN" "$FAIL"
if [[ "$FAIL" == "0" ]]; then
  echo "  ✅ 可以安装：sudo bash install.sh ${DOMAIN:-<域名>} $BIN"
else
  echo "  ❌ 先解决上面 ❌ 的项再安装"
fi
echo "======================================================================"
exit $(( FAIL > 0 ? 1 : 0 ))

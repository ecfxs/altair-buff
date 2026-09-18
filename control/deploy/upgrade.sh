#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# altaird 升级（在 VPS 上以 root 运行）
#
#   bash upgrade.sh ./altaird-linux-amd64
#
# 流程：备份现有二进制 → 替换 → 重启 → 自测 /healthz → 失败自动回滚。
# 数据库迁移在服务启动时自动执行（内嵌迁移，幂等）。
# ---------------------------------------------------------------------------
set -euo pipefail

BIN_SRC="${1:-}"
APP_DIR=/opt/altair
DATA_DIR=/var/lib/altair
SVC=altaird
PORT=8788

say() { printf "\n\033[36m== %s\033[0m\n" "$*"; }
ok()  { printf "  ✅ %s\n" "$*"; }
bad() { printf "  ❌ %s\n" "$*"; }

if [[ "$(id -u)" != "0" ]]; then
  echo "请用 root 运行：sudo bash upgrade.sh <新二进制>" >&2
  exit 1
fi
if [[ ! -f "$BIN_SRC" ]]; then
  echo "用法: bash upgrade.sh <altaird 二进制路径>" >&2
  exit 1
fi

say "升级 altaird"

# 先确认是在一个装好的环境里升级，而不是拿它当首装用
if [[ ! -f "/etc/systemd/system/$SVC.service" ]]; then
  bad "没找到 $SVC.service —— 这台机器还没安装过"
  echo "     首次安装请用：sudo bash install.sh <域名> $BIN_SRC" >&2
  exit 1
fi

# 关键：替换之前先确认新二进制**能在这台机器上跑**。
# 架构不匹配（比如把 amd64 传到 arm 机器）如果等到重启才发现，
# 就白停一次服务、白回滚一轮。
NEW_VER="$("$BIN_SRC" version 2>/dev/null || true)"
if [[ -z "$NEW_VER" ]]; then
  bad "新二进制无法执行（架构不匹配？文件损坏？）"
  echo "     实测：$BIN_SRC version" >&2
  file -b "$BIN_SRC" 2>/dev/null | sed 's/^/     /' >&2 || true
  echo "     本机架构：$(uname -m)" >&2
  exit 1
fi
ok "新二进制可执行：$NEW_VER"

# 数据先备份（迁移不可逆，出事能回去）
say "备份数据"
STAMP="$(date +%Y%m%d-%H%M%S)"
if [[ -f "$DATA_DIR/altair.db" ]]; then
  DBSZ="$(stat -c %s "$DATA_DIR/altair.db" 2>/dev/null || stat -f %z "$DATA_DIR/altair.db")"
  AVAIL="$(df -Pk "$DATA_DIR" | awk 'NR==2{print $4*1024}')"
  if [[ -n "$AVAIL" && "$AVAIL" -lt $((DBSZ * 2)) ]]; then
    bad "磁盘空间不足：数据库 $((DBSZ/1024/1024))MB，可用 $((AVAIL/1024/1024))MB"
    echo "     备份需要约 2 倍数据库大小的空间；先清理再升级" >&2
    exit 1
  fi
  # SQLite 用 .backup 才能在服务运行时拿到一致快照
  if command -v sqlite3 >/dev/null; then
    sqlite3 "$DATA_DIR/altair.db" ".backup '$DATA_DIR/altair.db.bak-$STAMP'"
  else
    cp -f "$DATA_DIR/altair.db" "$DATA_DIR/altair.db.bak-$STAMP"
  fi
  ok "已备份 altair.db.bak-$STAMP"
  # 只留最近 3 份，免得备份把磁盘吃满（备份策略自己把自己搞死很常见）
  ls -1t "$DATA_DIR"/altair.db.bak-* 2>/dev/null | tail -n +4 | while read -r old; do
    rm -f "$old" && echo "  （清理旧备份 $(basename "$old")）"
  done
else
  ok "还没有数据库（首次启动会创建）"
fi

systemctl stop "$SVC" || true
cp -f "$APP_DIR/altaird" "$APP_DIR/altaird.bak"
install -m 0755 "$BIN_SRC" "$APP_DIR/altaird"
ok "二进制已替换（上一版存为 altaird.bak）"

systemctl start "$SVC"
sleep 3

if systemctl is-active --quiet "$SVC" && curl -fsS -m 3 "http://127.0.0.1:$PORT/healthz" >/dev/null; then
  ok "升级成功，服务健康"
  systemctl status "$SVC" --no-pager -n 5 | sed 's/^/  /'
  exit 0
fi

bad "升级后服务不健康，执行回滚"
journalctl -u "$SVC" -n 25 --no-pager | sed 's/^/  /' || true
systemctl stop "$SVC" || true
cp -f "$APP_DIR/altaird.bak" "$APP_DIR/altaird"
systemctl start "$SVC"
sleep 2
if systemctl is-active --quiet "$SVC"; then
  bad "已回滚到上一版（服务恢复运行）"
else
  bad "回滚后仍不正常，请手动检查"
fi
exit 1

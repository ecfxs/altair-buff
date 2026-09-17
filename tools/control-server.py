#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
集控服务器（参考实现）
======================

给云手机上的 APK 提供「状态上报 + 配置下发」两件事，并自带一个网页面板。
只用 Python 标准库，不需要装任何依赖。

## 为什么是设备主动上报
云手机跑在机房 NAT 后面，没有公网 IP，**服务器无法主动连它**。
所以协议只能是设备定时来敲服务器：

    GET  /api/config?deviceId=xxx   设备拉取自己的配置
    POST /api/report                设备上报自己的状态

## 启动
    python3 tools/control-server.py                  # 监听 0.0.0.0:8899
    python3 tools/control-server.py --port 9000

然后在 App 里填： http://<这台机器的公网IP>:8899

## 网页面板
浏览器打开 http://127.0.0.1:8899/
可以看到所有已上报的设备、最后在线时间、状态，并逐台编辑配置。

## 配置字段（设备端认识的）
    revision     必填，改了它设备才会重新加载
    targetPkg    目标游戏包名（前台门禁用）
    pressMs      点击按压时长（毫秒）
    skillPoints  技能键坐标 [[x,y], ...]（归一化）
    notes        备注，设备端只记录

存储：同目录下的 control-store.json（首次运行自动创建）
"""

import argparse
import base64
import hmac
import html
import json
import os
import secrets
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

STORE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "control-store.json")
LOCK = threading.Lock()

def gen_secret(nbytes=18):
    """生成 URL 安全的随机密钥。"""
    return secrets.token_urlsafe(nbytes)


def ensure_auth(st):
    """确保存储里有凭据；首次运行自动生成并落盘。

    刻意把「面板密码」和「设备 token」分成两套：
      · 面板密码 —— 人用，走 HTTP Basic Auth
      · 设备 token —— APK 用，走 X-Altair-Token 头
    两者独立，APK 里存的 token 泄漏不会连带泄漏面板密码。
    """
    a = st.setdefault("auth", {})
    changed = False
    if not a.get("panelUser"):
        a["panelUser"] = "admin"; changed = True
    if not a.get("panelPass"):
        a["panelPass"] = gen_secret(12); changed = True
    if not a.get("deviceToken"):
        a["deviceToken"] = gen_secret(18); changed = True
    if changed:
        save_store(st)
    return a



PANEL_HTML = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>阿尔泰挂机 · 监控台</title>
<style>
 *{box-sizing:border-box}
 body{background:#0d1014;color:#d8dde3;font:13px/1.6 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif;margin:0;padding:18px}
 h1{font-size:17px;margin:0}
 h2{font-size:12px;color:#8b97a6;margin:22px 0 8px;letter-spacing:.06em}
 .top{display:flex;align-items:center;gap:14px;flex-wrap:wrap}
 .stat{background:#151a21;border:1px solid #2c333d;border-radius:8px;padding:8px 14px;min-width:96px}
 .stat b{display:block;font-size:20px;color:#eaf0f6;line-height:1.2}
 .stat span{font-size:11px;color:#8b97a6}
 .muted{color:#6c7787;font-size:11px}
 .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(330px,1fr));gap:12px}
 .card{background:#151a21;border:1px solid #2c333d;border-radius:8px;padding:12px;position:relative}
 .card.on{border-color:#2f6b3f} .card.off{border-color:#5a2a30;opacity:.72}
 .did{font-family:ui-monospace,monospace;color:#7fd18b;font-size:14px;font-weight:600}
 .age{position:absolute;right:12px;top:12px;font-size:11px}
 .kv{font-size:11px;color:#9aa7b6;margin-top:3px}
 .kv code{color:#c9d4e0}
 pre{background:#0b0e12;border:1px solid #242b34;border-radius:5px;padding:8px;margin:8px 0 0;
     font-size:10.5px;color:#9aa7b6;max-height:112px;overflow:auto;white-space:pre-wrap;word-break:break-all}
 textarea{width:100%;height:180px;background:#0b0e12;color:#c9d4e0;border:1px solid #3a4450;border-radius:6px;
          padding:10px;font-family:ui-monospace,monospace;font-size:12px}
 input{background:#0b0e12;color:#c9d4e0;border:1px solid #3a4450;border-radius:6px;padding:7px;font-size:12px}
 button{background:#2563eb;color:#fff;border:0;border-radius:6px;padding:8px 16px;cursor:pointer;font-size:12px}
 button:hover{background:#3b82f6}
 .bar{height:3px;background:#1b2028;border-radius:2px;overflow:hidden;margin-top:14px}
 .bar i{display:block;height:100%;background:#2563eb;width:0;transition:width .3s linear}
 .token{background:#0b0e12;border:1px solid #3a4450;border-radius:6px;padding:9px;
        font-family:ui-monospace,monospace;font-size:13px;color:#7fd18b;word-break:break-all}
</style></head><body>

<div class="top">
  <h1>阿尔泰挂机 · 监控台</h1>
  <div class="stat"><b id="s-total">-</b><span>设备总数</span></div>
  <div class="stat"><b id="s-online" style="color:#7fd18b">-</b><span>在线(&lt;3min)</span></div>
  <div class="stat"><b id="s-armed" style="color:#7fd18b">-</b><span>门禁已启用</span></div>
  <div class="stat"><b id="s-upd">-</b><span>距下次刷新</span></div>
</div>
<div class="bar"><i id="bar"></i></div>

<h2>设备</h2>
<div class="grid" id="devs"><div class="muted">加载中…</div></div>

<h2>设备 Token（复制到 App 的「集控 Token」输入框）</h2>
<div class="token" id="token">__TOKEN__</div>
<div class="muted" style="margin-top:6px">
  面板账号 <code>__USER__</code> 的口令不在此显示 ·
  在服务器执行 <code>python3 control-server.py --show</code> 查看或更换
</div>

<h2>下发配置</h2>
<textarea id="cfg"></textarea>
<div style="margin-top:8px;display:flex;gap:8px;flex-wrap:wrap;align-items:center">
  <input id="dev" placeholder="设备ID（留空 = 设为默认配置）" size="34">
  <button onclick="saveCfg()">下发</button>
  <span id="msg" class="muted"></span>
</div>

<script>
const REFRESH = 5000;
let left = REFRESH / 1000;
let lastDefault = null;

function ago(t) {
  if (!t) return "-";
  const s = Math.max(0, Math.floor(Date.now()/1000 - t));
  if (s < 60) return s + "s 前";
  if (s < 3600) return Math.floor(s/60) + "m 前";
  if (s < 86400) return Math.floor(s/3600) + "h 前";
  return Math.floor(s/86400) + "d 前";
}

async function refresh() {
  let st;
  try { st = await (await fetch('/api/devices', {cache:'no-store'})).json(); }
  catch (e) { document.getElementById('devs').innerHTML = '<div class="muted">连接失败</div>'; return; }

  const devs = st.devices || {};
  const ids = Object.keys(devs).sort((a,b) => (devs[b].lastSeen||0) - (devs[a].lastSeen||0));
  const now = Date.now()/1000;
  let online = 0, armed = 0;

  const html = ids.map(id => {
    const d = devs[id], r = d.lastReport || {};
    const age = now - (d.lastSeen || now);
    if (age < 180) online++;
    if (r.armed) armed++;
    const cls = age < 180 ? 'on' : (age < 900 ? 'warm' : 'off');
    const dot = age < 180 ? '🟢' : (age < 900 ? '🟡' : '🔴');
    const tail = (r.logTail || []).map(x => x.replace(/[<>&]/g, c => ({'<':'&lt;','>':'&gt;','&':'&amp;'}[c]))).join('\n');
    return `<div class="card ${cls}">
      <div class="did">${id}</div>
      <div class="age">${dot} ${ago(d.lastSeen)}</div>
      <div class="kv">版本 <code>${r.versionName || '-'}</code> · 门禁 ${r.armed ? '✅ 已启用' : '⛔ 已禁用'}</div>
      <div class="kv">前台 <code>${r.foreground || '-'}</code></div>
      <div class="kv">目标 <code>${r.targetPkg || '-'}</code></div>
      <pre>${tail || '(无日志)'}</pre>
    </div>`;
  }).join('');

  document.getElementById('devs').innerHTML =
    html || '<div class="muted">还没有设备上报。在 App 里填服务器地址 + Token，点「上报一次」。</div>';
  document.getElementById('s-total').textContent = ids.length;
  document.getElementById('s-online').textContent = online;
  document.getElementById('s-armed').textContent = armed;

  const cur = JSON.stringify(st.defaultConfig || {});
  if (lastDefault === null) { document.getElementById('cfg').value = JSON.stringify(st.defaultConfig||{}, null, 2); lastDefault = cur; }
  else if (cur !== lastDefault && !document.getElementById('cfg').matches(':focus')) {
    document.getElementById('cfg').value = JSON.stringify(st.defaultConfig||{}, null, 2); lastDefault = cur;
  }
}

async function saveCfg() {
  const el = document.getElementById('cfg');
  let cfg;
  try { cfg = JSON.parse(el.value); } catch (e) { document.getElementById('msg').textContent = 'JSON 格式错误: ' + e; return; }
  cfg.deviceId = document.getElementById('dev').value.trim();
  const r = await fetch('/api/config', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(cfg)});
  const j = await r.json();
  document.getElementById('msg').textContent = j.ok ? ('已下发 revision=' + j.config.revision) : ('失败: ' + j.error);
  if (j.ok) { lastDefault = null; setTimeout(refresh, 500); }
}

// 倒计时 + 自动刷新
setInterval(() => {
  left -= 0.25;
  if (left <= 0) { left = REFRESH/1000; refresh(); }
  document.getElementById('s-upd').textContent = Math.max(0, left).toFixed(1) + 's';
  document.getElementById('bar').style.width = (100 * (1 - left/(REFRESH/1000))) + '%';
}, 250);

refresh();
</script>
</body></html>"""


DEFAULT_CONFIG = {
    "revision": "r1",
    "targetPkg": "com.nexon.mod",
    "pressMs": 90,
    "skillPoints": [],
    "notes": "初始配置",
}


# ---------------------------------------------------------------- 存储

def load_store():
    if not os.path.exists(STORE_PATH):
        return {"devices": {}, "defaultConfig": dict(DEFAULT_CONFIG)}
    try:
        with open(STORE_PATH, encoding="utf-8") as f:
            d = json.load(f)
        d.setdefault("devices", {})
        d.setdefault("defaultConfig", dict(DEFAULT_CONFIG))
        return d
    except Exception as e:
        print(f"[warn] 存储损坏，重建: {e}")
        return {"devices": {}, "defaultConfig": dict(DEFAULT_CONFIG)}


def save_store(d):
    tmp = STORE_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False, indent=2)
    os.replace(tmp, STORE_PATH)


# ---------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    server_version = "AltairControl/1.0"

    # ---------- 鉴权 ----------

    def _auth(self):
        with LOCK:
            return dict(ensure_auth(load_store()))

    def _panel_ok(self):
        """面板：HTTP Basic Auth。浏览器会原生弹出登录框。"""
        hdr = self.headers.get("Authorization", "") or ""
        if not hdr.startswith("Basic "):
            return False
        try:
            raw = base64.b64decode(hdr[6:]).decode("utf-8")
            u, p = raw.split(":", 1)
        except Exception:
            return False
        a = self._auth()
        return (hmac.compare_digest(u, a["panelUser"])
                and hmac.compare_digest(p, a["panelPass"]))

    def _device_ok(self):
        """设备 API：随机 Token，走 X-Altair-Token 头（也允许 ?token= 便于调试）。"""
        a = self._auth()
        tok = self.headers.get("X-Altair-Token", "") or ""
        if not tok:
            q = parse_qs(urlparse(self.path).query)
            tok = (q.get("token") or [""])[0]
        if not tok:
            return False
        return hmac.compare_digest(tok, a["deviceToken"])

    # 只给面板用的路径（走 Basic Auth）。其余 /api/* 都是设备接口（走 Token）。
    PANEL_PATHS = {"/", "/index.html", "/api/devices"}

    def _auth_route(self):
        """按路径分流鉴权。通过返回 True；被拒绝返回 False（响应已发出）。"""
        path = urlparse(self.path).path
        if path == "/healthz":
            return True                              # 探活免鉴权
        if path in self.PANEL_PATHS:
            if self._panel_ok():
                return True
            self._deny_panel()
            return False
        if path.startswith("/api/"):
            if self._device_ok():
                return True
            self._deny_device()
            return False
        # 其它路径（静态资源等）一律按面板处理
        if self._panel_ok():
            return True
        self._deny_panel()
        return False

    def _deny_panel(self):
        # 认证失败退避，拖慢暴力破解
        time.sleep(1.0)
        body = b"\n401 Unauthorized\n"
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="Altair Control"')
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        print(f"[auth] 面板认证失败 来自 {self.client_address[0]}")

    def _deny_device(self):
        time.sleep(0.5)
        self._json({"ok": False, "error": "invalid or missing device token"}, 401)
        print(f"[auth] 设备 token 无效 来自 {self.client_address[0]}")

    # ---------- 工具 ----------
    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _html(self, text, code=200):
        body = text.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self):
        n = int(self.headers.get("Content-Length", "0") or 0)
        if n <= 0:
            return {}
        raw = self.rfile.read(n)
        return json.loads(raw.decode("utf-8"))

    # ---------- GET ----------
    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)

        # 健康检查不需要鉴权（便于探活/排障）
        if u.path == "/healthz":
            return self._json({"ok": True, "ts": int(time.time())})

        if not self._auth_route():
            return

        if u.path == "/api/config":
            dev = (q.get("deviceId") or [""])[0]
            with LOCK:
                st = load_store()
                cfg = None
                if dev and dev in st["devices"]:
                    cfg = st["devices"][dev].get("config")
                if not cfg:
                    cfg = st["defaultConfig"]
            print(f"[config] 下发 -> device={dev or '(未指定)'} revision={cfg.get('revision')}")
            return self._json(cfg)

        if u.path in ("/", "/index.html"):
            return self._html(self._panel())

        if u.path == "/api/devices":
            with LOCK:
                st = load_store()
            return self._json(st)

        return self._json({"error": "not found"}, 404)

    # ---------- POST ----------
    def do_POST(self):
        u = urlparse(self.path)

        if not self._auth_route():
            return

        if u.path == "/api/report":
            try:
                data = self._read_json()
            except Exception as e:
                return self._json({"ok": False, "error": f"bad json: {e}"}, 400)
            dev = str(data.get("deviceId") or "unknown")
            with LOCK:
                st = load_store()
                d = st["devices"].setdefault(dev, {})
                d["lastReport"] = data
                d["lastSeen"] = time.time()
                d.setdefault("firstSeen", time.time())
                save_store(st)
            armed = "已启用" if data.get("armed") else "已禁用"
            print(f"[report] {dev}  v{data.get('versionName')}  "
                  f"前台={data.get('foreground')}  动作{armed}  [{time.strftime('%H:%M:%S')}]")
            return self._json({"ok": True, "ts": int(time.time())})

        if u.path == "/api/config":
            # 面板提交：设置某台设备（或默认）的配置
            try:
                data = self._read_json()
            except Exception as e:
                return self._json({"ok": False, "error": str(e)}, 400)
            dev = str(data.pop("deviceId", "") or "")
            data["revision"] = str(data.get("revision") or f"r{int(time.time())}")
            if isinstance(data.get("skillPoints"), str):
                try:
                    data["skillPoints"] = json.loads(data["skillPoints"])
                except Exception:
                    data["skillPoints"] = []
            with LOCK:
                st = load_store()
                if dev:
                    st["devices"].setdefault(dev, {})["config"] = data
                    target = f"设备 {dev}"
                else:
                    st["defaultConfig"] = data
                    target = "默认配置"
                save_store(st)
            print(f"[config] 更新 {target} -> revision={data['revision']}")
            return self._json({"ok": True, "config": data})

        return self._json({"error": "not found"}, 404)

    # ---------- 面板 ----------

    def _panel(self):
        with LOCK:
            st = load_store()
            ensure_auth(st)
        return (PANEL_HTML
                .replace("__TOKEN__", html.escape(st["auth"]["deviceToken"]))
                .replace("__USER__", html.escape(st["auth"]["panelUser"])))

    def log_message(self, fmt, *args):
        pass  # 只打印我们自己关心的行


def main():
    # 关闭输出缓冲，否则后台运行时日志要等很久才刷出来
    try:
        sys.stdout.reconfigure(line_buffering=True)
        sys.stderr.reconfigure(line_buffering=True)
    except Exception:
        pass

    ap = argparse.ArgumentParser(description="阿尔泰挂机 · 集控服务器")
    ap.add_argument("--port", type=int, default=8899)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--show", action="store_true", help="打印当前凭据后退出")
    ap.add_argument("--set-password", metavar="新密码", help="修改面板密码后退出")
    ap.add_argument("--set-token", metavar="新Token", help="修改设备 Token 后退出")
    a = ap.parse_args()

    with LOCK:
        st = load_store()
        auth = ensure_auth(st)
        save_store(st)

    if a.show:
        print("=" * 66)
        print("  当前凭据")
        print("=" * 66)
        print(f"  面板地址    http://<本机IP>:{a.port}/")
        print(f"  面板账号    {auth['panelUser']}")
        print(f"  面板密码    {auth['panelPass']}")
        print(f"  设备 Token  {auth['deviceToken']}")
        print("=" * 66)
        return

    if a.set_password or a.set_token:
        with LOCK:
            st = load_store()
            au = ensure_auth(st)
            if a.set_password:
                au["panelPass"] = a.set_password
                print(f"面板密码已更新为: {a.set_password}")
            if a.set_token:
                au["deviceToken"] = a.set_token
                print(f"设备 Token 已更新为: {a.set_token}")
            save_store(st)
        print("（改完记得重启服务： systemctl restart altair-control）")
        return

    print("=" * 66)
    print("  阿尔泰挂机 · 集控服务器")
    print("=" * 66)
    print(f"  面板        http://<本机IP>:{a.port}/")
    print(f"  面板账号    {auth['panelUser']}")
    print(f"  面板密码    {auth['panelPass']}")
    print(f"  设备 Token  {auth['deviceToken']}")
    print(f"  存储        {STORE_PATH}")
    print(f"  已有设备    {len(st['devices'])} 台")
    print("-" * 66)
    print("  再次查看凭据: python3 control-server.py --show")
    print("  改登录口令  : python3 control-server.py --set-password 新口令")
    print("  改设备Token : python3 control-server.py --set-token 新Token")
    print("=" * 66)
    print()
    ThreadingHTTPServer((a.host, a.port), Handler).serve_forever()


if __name__ == "__main__":
    main()

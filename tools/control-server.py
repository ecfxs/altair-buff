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
import html
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

STORE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "control-store.json")
LOCK = threading.Lock()

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
        now = time.time()

        rows = []
        for dev, d in sorted(st["devices"].items(),
                             key=lambda kv: kv[1].get("lastSeen", 0), reverse=True):
            r = d.get("lastReport") or {}
            age = int(now - d.get("lastSeen", now))
            online = "🟢" if age < 180 else ("🟡" if age < 900 else "🔴")
            tail = r.get("logTail") or []
            tail_html = "<br>".join(html.escape(str(x)) for x in tail[-4:])
            rows.append(f"""
            <tr>
              <td><code>{html.escape(dev)}</code></td>
              <td>{online} {age}s 前</td>
              <td>{html.escape(str(r.get('versionName','')))}</td>
              <td>{html.escape(str(r.get('foreground','')))}</td>
              <td>{'✅' if r.get('armed') else '⛔'}</td>
              <td class="tail">{tail_html}</td>
            </tr>""")
        if not rows:
            rows.append('<tr><td colspan="6" class="muted">还没有设备上报。'
                        '在 App 里填本机地址并点「上报一次」。</td></tr>')

        cfg = json.dumps(st["defaultConfig"], ensure_ascii=False, indent=2)

        return f"""<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>阿尔泰挂机 · 集控面板</title>
<style>
 body{{background:#0f1216;color:#d8dde3;font:13px/1.6 -apple-system,"PingFang SC",sans-serif;margin:0;padding:22px}}
 h1{{font-size:17px;margin:0 0 4px}} h2{{font-size:13px;color:#8b97a6;margin:22px 0 8px;text-transform:uppercase;letter-spacing:.05em}}
 table{{border-collapse:collapse;width:100%}} th,td{{border:1px solid #2c333d;padding:6px 9px;text-align:left;vertical-align:top}}
 th{{background:#1b1f26;color:#8b97a6;font-weight:600}} code{{color:#7fd18b}}
 .muted{{color:#6c7787}} .tail{{font-family:ui-monospace,monospace;font-size:11px;color:#9aa7b6;max-width:420px}}
 textarea{{width:100%;height:170px;background:#0b0e12;color:#c9d4e0;border:1px solid #3a4450;border-radius:5px;
   padding:9px;font-family:ui-monospace,monospace;font-size:12px}}
 button{{background:#2563eb;color:#fff;border:0;border-radius:5px;padding:7px 16px;cursor:pointer;font-size:12px}}
 input{{background:#0b0e12;color:#c9d4e0;border:1px solid #3a4450;border-radius:5px;padding:6px;font-size:12px}}
</style></head><body>
<h1>阿尔泰挂机 · 集控面板</h1>
<div class="muted">设备每 {60} 秒上报一次；<span class="muted">🟢 &lt;3分钟 · 🟡 &lt;15分钟 · 🔴 更久</span></div>

<h2>设备（{len(st["devices"])} 台）</h2>
<table>
 <tr><th>设备ID</th><th>最后在线</th><th>版本</th><th>前台应用</th><th>门禁</th><th>最近日志</th></tr>
 {''.join(rows)}
</table>

<h2>默认配置（未单独配置的设备都用它）</h2>
<textarea id="cfg">{html.escape(cfg)}</textarea>
<div style="margin-top:8px">
  <input id="dev" placeholder="设备ID（留空=设为默认配置）" size="34">
  <button onclick="save()">下发配置</button>
  <span class="muted" id="msg"></span>
</div>

<script>
async function save() {{
  const el = document.getElementById('cfg');
  let cfg;
  try {{ cfg = JSON.parse(el.value); }} catch (e) {{ document.getElementById('msg').textContent = 'JSON 格式错误: ' + e; return; }}
  cfg.deviceId = document.getElementById('dev').value.trim();
  const r = await fetch('/api/config', {{method:'POST', headers:{{'Content-Type':'application/json'}}, body: JSON.stringify(cfg)}});
  const j = await r.json();
  document.getElementById('msg').textContent = j.ok ? ('已下发 revision=' + j.config.revision) : ('失败: ' + j.error);
  if (j.ok) setTimeout(()=>location.reload(), 800);
}}
</script>
</body></html>"""

    def log_message(self, fmt, *args):
        pass  # 只打印我们自己关心的行


def main():
    # 关闭输出缓冲，否则后台运行时日志要等很久才刷出来
    try:
        sys.stdout.reconfigure(line_buffering=True)
        sys.stderr.reconfigure(line_buffering=True)
    except Exception:
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8899)
    ap.add_argument("--host", default="0.0.0.0")
    a = ap.parse_args()

    st = load_store()
    print("=" * 66)
    print("  阿尔泰挂机 · 集控服务器")
    print("=" * 66)
    print(f"  面板      http://127.0.0.1:{a.port}/")
    print(f"  设备填    http://<本机公网IP>:{a.port}")
    print(f"  存储      {STORE_PATH}")
    print(f"  已有设备  {len(st['devices'])} 台")
    print("=" * 66)
    print("提示：云手机在机房，需把本服务放到它有网可达的地址（公网 VPS / 内网穿透）")
    print()
    ThreadingHTTPServer((a.host, a.port), Handler).serve_forever()


if __name__ == "__main__":
    main()

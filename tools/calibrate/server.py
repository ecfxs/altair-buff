#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
标定工具服务端
==============

一个极小的 HTTP 服务：把工作区静态文件serve出去，并提供一个保存接口，
让浏览器里的标定页面 index.html 能把标定结果直接写回项目。

用法
----
    python3 tools/calibrate/server.py            # 默认 127.0.0.1:8787
    python3 tools/calibrate/server.py --port 9000

然后浏览器打开 http://127.0.0.1:8787/tools/calibrate/index.html
"""

import argparse
import json
import os
import sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))          # 工作区根
SHOTS = os.path.join(ROOT, "shots")
OUT = os.path.join(SHOTS, "_analysis", "calibration.json")


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=ROOT, **kw)

    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/api/images"):
            exts = (".png", ".jpg", ".jpeg", ".webp")
            files = []
            if os.path.isdir(SHOTS):
                for n in sorted(os.listdir(SHOTS)):
                    if n.lower().endswith(exts) and os.path.isfile(os.path.join(SHOTS, n)):
                        files.append(n)
            return self._json({"images": files, "out": os.path.relpath(OUT, ROOT)})
        return super().do_GET()

    def do_POST(self):
        if self.path.startswith("/api/save"):
            try:
                n = int(self.headers.get("Content-Length", "0"))
                raw = self.rfile.read(n)
                data = json.loads(raw.decode("utf-8"))      # 校验是合法 JSON
                os.makedirs(os.path.dirname(OUT), exist_ok=True)
                with open(OUT, "w", encoding="utf-8") as f:
                    json.dump(data, f, ensure_ascii=False, indent=2)
                cnt = len(data.get("items", []))
                print(f"[saved] {OUT}  ({cnt} 个标定项)")
                return self._json({"ok": True, "path": OUT, "items": cnt})
            except Exception as e:                            # noqa: BLE001
                print(f"[error] 保存失败: {e}")
                return self._json({"ok": False, "error": str(e)}, 400)
        return self._json({"ok": False, "error": "unknown endpoint"}, 404)

    def log_message(self, fmt, *args):
        pass  # 保持安静，只打印保存事件


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8787)
    ap.add_argument("--host", default="127.0.0.1")
    a = ap.parse_args()
    srv = ThreadingHTTPServer((a.host, a.port), Handler)
    url = f"http://{a.host}:{a.port}/tools/calibrate/index.html"
    print("=" * 66)
    print("  标定工具已启动")
    print(f"  打开: {url}")
    print(f"  截图目录: {SHOTS}")
    print(f"  保存到:   {OUT}")
    print("  Ctrl+C 退出")
    print("=" * 66)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止")
        return 0


if __name__ == "__main__":
    sys.exit(main())

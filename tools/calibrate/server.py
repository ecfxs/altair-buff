#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
标定工具服务端
==============

一个极小的 HTTP 服务：只把**标定页面本身**和**截图目录**开放给浏览器，
并提供一个保存接口，让页面 index.html 能把标定结果写回项目。

用法
----
    python3 tools/calibrate/server.py            # 默认 127.0.0.1:8787
    python3 tools/calibrate/server.py --port 9000

然后浏览器打开 http://127.0.0.1:8787/tools/calibrate/index.html

## 为什么不能直接把工作区 serve 出去（历史缺陷）
改造前这里用的是 `SimpleHTTPRequestHandler(directory=工作区根)`，于是"打开标定页面"
等于**把整个项目目录挂在 HTTP 上**：签名密钥、签名配置、构建产物、日志、其他工具的源码
全都可以被直接下载。默认只监听 127.0.0.1 确实降低了一部分风险，但 `--host` 是开放的
参数，一旦有人用它监听局域网（`--host 0.0.0.0`）就等于把这些文件公开发布。

现在改成**白名单**：只有下面 [ROUTE_ALLOW] 里列出的前缀可以被读取，其余一律 404。
保存接口也不再接受客户端指定的路径 —— 写到哪里由服务端自己决定。
"""

import argparse
import ipaddress
import json
import os
import sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))          # 工作区根
SHOTS = os.path.join(ROOT, "shots")
OUT = os.path.join(SHOTS, "_analysis", "calibration.json")

FILE_URL = "/tools/calibrate/index.html"

#: 只读白名单：URL 前缀 → (本地根目录, 是否限制为图片)。
#: 根路径与 /index.html 在 [Handler.resolve_read] 里被改写成 FILE_URL，所以不在这里重复登记。
ROUTE_ALLOW = (
    ("/tools/calibrate/", HERE, False),
    ("/shots/", SHOTS, True),
)

IMAGE_EXTS = (".png", ".jpg", ".jpeg", ".webp")


def _is_image(name):
    return name.lower().endswith(IMAGE_EXTS)


def list_shots():
    """截图目录里的图片文件名（按名字排序）。"""
    if not os.path.isdir(SHOTS):
        return []
    return sorted(
        n for n in os.listdir(SHOTS)
        if _is_image(n) and os.path.isfile(os.path.join(SHOTS, n))
    )


class Handler(SimpleHTTPRequestHandler):
    """白名单静态文件 + 固定落盘的保存接口。"""

    server_version = "altair-calibrate"

    def __init__(self, *a, **kw):
        # 不再把工作区交给父类：所有路径都由 [resolve_read] 自己裁决。
        super().__init__(*a, directory=HERE, **kw)

    # ------------------------------------------------------------ 工具

    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _not_found(self, why=""):
        self.send_error(404, "Not Found" if not why else why)

    def resolve_read(self):
        """把请求路径翻译成允许读取的本地文件；不允许则返回 None。

        三重约束，缺一不可：
          1. 前缀必须在白名单里（`/etc/passwd` 这种连门都进不来）；
          2. `realpath` 之后必须仍在白名单根目录之内（挡掉 `../` 与符号链接逃逸）；
          3. 图片路由只放行图片扩展名（截图目录里可能混着分析产物）。
        """
        path = urlparse(self.path).path
        if path in ("/", "/index.html"):
            path = FILE_URL
        if path.startswith("/tools/calibrate/") and path != FILE_URL:
            return None
        for prefix, root, images_only in ROUTE_ALLOW:
            if not path.startswith(prefix):
                continue
            rel = unquote(path[len(prefix):]).lstrip("/")
            if not rel or rel.endswith("/"):
                return None
            if images_only and not _is_image(rel):
                return None
            target = os.path.realpath(os.path.join(root, rel))
            real_root = os.path.realpath(root)
            if target != real_root and not target.startswith(real_root + os.sep):
                return None
            return target if os.path.isfile(target) else None
        return None

    # ------------------------------------------------------------ 路由

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/api/images":
            return self._json({
                "images": list_shots(),
                "out": os.path.relpath(OUT, ROOT),
            })
        if path.startswith("/api/"):
            return self._json({"ok": False, "error": "unknown endpoint"}, 404)

        target = self.resolve_read()
        if target is None:
            # 不回显被拒的路径细节：那等于告诉探测者哪些路径存在。
            print(f"[deny] {self.command} {path}")
            return self._not_found()
        return self.serve_file(target)

    def do_HEAD(self):
        target = self.resolve_read()
        if target is None:
            return self._not_found()
        self.serve_file(target, head_only=True)

    def do_POST(self):
        path = urlparse(self.path).path
        if path != "/api/save":
            return self._json({"ok": False, "error": "unknown endpoint"}, 404)
        if self.headers.get_content_type() != "application/json":
            return self._json({"ok": False, "error": "需要 application/json"}, 415)
        origin = self.headers.get("Origin")
        if origin and origin != "http://" + self.headers.get("Host", ""):
            return self._json({"ok": False, "error": "拒绝跨站保存请求"}, 403)
        try:
            n = int(self.headers.get("Content-Length", "0"))
            if n <= 0 or n > 8 * 1024 * 1024:
                raise ValueError(f"请求体大小不合理：{n} 字节")
            raw = self.rfile.read(n)
            data = json.loads(raw.decode("utf-8"))      # 校验是合法 JSON
            if not isinstance(data, dict):
                raise ValueError("标定结果必须是 JSON 对象")
            items = data.get("items", [])
            if not isinstance(items, list):
                raise ValueError("items 必须是数组")
            # ★ 落盘路径由服务端固定，客户端只能给内容、不能给路径。
            os.makedirs(os.path.dirname(OUT), exist_ok=True)
            with open(OUT, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
            print(f"[saved] {OUT}  ({len(items)} 个标定项)")
            return self._json({"ok": True, "path": os.path.relpath(OUT, ROOT), "items": len(items)})
        except Exception as e:                            # noqa: BLE001
            print(f"[error] 保存失败: {e}")
            return self._json({"ok": False, "error": str(e)}, 400)

    def serve_file(self, target, head_only=False):
        try:
            with open(target, "rb") as f:
                body = f.read()
        except OSError as e:
            return self._not_found(str(e))
        self.send_response(200)
        self.send_header("Content-Type", self.guess_type(target))
        self.send_header("Content-Length", str(len(body)))
        # 标定要反复对同一张截图，禁掉缓存省得看到上一版的图。
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if not head_only:
            self.wfile.write(body)

    def log_message(self, fmt, *args):
        pass  # 保持安静；拒绝与保存各自有明确的打印


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8787)
    ap.add_argument("--host", default="127.0.0.1",
                    help="监听地址。默认仅本机；服务只开放页面与截图目录，但仍不建议监听外网。")
    a = ap.parse_args()

    # ★ 页面本身给了明确的非本机警告：白名单已经收窄了暴露面，但"能上网就能标定/写文件"
    #   这件事仍然不该在用户不知情的情况下发生。
    try:
        loopback = ipaddress.ip_address(a.host).is_loopback
    except ValueError:
        loopback = False          # 主机名（localhost 等）无法判定，按非回环处理
    if not loopback:
        print(f"⚠ --host {a.host} 不是本机回环地址：局域网内任何人都能打开这个页面并覆盖标定文件。")
        print("  仅在你清楚这一点时继续；标定完成后请立刻 Ctrl+C 关掉。")

    srv = ThreadingHTTPServer((a.host, a.port), Handler)
    url = f"http://{a.host}:{a.port}{FILE_URL}"
    print("=" * 66)
    print("  标定工具已启动")
    print(f"  打开: {url}")
    print(f"  只开放: {HERE}（页面） + {SHOTS}（截图）")
    print(f"  保存到: {OUT}")
    print("  Ctrl+C 退出")
    print("=" * 66)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止")
        return 0


if __name__ == "__main__":
    sys.exit(main())

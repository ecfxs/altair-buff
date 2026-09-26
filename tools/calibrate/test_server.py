"""不启动监听端口，直接验证生产请求处理器的读取和保存边界。"""
import importlib.util
import io
import json
import tempfile
import unittest
from email.message import Message
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("calibrate_server", Path(__file__).with_name("server.py"))
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)


class Request(server.Handler):
    def __init__(self, path, body=b"", content_type="application/json", origin=None):
        self.path = path
        self.command = "GET"
        self.headers = Message()
        self.headers["Content-Length"] = str(len(body))
        self.headers["Content-Type"] = content_type
        self.headers["Host"] = "127.0.0.1:8787"
        if origin:
            self.headers["Origin"] = origin
        self.rfile = io.BytesIO(body)
        self.wfile = io.BytesIO()

    def send_response(self, code, message=None): self.status = code
    def send_header(self, key, value): pass
    def end_headers(self): pass
    def send_error(self, code, message=None): self.status = code


class ServerBoundaryTest(unittest.TestCase):
    def test_read_allowlist(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            page = root / "page"
            shots = root / "shots"
            page.mkdir()
            shots.mkdir()
            (page / "index.html").write_text("page")
            (page / "server.py").write_text("private source")
            (root / "signing.properties").write_text("test-only secret")
            (shots / "game.png").write_bytes(b"image")
            (shots / "escape.png").symlink_to(root / "signing.properties")
            routes = (("/tools/calibrate/", str(page), False), ("/shots/", str(shots), True))
            with patch.object(server, "ROUTE_ALLOW", routes):
                for path in ("/", "/tools/calibrate/index.html", "/shots/game.png"):
                    request = Request(path)
                    request.do_GET()
                    self.assertEqual(request.status, 200, path)
                for path in ("/.git/config", "/keystore/probe.jks", "/signing.properties",
                             "/tools/calibrate/server.py", "/shots/escape.png",
                             "/shots/%2e%2e/signing.properties", "/shots/"):
                    for method in ("do_GET", "do_HEAD"):
                        request = Request(path)
                        getattr(request, method)()
                        self.assertEqual(request.status, 404, path)
                        self.assertNotIn(b"test-only secret", request.wfile.getvalue())

    def test_save_boundaries(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "calibration.json"
            payload = json.dumps({"items": []}).encode()
            with patch.object(server, "OUT", str(output)):
                request = Request("/api/save", payload)
                request.do_POST()
                self.assertEqual(request.status, 200)
                self.assertEqual(json.loads(output.read_text()), {"items": []})
                for path, body, content_type, origin, code in (
                    ("/api/save-extra", payload, "application/json", None, 404),
                    ("/api/save", b"[]", "application/json", None, 400),
                    ("/api/save", payload, "text/plain", None, 415),
                    ("/api/save", payload, "application/json", "http://untrusted.example", 403),
                ):
                    request = Request(path, body, content_type, origin)
                    request.do_POST()
                    self.assertEqual(request.status, code)
                    self.assertEqual(json.loads(output.read_text()), {"items": []})


if __name__ == "__main__":
    unittest.main()

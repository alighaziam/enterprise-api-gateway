import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SERVICE = os.environ.get("MOCK_SERVICE", "orders")
PREFIX = "/" + SERVICE


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == PREFIX or path.startswith(PREFIX + "/"):
            body = json.dumps({"service": SERVICE, "path": path, "status": "ok"}).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(404)
        self.end_headers()

    def log_message(self, fmt, *args):
        return


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()

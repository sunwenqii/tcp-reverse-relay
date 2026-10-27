"""A small local web page for checking a TCP port mapping."""

import argparse
from datetime import datetime
from html import escape
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


HOST = "127.0.0.1"


class DemoHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        time = datetime.now().astimezone().strftime("%Y-%m-%d %H:%M:%S %Z")
        page = f"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>TCP 转发已连通</title>
  <style>
    body {{ margin: 0; min-height: 100vh; display: grid; place-items: center;
            background: #f3f7fa; color: #183044; font: 16px/1.6 system-ui, sans-serif; }}
    main {{ width: min(480px, calc(100% - 48px)); padding: 40px;
            background: white; border-radius: 18px; box-shadow: 0 12px 40px #18304414; }}
    h1 {{ margin: 0 0 10px; font-size: 28px; }}
    p {{ color: #526777; }}
    code {{ color: #087e76; }}
  </style>
</head>
<body><main>
  <h1>✓ TCP 转发已连通</h1>
  <p>你已经访问到客户端本机 <code>{HOST}:{self.server.server_port}</code> 上的 Python 服务。</p>
  <p>访问路径：<code>{escape(self.path)}</code><br>当前时间：{time}</p>
</main></body>
</html>"""
        body = page.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="在本机运行 TCP 转发演示页面")
    parser.add_argument("--port", type=int, default=8080, help="监听端口，默认 8080")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("端口必须在 1 到 65535 之间")

    try:
        server = ThreadingHTTPServer((HOST, args.port), DemoHandler)
    except OSError as error:
        raise SystemExit(f"无法监听 {HOST}:{args.port}：{error}")

    with server:
        print(f"服务已启动：http://{HOST}:{args.port}/（按 Ctrl+C 停止）", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            print("服务已停止")

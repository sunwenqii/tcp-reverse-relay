"""TCP echo benchmark for an already running TCP Reverse Relay mapping."""

import argparse
import concurrent.futures
import socket
import socketserver
import statistics
import threading
import time


class EchoHandler(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            while True:
                data = self.request.recv(65536)
                if not data:
                    return
                self.request.sendall(data)
        except (ConnectionResetError, BrokenPipeError):
            return


class EchoServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True
    request_queue_size = 128


def percentile(values, percent):
    ordered = sorted(values)
    return ordered[max(0, (len(ordered) * percent + 99) // 100 - 1)]


def worker(host, port, requests, payload, timeout):
    latencies = []
    transferred = 0
    try:
        with socket.create_connection((host, port), timeout=timeout) as connection:
            connection.settimeout(timeout)
            for _ in range(requests):
                started = time.perf_counter()
                connection.sendall(payload)
                received = bytearray()
                while len(received) < len(payload):
                    chunk = connection.recv(len(payload) - len(received))
                    if not chunk:
                        raise ConnectionError("connection closed before echo completed")
                    received.extend(chunk)
                if received != payload:
                    raise ValueError("echo payload differs from sent bytes")
                latencies.append((time.perf_counter() - started) * 1000)
                transferred += len(payload) * 2
        return latencies, transferred, None
    except (OSError, ValueError) as error:
        return latencies, transferred, str(error)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1", help="server mapping address")
    parser.add_argument("--port", type=int, required=True, help="server mapping port")
    parser.add_argument("--connections", type=int, default=10)
    parser.add_argument("--requests", type=int, default=20, help="echoes per connection")
    parser.add_argument("--payload-bytes", type=int, default=1024)
    parser.add_argument("--timeout", type=float, default=10)
    parser.add_argument("--echo-port", type=int, help="start local echo target on this port")
    args = parser.parse_args()
    if min(args.connections, args.requests, args.payload_bytes) < 1 or args.timeout <= 0:
        parser.error("connections, requests, payload-bytes and timeout must be positive")

    echo = None
    if args.echo_port is not None:
        echo = EchoServer(("127.0.0.1", args.echo_port), EchoHandler)
        threading.Thread(target=echo.serve_forever, daemon=True).start()

    payload = bytes(i % 251 for i in range(args.payload_bytes))
    try:
        started = time.perf_counter()
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.connections) as pool:
            results = list(pool.map(
                lambda _: worker(args.host, args.port, args.requests, payload, args.timeout),
                range(args.connections),
            ))
        elapsed = time.perf_counter() - started
    finally:
        if echo is not None:
            echo.shutdown()
            echo.server_close()

    latencies = [latency for times, _, _ in results for latency in times]
    failures = [error for _, _, error in results if error is not None]
    total_bytes = sum(size for _, size, _ in results)
    print(f"connections={args.connections} requests={args.requests} completed={len(latencies)} failures={len(failures)}")
    print(f"elapsed={elapsed:.2f}s ops/s={len(latencies) / elapsed:.1f} throughput={total_bytes / elapsed / 1048576:.2f} MiB/s")
    if latencies:
        print(f"latency_ms p50={statistics.median(latencies):.2f} p95={percentile(latencies, 95):.2f} p99={percentile(latencies, 99):.2f}")
    if failures:
        print(f"first_error={failures[0]}")
        raise SystemExit(1)


if __name__ == "__main__":
    main()

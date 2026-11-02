"""Loopback test proxy. Read the temporary key without echo; keep credentials off the device.

Only forwards synthetic acceptance requests to the supplied HTTPS chat-completions endpoint.
No credentials, request bodies, or response bodies are logged or persisted.
"""
import argparse
import getpass
import http.server
import json
import urllib.error
import urllib.parse
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", required=True)
    parser.add_argument("--port", type=int, default=19001)
    args = parser.parse_args()
    if urllib.parse.urlsplit(args.upstream).scheme != "https":
        parser.error("upstream must use HTTPS")
    key = getpass.getpass("Temporary test key (not echoed): ").strip()
    if not key:
        parser.error("empty test key")

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *unused):
            pass

        def do_POST(self):
            if self.path != "/v1/chat/completions":
                self.send_error(404)
                return
            data = self.rfile.read(int(self.headers.get("content-length", "0")))
            request = urllib.request.Request(args.upstream, data=data, headers={
                "Content-Type": "application/json", "Authorization": "Bearer " + key,
            })
            try:
                upstream = urllib.request.urlopen(request, timeout=180)
            except urllib.error.HTTPError as error:
                self.send_response(error.code)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"error": {"message": "Test upstream rejected request",
                    "code": error.code}}).encode())
                print(json.dumps({"status": error.code}), flush=True)
                return
            except Exception as error:
                self.send_error(502, "Test upstream unavailable")
                print(json.dumps({"error": type(error).__name__}), flush=True)
                return
            with upstream:
                self.send_response(upstream.status)
                self.send_header("Content-Type", upstream.headers.get("Content-Type", "application/json"))
                self.end_headers()
                try:
                    while True:
                        chunk = upstream.read1(8192)
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                        self.wfile.flush()
                    print(json.dumps({"status": upstream.status, "completed": True}), flush=True)
                except (BrokenPipeError, ConnectionResetError):
                    pass

    print(f"Test proxy ready on 127.0.0.1:{args.port}; key held only in memory", flush=True)
    http.server.ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()

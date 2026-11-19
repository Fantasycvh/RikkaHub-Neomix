"""Loopback-only media acceptance fixture. Forward its port with adb reverse."""

import argparse
import base64
import json
import re
import struct
import threading
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def png():
    def chunk(kind, value):
        return struct.pack(">I", len(value)) + kind + value + struct.pack(">I", zlib.crc32(kind + value))

    row = b"\0" + b"\x28\x78\xc0" * 64
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 64, 64, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(row * 64)) + chunk(b"IEND", b""))


def image_bytes(value):
    return value.startswith((b"\x89PNG", b"\xff\xd8\xff"))


class Fixture(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send_bytes(self, data, mime, status=200):
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def send_json(self, value):
        self.send_bytes(json.dumps(value).encode(), "application/json")

    def record(self, **event):
        with self.server.lock:
            self.server.trace.append(event)

    def do_GET(self):
        if self.path == "/trace":
            with self.server.lock:
                snapshot = self.server.trace.copy()
            self.send_json(snapshot)
        elif self.path == "/fixture.png":
            self.send_bytes(png(), "image/png")
        elif self.path == "/fixture.mp4":
            self.send_bytes(self.server.video, "video/mp4")
        else:
            self.send_error(404)

    def do_POST(self):
        payload = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if self.path == "/v1/images/edits":
            from email import policy
            from email.parser import BytesParser

            header = f"Content-Type: {self.headers['Content-Type']}\r\n\r\n".encode()
            parts = list(BytesParser(policy=policy.default).parsebytes(header + payload).iter_parts())
            fields = {part.get_param("name", header="content-disposition"): part for part in parts}
            reference = fields.get("image") or fields.get("image[]")
            self.record(path=self.path, prompt=fields["prompt"].get_content(), model=fields["model"].get_content(),
                        hasImage=bool(reference and image_bytes(reference.get_payload(decode=True))))
            self.send_json({"data": [{"b64_json": base64.b64encode(png()).decode()}]})
            return

        body = json.loads(payload or b"{}")
        if self.path == "/v1/images/generations":
            self.record(path=self.path, prompt=body["prompt"], model=body["model"],
                        count=body.get("n"), size=body.get("size"),
                        fixtureAuth=self.headers.get("Authorization") == "Bearer media-fixture-key")
            self.send_json({"data": [{"b64_json": base64.b64encode(png()).decode()}]})
        elif self.path == "/v1/video/submit":
            prompt = body["prompt"]
            image = body.get("image", "")
            header, _, content = image.partition(",")
            self.record(path=self.path, prompt=prompt, model=body["model"], size=body.get("image_size"),
                        hasImage=header in ("data:image/png;base64", "data:image/jpeg;base64")
                        and image_bytes(base64.b64decode(content)))
            if prompt.endswith("-unknown"):
                self.server.unknown_release.wait(60)
            try:
                self.send_json({"requestId": prompt})
            except (BrokenPipeError, ConnectionResetError):
                pass
        elif self.path == "/v1/video/status":
            identifier = body["requestId"]
            self.record(path=self.path, prompt=identifier)
            with self.server.lock:
                released = identifier in self.server.released
            waiting = identifier.endswith(("-resume", "-cancel")) and not released
            self.send_json({"status": "InProgress"} if waiting else {
                "status": "Succeed", "results": {
                    "videos": [{"url": f"http://127.0.0.1:{self.server.server_port}/fixture.mp4"}],
                },
            })
        elif self.path == "/control/release":
            with self.server.lock:
                self.server.released.add(body["requestId"])
            self.send_json({})
        elif self.path == "/v1/chat/completions":
            self.chat(body)
        else:
            self.send_error(404)

    def chat(self, body):
        messages = body["messages"]
        user = next(message for message in reversed(messages) if message["role"] == "user")
        prompt = re.search(r"media-e2e-chat-[a-z0-9-]+", str(user["content"]))[0]
        tools = [tool["function"]["name"] for tool in body.get("tools", [])]
        tool_results = sum(message["role"] == "tool" for message in messages)
        self.record(path=self.path, prompt=prompt, imageTool="generate_image" in tools, results=tool_results)
        if "generate_image" in tools and tool_results == 0:
            counts = [99, 1, 1] if "-scheduled-" in prompt else [1]
            delta = {"role": "assistant", "tool_calls": [
                {"index": index, "id": f"media-call-{index}", "type": "function", "function": {
                    "name": "generate_image", "arguments": json.dumps({"prompt": prompt, "num": count}),
                }} for index, count in enumerate(counts)
            ]}
            reason = "tool_calls"
        else:
            delta, reason = {"role": "assistant", "content": "MEDIA_FIXTURE_DONE"}, "stop"
        event = {"id": "media-fixture", "object": "chat.completion.chunk", "created": 1,
                 "model": body["model"]}
        chunks = [{"delta": delta, "finish_reason": None}, {"delta": {}, "finish_reason": reason}]
        stream = "".join("data: " + json.dumps({**event, "choices": [{"index": 0, **value}]}) + "\n\n"
                         for value in chunks) + "data: [DONE]\n\n"
        self.send_bytes(stream.encode(), "text/event-stream")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--video", type=Path, required=True, help="A small synthetic MP4 file")
    args = parser.parse_args()
    with ThreadingHTTPServer(("127.0.0.1", args.port), Fixture) as server:
        server.video = args.video.read_bytes()
        server.trace = []
        server.released = set()
        server.lock = threading.Lock()
        server.unknown_release = threading.Event()
        print(f"media fixture listening on {server.server_port}", flush=True)
        server.serve_forever()

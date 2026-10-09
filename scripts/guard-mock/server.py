"""思考退化护栏的 E2E 测试桩（本地 OpenAI 兼容流式服务）。

为什么需要它：`ThinkingDegenerationGuard` 的三条判据都依赖**真实退化**才会被触发，而模型侧退化
无法按需复现（见 docs/roadmap.md 2026-10-09 条）。这个桩用**按模型名注入的退化形态**把三态变成
可确定性复现的输入，从而在真机上验证「切断 + 提示 + 内容保留 + 不重复计费」整条链路。

模型名 → 形态：
  guard-cycle          推理增量按 5 字符周期（"好。执行。"）逐字重复，不会自己结束 → 判据③（周期）
  guard-repeat         正文增量连续 120 次完全相同的短句 → 判据③变体（相同 delta，周期 1）
  guard-thinking-only  只有推理、无正文无工具，随后正常收尾 → 判据①（跨调用计数）
  guard-normal         正常短回复 → **负对照**（不得被误杀）

用法（实测流程，2026-10-09）：
  1. 宿主机起服务：  python -X utf8 scripts/guard-mock/server.py      # 日志写同目录 server.log
  2. 把服务暴露给设备：adb reverse tcp:8888 tcp:8888
  3. app（任意版本，须为 debug 包以便共存）加一个「OpenAI 兼容」提供商：
       名称 guard-mock / API Key 随便 / **Base URL = http://127.0.0.1:8888/v1**（必须显式写 http://，
       只写 127.0.0.1:8888 会被 app 当 https 请求 → TLS 握手失败 "Unable to parse TLS packet header"）
       模型 ID：上面四个（可点「测试连接」拉取，本桩 /v1/models 会返回它们）
  4. 依次切模型各发一条消息，然后对三份证据：
       - app 侧：  adb logcat -d | grep -iE "thinking degeneration"
         （应看到 generateInternal: ... cutting this call / streamText: ... stopping this turn /
           ChatService: thinking degeneration stopped: cut=...）
       - 桩侧：    server.log 的 [cut]（客户端主动断开）与 [done]（未被切断）
       - 数据侧：  库里那条助手消息的长度——**内容被保留、没有回滚**，且长度等于切断点
         （实测：周期态 256 字符、相同 delta 态 180 字符 = 60×3、负对照 finish=stop）

只用标准库；除 adb reverse 外不需要任何依赖。
"""
import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = "127.0.0.1"
PORT = int(os.environ.get("GUARD_MOCK_PORT", "8888"))
LOG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "server.log")

CYCLE = "好。执行。"
REPEAT_TEXT = "继续。"
NORMAL_TEXT = "这是一条正常的测试回复，用于确认护栏不会误伤普通回答。"
MODELS = ["guard-cycle", "guard-repeat", "guard-thinking-only", "guard-normal"]


def log(msg: str) -> None:
    line = f"{time.strftime('%H:%M:%S')} {msg}"
    print(line, flush=True)
    with open(LOG_PATH, "a", encoding="utf-8") as fh:
        fh.write(line + "\n")


def sse(obj) -> bytes:
    return ("data: " + json.dumps(obj, ensure_ascii=False) + "\n\n").encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):  # 静音默认访问日志（我们只用 log()）
        pass

    # ---- /v1/models（app 的「拉取模型列表」）----
    def do_GET(self):
        payload = json.dumps(
            {"object": "list", "data": [{"id": m, "object": "model"} for m in MODELS]}
        ).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    # ---- /v1/chat/completions（路径不敏感：任何 POST 都按 chat 处理）----
    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        try:
            req = json.loads(raw.decode("utf-8"))
        except Exception:
            req = {}
        model = str(req.get("model") or "")
        messages = req.get("messages") or []
        last_user = ""
        for m in reversed(messages):
            if m.get("role") == "user":
                c = m.get("content")
                last_user = c if isinstance(c, str) else json.dumps(c, ensure_ascii=False)
                break
        log(f"[request] path={self.path} model={model!r} stream={req.get('stream')} "
            f"messages={len(messages)} last_user={last_user[:40]!r}")

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.end_headers()

        def chunk(delta, finish=None):
            return sse({
                "id": "chatcmpl-guardmock",
                "object": "chat.completion.chunk",
                "created": int(time.time()),
                "model": model,
                "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
            })

        def write(payload: bytes) -> None:
            self.wfile.write(payload)
            self.wfile.flush()

        started = time.time()
        sent = 0
        try:
            write(chunk({"role": "assistant"}))

            if "thinking-only" in model:
                # 判据①：只有推理、无正文无工具，随后**正常收尾**（该步会结束，故计的是跨调用）
                block = "先确认用户的问题，再看是否需要调用工具：目前看不需要工具，我先把思路理清楚。" * 6
                for _ in range(5):
                    write(chunk({"reasoning_content": block}))
                    sent += len(block)
                    time.sleep(0.05)
            elif "repeat" in model:
                # 判据③变体：连续完全相同的正文增量（阈值 60 个）
                for _ in range(120):
                    write(chunk({"content": REPEAT_TEXT}))
                    sent += 1
                    time.sleep(0.02)
            elif "normal" in model:
                for piece in [NORMAL_TEXT[i:i + 6] for i in range(0, len(NORMAL_TEXT), 6)]:
                    write(chunk({"content": piece}))
                    sent += len(piece)
                    time.sleep(0.05)
            else:
                # 判据③：逐字周期（周期 5 字符），不会自己结束
                for i in range(600):
                    write(chunk({"reasoning_content": CYCLE[i % len(CYCLE)]}))
                    sent += 1
                    time.sleep(0.02)

            write(chunk({}, finish="stop"))
            write(b"data: [DONE]\n\n")
            log(f"[done] model={model!r} deltas={sent} elapsed={time.time() - started:.1f}s "
                f"(客户端未被切断，正常收尾)")
        except (BrokenPipeError, ConnectionResetError) as exc:
            log(f"[cut] model={model!r} deltas={sent} elapsed={time.time() - started:.1f}s "
                f"客户端断开（{type(exc).__name__}）——若 app 侧出现护栏日志，即为护栏断流")
        except Exception as exc:  # noqa: BLE001
            log(f"[error] model={model!r} {type(exc).__name__}: {exc}")


def main() -> int:
    with open(LOG_PATH, "a", encoding="utf-8") as fh:
        fh.write(f"\n===== server start {time.strftime('%Y-%m-%d %H:%M:%S')} =====\n")
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    log(f"[listen] http://{HOST}:{PORT} (models: {', '.join(MODELS)})")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())

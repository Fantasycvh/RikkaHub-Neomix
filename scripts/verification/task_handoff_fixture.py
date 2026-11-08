"""Local OpenAI-compatible fixture for TaskRuntimeAcceptanceTest's handoff scenario.

Run this server, adb reverse tcp:19089 tcp:19089, and opt into the device test with
runtimeProtocol=openai, runtimeScenario=handoff, runtimeBaseUrl=http://127.0.0.1:19089.
Only synthetic test prompts are expected. No external model or credentials are used.
"""

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

class Fixture(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        tools = {item['function']['name'] for item in body.get('tools', [])}
        messages = body.get('messages', [])
        users = [str(item.get('content', '')) for item in messages if item['role'] == 'user']
        latest = users[-1] if users else ''
        if 'HANDOFF_NEW_MESSAGE' in latest:
            delta, reason, kind = {'content': 'NEW_MESSAGE_OK'}, 'stop', 'new-reply'
        elif 'dispatch_subagent' in tools:
            delta = {'tool_calls': [{'index': 0, 'id': 'handoff-parent-call', 'type': 'function',
                'function': {'name': 'dispatch_subagent', 'arguments': json.dumps({
                    'name': 'handoff-blocking', 'goal': 'Run runtime_acceptance_wait once, then return its result.'})}}]}
            reason, kind = 'tool_calls', 'dispatch-blocking'
        elif any(item['role'] == 'tool' for item in messages):
            delta, reason, kind = {'content': 'RUNTIME_ACCEPTANCE_OK'}, 'stop', 'old-task-result'
        else:
            delta = {'tool_calls': [{'index': 0, 'id': 'handoff-worker-call', 'type': 'function',
                'function': {'name': 'runtime_acceptance_wait', 'arguments': '{}'}}]}
            reason, kind = 'tool_calls', 'worker-barrier'
        print(kind, flush=True)
        event = {'id': 'handoff-fixture', 'object': 'chat.completion.chunk', 'created': 1,
            'model': 'runtime-fixture', 'choices': [{'index': 0, 'delta': {'role': 'assistant', **delta}, 'finish_reason': None}]}
        finish = {**event, 'choices': [{'index': 0, 'delta': {}, 'finish_reason': reason}],
            'usage': {'prompt_tokens': 100, 'completion_tokens': 20, 'total_tokens': 120}}
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.end_headers()
        for item in [event, finish]:
            self.wfile.write(('data: ' + json.dumps(item) + '\n\n').encode())
        self.wfile.write(b'data: [DONE]\n\n')
        self.wfile.flush()

print('handoff fixture listening on 19089', flush=True)
ThreadingHTTPServer(('127.0.0.1', 19089), Fixture).serve_forever()

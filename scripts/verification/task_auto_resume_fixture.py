"""Local synthetic OpenAI fixture for runtimeScenario=auto, auto-priority, auto-stop.

Use adb reverse tcp:19090 tcp:19090 and runtimeBaseUrl=http://127.0.0.1:19090.
No external model or credentials are used. Barriers are controlled inside the device test.
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
        calls = {call['function']['name'] for item in messages for call in item.get('tool_calls', [])}
        completion = any('External subagent task result' in str(item.get('content', '')) for item in messages)
        name, args = None, {}
        if 'dispatch_subagent' in tools:
            if completion:
                text, kind = 'AUTO_RESUME_OK', 'automatic-resume'
            elif 'AUTO_NEW_MESSAGE' in latest:
                if 'runtime_new_message_wait' in calls:
                    text, kind = 'NEW_MESSAGE_OK', 'new-reply'
                else:
                    name, kind = 'runtime_new_message_wait', 'new-message-barrier'
            elif 'dispatch_subagent' in calls:
                name, kind = 'await_background_tasks', 'parent-yield'
            else:
                name, kind = 'dispatch_subagent', 'dispatch-blocking-preset-as-background'
                args = {'name': 'handoff-blocking', 'goal': 'Run runtime_acceptance_wait once, then return its result.'}
        elif 'runtime_acceptance_wait' in calls:
            text, kind = 'RUNTIME_ACCEPTANCE_OK', 'old-task-result'
        else:
            name, kind = 'runtime_acceptance_wait', 'worker-barrier'
        if name:
            delta = {'tool_calls': [{'index': 0, 'id': 'auto-' + name, 'type': 'function',
                'function': {'name': name, 'arguments': json.dumps(args)}}]}
            reason = 'tool_calls'
        else:
            delta, reason = {'content': text}, 'stop'
        print(kind, flush=True)
        event = {'id': 'auto-fixture', 'object': 'chat.completion.chunk', 'created': 1,
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


print('auto resume fixture listening on 19090', flush=True)
ThreadingHTTPServer(('127.0.0.1', 19090), Fixture).serve_forever()

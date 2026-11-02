"""Controlled SSE backend for task-runtime lifecycle tests; never calls a real model."""
import http.server,json,time,argparse
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--port', type=int, default=19000)
args=parser.parse_args()
class Handler(http.server.BaseHTTPRequestHandler):
 def log_message(self,*a):pass
 def do_POST(self):
  body=json.loads(self.rfile.read(int(self.headers.get('content-length','0'))))
  done=any(isinstance(m.get('content'),list) and any(c.get('type')=='tool_result' for c in m['content']) for m in body.get('messages',[]))
  tool=next((t['name'] for t in body.get('tools',[]) if t['name']=='runtime_acceptance_wait'),None)
  self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
  def event(kind,data):
   self.wfile.write(('event: '+kind+'\ndata: '+json.dumps({'type':kind,**data})+'\n\n').encode());self.wfile.flush()
  event('message_start',{'message':{'id':'msg_fixture','type':'message','role':'assistant','model':'runtime-fixture','content':[],'usage':{'input_tokens':32,'output_tokens':0}}})
  if tool and not done:
   event('content_block_start',{'index':0,'content_block':{'type':'tool_use','id':'tool_fixture','name':tool,'input':{}}})
   event('content_block_delta',{'index':0,'delta':{'type':'input_json_delta','partial_json':'{}'}})
  else:
   event('content_block_start',{'index':0,'content_block':{'type':'text','text':''}})
   event('content_block_delta',{'index':0,'delta':{'type':'text_delta','text':'RUNTIME_ACCEPTANCE_OK'}})
  event('content_block_stop',{'index':0})
  event('message_delta',{'delta':{'stop_reason':'tool_use' if tool and not done else 'end_turn'},'usage':{'output_tokens':8}})
  event('message_stop',{})
  print(json.dumps({'event':'fixture_response','tool':bool(tool and not done),'time':int(time.time())}),flush=True)
print(f'Controlled runtime fixture ready:{args.port}',flush=True)
http.server.ThreadingHTTPServer(('127.0.0.1',args.port),Handler).serve_forever()

/* @rikka-plugin
{
  "id": "sample.persistent_counter",
  "name": "持久化输出计数",
  "description": "用隔离 KV 记录已处理的回复数，在输出后追加计数。需要本地存储授权。",
  "target": "output",
  "storage": true,
  "timeoutMs": 3000
}
*/
async function onOutput({ text }) {
  const count = (await rikka.store.get("count") || 0) + 1;
  await rikka.store.set("count", count);
  return text + "\n\n[plugin count: " + count + "]";
}

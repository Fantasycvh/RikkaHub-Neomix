/* @rikka-plugin
{
  "id": "sample.notebook_tool",
  "name": "插件便笺工具",
  "description": "模型可保存或读取一条插件私有便笺；工具调用始终需要用户审批。",
  "target": "none",
  "storage": true,
  "timeoutMs": 3000,
  "tools": [
    {
      "name": "note",
      "description": "保存或读取用户指定的便笺。action=save 时提供 text；action=read 时读取最近便笺。仅在用户要求管理便笺时使用。",
      "parameters": {
        "type": "object",
        "properties": {
          "action": { "type": "string", "enum": ["save", "read"] },
          "text": { "type": "string", "description": "保存的便笺内容，save 时必填" }
        },
        "required": ["action"],
        "additionalProperties": false
      }
    }
  ]
}
*/
async function onTool({ name, arguments: args }) {
  if (name !== "note") throw new Error("Unknown tool");
  if (args.action === "save") {
    if (typeof args.text !== "string") throw new Error("save requires text");
    await rikka.store.set("note", args.text);
    return { saved: true };
  }
  return { note: await rikka.store.get("note") };
}

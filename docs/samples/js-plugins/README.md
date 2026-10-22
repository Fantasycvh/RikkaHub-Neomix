# JS 沙箱插件样例

单文件 `.js`,开头注释块里放 JSON 清单,其余是脚本。导入路径:设置 → 扩展管理 → 插件 → JS 插件「导入」→ 贴链接或源码 → 解析 → **逐项确认能力** → 安装。

## 格式

```js
/* @rikka-plugin
{
  "id": "sample.demo",
  "name": "示例",
  "description": "说明",
  "version": "1.0.0",
  "author": "YourName",
  "target": "output",            // input | output | both
  "networkHosts": ["api.example.com"],  // 可选;支持 "*.example.com",内网地址一律拒绝
  "timeoutMs": 1500               // 可选,300..5000
}
*/
function onOutput({ text, ctx }) {
  return text; // 返回字符串 = 新文本;返回 null/undefined = 不改动
}
```

## 运行时契约

- 钩子:`onInput`(用户输入,发送给模型前)/ `onOutput`(助手输出,生成结束后)。只处理**最新一条**目标角色消息。
- `payload.text`:当前文本;`payload.ctx`:`{ role, messageCount, assistant: { name, id }, model: { name } }`。
- 返回值:字符串(替换文本)、`{ text: "..." }`、或 `null`(不改动)。钩子内抛错或超时 → 保留原文,不影响对话。
- `rikka.log(msg)`:写 logcat(过滤标签 `JsPluginRuntime`)。
- `rikka.http({ method, url, headers, body })`:返回 `Promise<{ ok, status, body, headers } | { ok:false, status?, body?, error }>`。
  `ok` 表示 HTTP 2xx;非 2xx 时 `ok=false` 但同样带回 `status`/`body`,插件可自行判断。
  **唯一**出站通道,必须:声明 `networkHosts` + 用户在安装时勾选"网络"能力;否则立即失败。
- 沙箱限制:没有 `fetch`/`XMLHttpRequest`/`WebSocket`/`localStorage`,不能加载外部资源、不能导航、不能读写本地文件;
  每次求值新建 WebView,超时(见 `timeoutMs`)会直接销毁沙箱,脚本状态不跨调用。

## 样例

| 文件 | 作用 | 需要能力 |
| --- | --- | --- |
| `uppercase-output.js` | 输出转大写 | 改写输出 |
| `strip-emoji-output.js` | 去掉输出中的 emoji | 改写输出 |
| `github-zen-output.js` | 取 `api.github.com/zen` 追加尾注 | 改写输出 + 网络 |

## 调试

- logcat 过滤 `JsPluginRuntime` 看脚本日志、超时与错误。
- 失败时对话不受影响(原文保留),错误只进日志。

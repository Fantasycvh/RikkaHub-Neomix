# JSON 规则插件样例

这些文件既可直接粘贴导入，也可托管到任意 HTTPS 地址后从链接导入。

导入路径：设置 → 扩展管理 → 插件 → 规则插件「导入」→ 粘贴 JSON / 填链接 → 解析 → **确认能力** → 安装。

## 格式

```json
{
  "id": "sample.example",
  "name": "示例",
  "description": "说明",
  "version": "1.0.0",
  "author": "YourName",
  "target": "output",
  "rules": [
    {
      "condition": { "field": "text", "op": "contains", "value": "广告" },
      "action": { "type": "replace", "pattern": "正则", "replacement": "替换串" }
    }
  ]
}
```

- `id`：小写字母/数字/`_`/`.`/`-`，1–64 字符，不能占用 `builtin.` 前缀。
- `target`：`input`（作用于用户消息，发送前）或 `output`（作用于助手消息，生成结束）。
  能力随之确定为 `PROMPT_TRANSFORM` / `OUTPUT_TRANSFORM`，安装时必须由用户确认。
- 条件字段：引擎按路径取值，当前可用
  `text`、`role`、`messageCount`、`assistant.name`、`assistant.id`、`model.name`。
- 复合条件：`{ "and": [...] }` / `{ "or": [...] }`，可任意嵌套。
- 操作符：`eq`、`ne`、`lt`、`le`、`gt`、`ge`、`contains`、`startsWith`、`endsWith`、`regex`、`in`。
  两侧都能解析为数字时按数值比较，否则按字符串比较。
- 动作：
  - `replace`：正则替换，支持 `$1` 分组引用；
  - `append` / `prepend`：追加 / 前置固定文本。
- 同一条消息会按顺序执行所有命中的规则；坏正则/非法动作原样返回，不会吞文本。

## 样例

| 文件 | 作用 |
| --- | --- |
| `strip-ad-lines.json` | 删除输出中整行包含「广告」的行 |
| `append-signature.json` | 输出末尾追加固定尾注 |
| `input-repeat-hint.json` | 发送前在用户消息末尾追加提醒 |

## 验证

- 导入后可在插件详情页看到规则列表与能力授权状态。
- logcat 过滤 `JsonRulePlugin` 可看到每次生成命中的规则数。

// 拉取 https://models.dev/api.json，只保留模型匹配用到的字段，写入 app assets 作为内置快照。
// 字段与 ai 模块 ModelsDevCatalog 解析的保持一致；app 运行时刷新也会写出同样格式。
// 用法：node tools/models-dev/update.ts（本机没有 bun，脚本用 Node 24 的全局 fetch + node:fs）

import { writeFileSync } from "node:fs";

const OUTPUT = `${import.meta.dirname}/../../app/src/main/assets/models_dev.json`;
const USED_MODALITIES = new Set(["text", "image"]);

type Model = {
  modalities?: { input?: string[]; output?: string[] };
  tool_call?: boolean | null;
  reasoning?: boolean | null;
  limit?: { context?: number | null };
  canonical_model_id?: string | null;
};

const response = await fetch("https://models.dev/api.json");
if (!response.ok) throw new Error(`models.dev responded ${response.status}`);
const api = (await response.json()) as Record<string, { models?: Record<string, Model> }>;

const slim: Record<string, { models: Record<string, unknown> }> = {};
let count = 0;
for (const [providerId, provider] of Object.entries(api)) {
  const models: Record<string, unknown> = {};
  for (const [modelId, model] of Object.entries(provider.models ?? {})) {
    models[modelId] = {
      modalities: {
        input: (model.modalities?.input ?? ["text"]).filter((it) => USED_MODALITIES.has(it)),
        output: (model.modalities?.output ?? ["text"]).filter((it) => USED_MODALITIES.has(it)),
      },
      ...(model.tool_call ? { tool_call: true } : {}),
      ...(model.reasoning ? { reasoning: true } : {}),
      ...(model.limit?.context ? { limit: { context: model.limit.context } } : {}),
      ...(model.canonical_model_id ? { canonical_model_id: model.canonical_model_id } : {}),
    };
    count += 1;
  }
  slim[providerId] = { models };
}

// 单行 JSON（与上游一致）：快照只是机器读的数据，不需要可读性，单行更省也没别的副作用
const payload = JSON.stringify(slim);
writeFileSync(OUTPUT, payload, "utf8");
console.log(
  `${Object.keys(slim).length} providers, ${count} models, ${Buffer.byteLength(payload)} bytes -> ${OUTPUT}`,
);

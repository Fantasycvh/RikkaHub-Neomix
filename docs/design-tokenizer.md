# 真 tokenizer（Kotlin BPE）设计稿

> 状态：**✅ 已实现（2026-09-26，`25141095` 引擎+词表+验证，`05c34f69` 接线）。**
> 一句话：把全仓的 token 估算从**字符启发式**换成**真 BPE 分词**（Kotlin 实现 + 内置 cl100k_base 词表），
> 因为用 tiktoken 真值比对下来，原先的启发式**系统性偏低**，而调用它的地方全是"预算判定"。

## 一、为什么（量化动机）

启用 token 计数的地方全是预算/阈值判定：上下文占用条、自动压缩触发、记忆注入预算、
子代理上下文预算（`ContextBudgetPlanner`）、`PromptInspect` 逐段计数、工具 schema 体积。
用 **44 条样本**（ASCII / CJK / emoji / 代码 / 全角空格 …）与 `tiktoken 0.13.0` 的真值比对：

| 指标 | 值 |
|---|---|
| 启发式 / 真值 中位数 | **0.64** |
| 区间 | 0.17 – 2.00 |

⇒ 系统性偏低：**该压的时候不压、该停的时候不停**（例如 8K 上下文按启发式只算 5K）。

## 二、方案

**Kotlin 字节级 BPE（tiktoken 同款算法）**，不引入 Rust/NDK，也不依赖网络：

1. 预分词（tiktoken 的 `cl100k_base` 正则，逐字符移植）；
2. 每片 UTF-8 编码 → GPT-2 的 `bytes_to_unicode` 映射成可见字符（保证"半个 UTF-8 字符"也能作为
   独立 token 参与合并，CJK/emoji 不会被拆坏）；
3. 反复取**相邻对里 rank 最小**的一对并合并其**全部出现**，直到无可合并；
4. 每个最终片在词表里的 rank 即 token id。

### 2.1 词表：怎么放、多大

官方 `cl100k_base.tiktoken`（base64 "token rank" 文本，1.68MB）→ 构建期转成
**按 rank 升序的 `varint(长度) + 原始字节`** 紧凑二进制：**744KB 原始 → 456KB gzip**。
放 `common/src/main/resources/tokenizer/cl100k_base.bin.gz`。

**为什么用 Java 资源而不是 `assets/`**：Java 资源在 Android 与 JVM 单测里都按 classpath 读得到
（`getResourceAsStream`），**不需要 Context/AssetManager**——也就不必把 Context 灌进工具层，
单测可以直接对真词表跑向量。（已验证：APK 里确实有 `tokenizer/cl100k_base.bin.gz`。）

### 2.2 分层与兜底

```
common/tokenizer/ByteLevelBpe           纯引擎（不可变、线程安全）
common/tokenizer/TokenizerRegistry      唯一入口：预热状态 + 有界缓存（512 条）
        ↑                                     ↑
app/utils/ContextLength.estimateTokens    ai/util/ToolSchemaSize
（全仓估算出口：真值优先 → 取不到退启发式）
```

- **预热是后台的、可能失败的**（456KB gz + 10 万条哈希表；真机 1389ms）⇒ 未就绪时 `count()` 返回
  `null`，调用方**退回启发式**：精度低一点，但**行为不中断**、也不会让"估算"这种非关键路径把请求打崩。
- **兜底路径保持"按整批字符统计"**，而不是把逐段启发式相加——后者每段都会被 `toInt()` 向下取整一次，
  长轨迹能凭空少算上百 token（改的时候踩过一次，已有测试钉住）。

## 三、性能与内存（实测）

| 指标 | 值 | 说明 |
|---|---|---|
| 批量计数 | **114K 字符 / 49ms** | 首版 180ms；优化=**片段级计数缓存**（自然语言里片段重复率极高）+ **"拼起来超过最长 token 就剪枝"** |
| 常驻内存 | **+12MB**（生产） | 10 万条 `HashMap<String,Int>`；**生产不保留解码表**（保留再多 +14MB），因为只编码不需要它 |
| 预热耗时 | 1389ms（<device>） | 后台，不阻塞启动 |

**内存是本功能唯一的硬成本**；若将来要抠，方向是更紧凑的 rank 结构（或按需分片加载），
而不是放弃精度。

## 四、踩过的坑（都是"单测绿、真机挂"）

1. **Android 的 `java.util.regex.Pattern` 不支持 `UNICODE_CHARACTER_CLASS`**（桌面 JVM 支持）
   ⇒ 单测全绿、真机启动 `ExceptionInInitializerError: UNICODE_CHARACTER_CLASS flag not supported`。
   解法：把 `\s` 展开成**显式 Unicode 空白集**（`WS` 常量）、`\S` 写成 `[^WS]`。
   **去掉该 flag 后 44 条 tiktoken 向量仍逐条一致**——等价性由此证明（而不是"看着像"）。
2. **aapt2：资源里未转义的单引号**会报 `Invalid unicode escape sequence in string`
   （韩文 legend 的 `'합성'` 中招）⇒ 转义 `\'`。
3. Kotlin 的转义序列表里**没有 `\f`**（要写 `\u000c`）。
4. **"安装成功"的返回值不能靠外层 `runCatching{}.isSuccess`**：内层失败但不抛时会被误报成功
   （首版日志 `ok=true vocab=null`）⇒ 返回内层结果。

## 五、验证

- **44 条 tiktoken 参考向量逐条一致**（`common/src/test/resources/tokenizer/cl100k_reference.json`），
  含全角空格（U+3000）、不换行空格（U+00A0）、零宽空格（U+200B）、制表符、代码缩进、emoji、
  特殊 token 字面量（`<|endoftext|>` 按普通文本计）。
- encode↔count 长度一致、decode 往返一致、词表大小 = 100256、缓存幂等、内存量级。
- **真机数值对照**：预览页 `预估 token ≈ 8126` vs 该会话上一轮 API 回传 prompt tokens **8.5K**
  ⇒ 偏差约 5%（换之前启发式约低 35%）。

## 六、仍未做 / 后续

- **o200k_base**（gpt-4o 系）与各家自有分词（Gemini 的 SentencePiece、Claude）：`cl100k` 对
  OpenAI 兼容模型是**精确值**、对其它是**近似值**。加一份词表资源 + 一个按模型的编码选择即可
  （引擎与注册表已经是编码无关的），到时再决定是否值得那几百 KB。
- **按模型选编码**（`Model` 上加 encoding 提示 / 名称启发式）。
- 低内存时 `TokenizerRegistry.release()` 的调用点（`onTrimMemory`）。

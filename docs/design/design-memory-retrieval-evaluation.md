# D：记忆检索回归集与主题组织

> 2026-10-19。D 已完成：先冻结并实测基线，再做两项词法修复；全量 JVM 与隔离 Room 回归通过。
> 延续 A/B/C；不引入向量数据库、额外 LLM 路由、自动重写存量、扩大默认预算或改变手动全库复核语义。

## 1. 判据和范围

D 的原定范围是「检索回归集、主题组织，必要时改索引或检索算法」，不是预先选定 BM25/embedding。
固定数据、问题、相关条目标签和预算后测量，再决定最小改动。离线检索命中不等于模型已经正确回答，
搜索返回不等于有效使用，索引可见不等于全文可见。

黄金集：[retrieval-golden-v1.json](<../../app/src/test/resources/memory/retrieval-golden-v1.json>)。
所有记录和问题均为手写合成数据，没有读取生产记忆或调用模型。

- `regression`：已有行为的控制组，包括中英日韩、大小写、description、单字、严格不同覆盖度的排序、
  重复词、超长记录、无答案与停用词。排序反例刻意让较新 id 的完整匹配战胜较旧 id 的部分匹配。
- `challenge`：执行基线之前冻结的边界，包括 Unicode 宽窄/组合等价以及「单字中文 + 拉丁词」混合查询。
- `limitation`：独立报告的已知能力边界（符号语言名、单字母、隐含偏好、英文屈折），
  不把它们偷偷改成负样本，也不为了全绿临时更换标签。
- `contentRepeat` 仅是测试夹具压缩表示，载入后展开成真实长正文再经过同一渲染与计数。

## 2. 两类链路分别评测

### 2.1 显式 search

评测必须调用生产检索内核，不能在测试复制一套「看起来相同」的排序逻辑。
固定 k=1/3/5，正样本分别计 macro Recall@k、MRR@k、binary nDCG@k；
空相关集合不进入这些指标的分母，单独计无答案误命中率及其样本数。
无相关项的 cohort 不能伪造 100% 召回，未命中正样本按 0 记而不是跳过。

同分旧 `sortKey` 在前、非正 limit 返回空、正文/description 共用词法分词及覆盖度等现有规则保留。
查询不应推进读取计数、时间、业务版本、历史或回收站。

### 2.2 自动注入

固定 `now`、预算 0/500/1200/8000、冻结锚点策略与冷态启发式估算器，直接评测 C 的生产 plan。
分别报告：

1. L2 正文召回；
2. L1+L2 完整文本召回；
3. L1+L2+L3 可见召回；
4. 实际三段估算 token、相关全文数及成本。

计划的 `fullText`/`indexed` 是渲染顺序，不当作相关性排名计算 nDCG。
无答案查询不要求注入为空：身份锚点与重要记忆有意不依赖词汇重叠。
每种预算都校验完整渲染硬上限、无重复 ID、真实省略数、整条正文准入。
不把 provider envelope、transformer、真实回答准确率或调用成本混入本地估算结论。

## 3. 数据与内容组织约定

本轮不自动改写、拆分或删除任何已有记忆。对新增/人工编辑记录，建议：

- 一条围绕一个能独立更新的主题；正文保留事实主体、约束、必要时间和例外。
- description 是短主题提示，不是另一份正文，不要填无关热词吸引召回。
- 用户纠正已有事实时按原 ID/revision 更新，不并排保留两条互相矛盾的现行偏好；
  旧文由 B 的 history 留存，不进入 live 搜索。
- 长记录可由用户按自然主题显式整理；不得仅为节省 token 自动原子化重写全库存量。
- 省略的条目先 search、再按回执的 ID/revision read；不要依据截断索引猜正文或变更。

这只是内容组织建议，不代表宿主能判定所有语义矛盾。混合主题与隐含问法仍需单独评测。

## 4. 真实存储接线验证

[MemoryRetrievalInstrumentedTest](<../../app/src/androidTest/java/app/androidrik/rikkahub/data/repository/MemoryRetrievalInstrumentedTest.kt>)
只用合成 in-memory Room，验证跨库/回收站不可搜、纠正后历史正文不泄漏、严格相关性排序及 limit、
查询等价形式和来源遥测不变。它验证的是检索接线，不是 provider 回答质量或生产升级。

## 5. 运行证据

### 5.1 可回放的冻结数据

- 黄金集：**23 条记忆、43 个问题**；31 regression（25 正 / 6 负）、8 challenge、4 limitation。
- 基准提交：`e9febda0eee5822f16ff4942e94891c4ccdd06b3`；先做语义不变的 search 排序 helper 提取，
  然后实测旧词元算法的报告并冻结，最后才修改算法。
- 黄金集 SHA-256：`28932bc7a6a3eb1acd7dd4e620fe659ab07d99e4d0501ee4f82de1ee0a97f65e`。
  测试校验固定哈希，只允许跨平台 CRLF/LF 差异；不得为了全绿改标签。
- [实测基线快照](<../../app/src/test/resources/memory/retrieval-baseline-v1.json>)
  保留基准提交、原始报告 SHA、逐问题搜索结果、分组指标和固定预算注入均值；不是手填的理想输出。
- [评测入口](<../../app/src/test/java/app/androidrik/rikkahub/data/ai/MemoryRetrievalEvaluationTest.kt>)
  有 6 项测试：报告及控制组、手算指标、严格排序/limit、8 个边界样本、逐问题基线不退化、
  固定预算的全文/可见召回与单位 token 质量不退化。

运行当前评测（需先按项目构建说明配置 Java/pnpm）：

```bash
./gradlew :app:testDebugUnitTest --tests app.androidrik.rikkahub.data.ai.MemoryRetrievalEvaluationTest
```

报告输出到 [retrieval-evaluation.json](<../../app/build/reports/memory/retrieval-evaluation.json>)（构建产物，不入库）。
完整 app JVM 回归用 `./gradlew :app:testDebugUnitTest`。
设备回归使用 `-PmemoryIsolatedTests=true`，选择 A/B/C/D 的四个 repository 测试类及 40→41、41→42 两个迁移类，
而不是运行会启动生产 Application 的一般设备测试。复现选择性设备回归：

```bash
p=app.androidrik.rikkahub.data
classes="$p.repository.MemoryTransactionInstrumentedTest"
classes="$classes,$p.repository.MemoryVersioningInstrumentedTest"
classes="$classes,$p.repository.MemoryReadTelemetryInstrumentedTest"
classes="$classes,$p.repository.MemoryRetrievalInstrumentedTest"
classes="$classes,$p.db.migrations.Migration_40_41_Test,$p.db.migrations.Migration_41_42_Test"
./gradlew :app:connectedDebugAndroidTest -PmemoryIsolatedTests=true \
  "-Pandroid.testInstrumentationRunnerArguments.class=$classes"
```

### 5.2 仅做两项有证据的修复

[MemoryRelevance](<../../app/src/main/java/app/androidrik/rikkahub/data/ai/MemoryRelevance.kt>)：

1. 分词比较键先 NFKC 再小写，覆盖宽窄、组合音标、半角片假名及分解韩文的等价输入。
   不去音标、不做词干/同义词扩展，不写回正文、description 或 ID。
2. 有 gram 时保留 **独立 CJK 连续段长度为 1** 的实字，与其他查询词元等权参与覆盖度。
   `猫 python` 不再漏掉猫；`咖啡` 不会被拆成「咖」与「啡」作宽泛 OR 查询；重复字仍去重，虚词仍过滤。

floor、cap、boost、时效/重要度、同分规则、冻结锚点和预算不变；检索与注入共用词法修复。
[4 项新增归一化回归](<../../app/src/test/java/app/androidrik/rikkahub/data/ai/MemoryRetrievalNormalizationTest.kt>)
在旧算法下 **4/4 失败**，修复后全绿；同时检查双向等价、额外未入黄金集的形式和不应放宽的负例。
独立源码审查未发现可证 P1/P2；之后新增的逐问题与固定预算基线守卫由 Lead 审阅，
并在 5.4 的完整 JVM 回归中实际执行通过。

### 5.3 实际指标

以下是**开发时已知的合成边界集**，不是盲测、真实用户问题分布或模型最终答案准确率。
正样本分母 37、负样本分母 6，前后黄金集、时间、估算器和预算完全相同。

| search 指标 | 基线 | 修复后 |
|---|---:|---:|
| macro Recall@1 | 70.27% | 86.49% |
| macro Recall@5 | 70.27% | 89.19% |
| MRR@5 | 72.97% | 89.19% |
| binary nDCG@5 | 70.88% | 89.19% |
| 无答案误命中 | 0/6 | 0/6 |
| 8 个 challenge 的 Recall@5 | 12.50% | 100% |

31 个控制样本保持原有门限；8 个 challenge 逐问题均改善，无搜索指标退化。
4 个 limitation 仍漏召回：`C++`、`R`、隐含「我一般喝什么？」、`cats`/`cat` 屈折。
不声称本次已解决语义理解、纠错时序推理或所有多语分词问题。

固定预算注入（37 个正样本的均值；全文含 L1+L2，可见额外含 L3）：

| 预算 | 全文召回：前→后 | 可见召回：前→后 | 本地估算 token：前→后 |
|---:|---:|---:|---:|
| 0 | 0→0 | 0→0 | 0→0 |
| 500 | 78.38%→89.19% | 82.43%→91.89% | 484.81→485.46 |
| 1200 | 97.30%→97.30% | 100%→100% | 842→842 |
| 8000 | 100%→100% | 100%→100% | 3966→3966 |

500 预算的平均「相关全文条数 / 千估算 token」由 **1.6710→1.9473**；
**不是绝对 token 下降**（该档平均输入略增），也不是 provider 账单减少。
宽预算下即使 search 未命中，一些记忆仍因原有重要度/锚点策略入选，所以不能把注入召回提升当作 search 指标。

### 5.4 完整回归与安全边界

- **209 个 JVM suites / 1909 项 app 测试，0 failures/errors/skipped；其中 memory 355 项。**
  新鲜 XML 的 UTC 时间范围为 `2026-10-19T02:21:49.576Z`–`02:22:00.634Z`。
- **Android 15 / PJX110：55/55**，报告时间 `2026-10-19T02:22:06`（Gradle connected XML）。
  细分：事务 21、版本 19、来源遥测 6、D 检索 4、40→41 迁移 3、41→42 迁移 2。
- 新 Room 测试实际构造异库记录、删除墓碑和纠正前的 history，并检查查询后整行/历史/回收站不变；
  双来源已有计数也不因 search 改动。
- 测试前后 `pm list packages` 都只有正式包；Gradle 已移除本次隔离 debug/test 包。
  未覆盖正式 APK、未访问生产数据库、未调用真实模型。
- DB 仍为 42，无新索引或迁移；A/B/C 的版本、事务、依赖、回收站、预算和冻结策略保持不变。
- 未专项覆盖进程死亡、并发 tokenizer 切换、极新 Unicode 在不同运行时的表差异或真实回答质量。
  本轮未提交、推送、构建 release、bump 版本或发版。

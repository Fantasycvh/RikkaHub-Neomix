# 借鉴判决与判据总集

> 首发于 2026-11-08 轮（NMoi-Agent 173 提交 + BiliPai 108 提交增量评估），2026-11-08 起扩展为**跨项目历史教训与判据的常设总集**：把各评估台账中可迁移的判据、负面样本、方法论规则精简提炼汇总于此。
> 细节与证据回查源台账：[peer-apps-benchmark.md](peer-apps-benchmark.md)（总索引）、[upstream-analysis.md](upstream-analysis.md)、[references/other projects/](references/other%20projects/) 各文档。
> 维护约定：每轮增量评估的**新增候选判决**进 §五；通用判据沉淀进 §二（标日期）；教训进 §三。
> 执行约定：用户拍板跟哪条 → 实施 → 真机验证；本地提交可自主做，**推送/发版等明确授权**（AGENTS.md 强制规则）。

## 一、跨项目同证判据（多家独立得出，置信最高）

1. **稳定前缀纪律**——易变块（时间/记忆/动态注入）一律排尾、同回合只采样一次；首条 user 是缓存 pin 不可跨越；wire 字节恒等。反例 `{{cur_date}}` 放 system；senera 实测一次记忆写入致命中 99%→72%（agentd §9.4 / senera §7.4 / billion-context §B / sillytavern §6.7④）。
2. **压缩须追加式不重写**——级联小折叠比一次大折叠贵 ~70 倍，「每次请求都压一点」是净亏反模式；触发改增量门非占用率（billion-context §B/D/E；另证「单 checkpoint 替换式压缩」结构性反模式）。
3. **工具结果一律「落盘 + 句柄 + 受限翻页」**——截断预览＋一次续读＋回取指回原工具；大输出到达即外置（SoLab / senera / NMoi R4 重复读路由 / billion-context，四家同证；判据已沉淀进 `design-tool-output-budget.md`）。
4. **工具 schema 三档 / 元工具懒加载**——本地工具 >20 个后决定可用性交给检索；检索 lexical-first，embedding/rerank 默认关（solab §二.1 / senera §7.1）。
5. **模型自报「完成」不可当成功**——以交付物/文件验收（agentd §9.4A3 / purrcat §3.5）。
6. **错误文案即修复指令**——稳定规则码＋修复清单让模型自修而非重掷（archify §十二 / rikkahub-agent §二.6）。
7. **usage 锚点估算 + 换模型 ⇒ 基线作废**——否则压缩判定永不触发（rikkahub-agent §二.1 / billion-context B4）。
8. **后台/无头运行必须持久化账本**——awaiting_approval 一等状态 + process_lost 诚实终态（rikkahub-agent §二.4 / purrcat §3.2 / yuihub 恢复状态机）。
9. **语义检索（embedding）不做**——Yuihub 上线次日删除 + 本仓成本/隐私判断，三家共识（PB §4.1 #1）。

## 二、域判据总集（一行一条，带源出处）

### A. 上下文与缓存
- 缓存身份**内容寻址**；折叠身份用 **wire 顺序**不用内容哈希（senera §7.3 / billion-context B1）。
- 锚点解析**不得改写 pin 位置**——曾改写 index 0 酿 56% 未命中事故（billion-context §B）。
- 跨请求前缀差分诊断：对比两次请求字节前缀定位失效源（huadeng §8.1E）。
- token 估算按 (模型,层级,内容类) 三维标定；估算**覆盖面 > 精度**（NMoi budget.rs / sillytavern §6.12）。
- 上下文档位 F1–F4 一个旋钮 + 任务锚点（最后一条 user **原样**截取拼进压缩摘要尾、不新增消息防 last_user 指错）（NMoi anchor.rs / senera F1-F5）。
- 压缩触发线数据点：NMoi 真机迭代默认 0.75/0.92 → **0.45/0.55**、窗口默认 1M（budget.rs:313/721）——与本仓 80% 触发方向相反，**等净化观察期双口径数据一起判**（2026-11-08 新）。
- 世界书扫描窗口含 assistant 消息与思考文本（NMoi lorebook.rs:199）——本仓是否含思考待核（2026-11-08 新）。
- 压缩摘要**机械携带**本段工具/文件痕迹与媒体句柄：可重取句柄（URL/fileId/fileUri）机械追加进摘要块、穿过折叠/合并/蒸馏，绝不靠模型自觉；本仓摘要输入只取 Text/Notice（`Message.kt:222-231`），折叠区图片/文档进摘要器前即被丢弃（NMoi D296 / billion-context `d126087ab`，2026-11-10 候选）。
- web/App 统计行 token 口径：取**最后一次** step 的 usage，不累加；`lastUsage`/`contextTokens` 落进 UIMessage（upstream `921427f95`，2026-11-10 候选 P2）。

### B. 压缩与读回
- 压缩按节点 ID **不删原文只换视图**；压缩后读回以 CompactionCheckpoint 指纹、编辑消息自动失效（rikkahub-agent §二.3 / yuihub §四.7）。
- 世界书注入带 SourceTag 进档位白名单不降级（NMoi session.rs:536）（2026-11-08 新）。
- 折叠对账**分级**：直接 ID 多数存活 = 部分丢失，不做「有缺即全量」的二次决策；压缩产物不得保留可调用形状（relocate v2 载体）；「淘汰清单条数上限」是陷阱——>64 张图永远压不到预算下会无限重试，必须另设字节/条数双维硬顶（billion-context #5745/#5968/#5470，2026-11-10 记账）。
- 一轮中途也要能压缩：长 agent run 只有轮前压缩 + 工具循环优雅停止，循环层压不住上下文（upstream `bcc649abc`，2026-11-10 候选 P2）。

### C. 工具与执行
- 重复读路由：同路径未变 → 掩码占位 ~10tok，变了 → 截断+差异计数（NMoi router.rs:167，判据沉淀进 design-tool-output-budget.md）。
- 工具调用**车道并行**：Read(4)/Workspace(1)/Process(4)，未声明默认串行防写冲突，结果按调用下标回填（NMoi agent.rs:535）（2026-11-08 候选 C2）。
- edit_message（模型改历史）安全四件套：只排队轮收尾统一落库 / 锁定消息拒绝 / 只读工作区不声明 / 改写落红（NMoi session.rs:1287）（2026-11-08 新）。
- LoopGuard 按工具语义 TTL + 只读豁免；重复度判据用**逐字周期**不用低熵比值（rikkahub-agent §二.2 / upstream §注意点 15）。
- native/MCP 挂死 = 永久不可用 → **串行门 + 熔断**；失败调用须 forget（solab §二.6 / LoopGuard）。
- 上下文图片清单 + 淘汰 + View-Image 回取：清单 20/硬顶 100、临时额度活到下次思考结束、模型可把被淘汰的图拉回（NMoi images.rs）（2026-11-08 候选 C1）。
- 工具超时/轮数边界：真正的边界是上下文/取消/上游，轮数 0=无上限（NMoi e38fc29）。

### D. 审批与安全
- **previewToken 参数指纹审批**——防「批准 A 执行 B」（solab §二.3）。
- 仅只读免审批工具可参数复用，且复用前重校验（senera §7.3）。
- 高危工具**子集**禁永久授权（rikkahub-agent §二.5）。
- 沙箱不可用时**明确拒绝**，不悄悄本机执行（senera §7.4）。
- 危险操作三档：放开一类保护 = 每次确认、无「不再提示」；「问 AI」只预填不直发（dsh-go §2.4）。
- KILL 前 2.5s 预算快照：只采「有 windowFocus 的 activity + 滚动锚点 + 断点」；快照带 processSessionId/账号 mid 防串号、consume 一次性消费、恢复前清旧请求态（BiliPai FairMemoryReceiver.kt）（2026-11-08 新，厂商广播不可移植、结构可借）。
- 内容级脱敏 6 类正则（ghp_/sk-/AKIA/PEM/URL 凭据/JWT），URL 凭据规则排最前；导出与落库路径分开、语义边界待拍板（NMoi redact.rs）。
- 能力生产线 = 沙盒起草 + 同一校验器双跑 + 人工审批 + 版本回滚（purrcat §3.3）。
- 用户显式档位被源级/模型级配置静默覆盖 → 必须显式提示冲突（bigeye：思考档位被 extra_body 覆盖；本仓 `Request.kt:115-135` `mergeCustomBody` 同构，2026-11-10 候选）。
- 「接口没报错」≠ 字段生效：探活不得替用户定档；默认档 = 删记录而非只不传字段（bigeye，2026-11-10）。

### E. 存储与备份
- **迁移前留 `.v<旧版本>.bak` 副本 + 迁移函数可重执行**（NMoi 65e13bf/D260 duplicate column 真机教训）（2026-11-08 候选 C4）。
- 备份发布前自校验：**2026-11-08 已实现**，完整 ZIP 与增量备份在快照上跑 `integrity_check` + `foreign_key_check`，通过才继续；详见 [实施记录](design/verification/attachments-backup-memory-order-2026-11-08.md)。
- 备份 manifest + 双 SHA-256 + **staged 换库启动回滚**（推迟 startKoin 化解检测难题）（yuihub §四.8）。
- WAL 取证：主库缺失而 -wal/-shm 在 = 同步撕裂拒开；0 字节主库 + WAL 在 = 拒开（SQLite 会静默丢 WAL）——**启动路径**要查，不只恢复路径（NMoi L0）。
- U+FFFD 拒写二进制；裸 EOF 标 finishIncomplete 不当正常结束（yuihub §四.8/§四.5）。
- Room `fallbackToDestructiveMigrationOnDowngrade(true)`；sealed @Serializable 子类须有直接代码引用防 R8 删（huadeng §八.1）。
- 安装/更新事务化：SHA-256 校验 + staging 原子切换留旧环境；签名是更新最后防线（dsh-go §2.3）。
- 附件落**会话私有目录**、删会话清目录；库只存路径 BLOB 懒迁移（NMoi D216）。
- 生成产物入库必须 CI 重建 + `git diff --exit-code` 比对并 pin LF；更优解是产物不入库、构建期生成（purrcat，2026-11-10）。
- 应用内安装/更新回执：dispatch 完成 / Activity resume / CLI 确认**都不算安装成功**，必须核对实际安装包+签名集+version+摘要；未知结果不自动重试、不重建命令/路径重放（dsh-go §7.2，2026-11-10 记账）。

### F. UI / 玻璃 / 动效 / 输入
- 液态玻璃门控层三件（SDK 分级 / 前后台 gate / hazeSourceCompat）+ 可读性一等设置——**✅ 已落地 2026-10-31**；ReduceMotion + VisualWorkPolicy——**✅ 已落地 `3254e33b0`**（bilipai.md）。
- **TextFieldState 单向同步纪律**：编辑态唯一权威是 TextFieldState / VM 字段只做投影 / 程序化写入先比较再 `setTextAndPlaceCursorAtEnd` / 绝不向活跃 IME 会话回写（BiliPai SearchScreen.kt:699）（2026-11-08 候选 C6，与 AGENTS.md TextField Host Rules 互补：宿主拓扑 vs 状态流向）。
- **动效令牌收口**：object + @Composable accessor + 命名时长/弹簧族（BiliPai AppMotionTokens.kt:85-189；本仓全仓零中央令牌）（2026-11-08 候选 C5）。
- 玻璃预设 = progress 三锚点曲线 + 一键预设**原子提交**（材质+高级参数同提交）防半套配方（BiliPai LiquidGlassTuning.kt）（2026-11-08 新）。
- **渐进模糊首帧闪烁**：恒定半径模糊只靠 layerAlpha 淡入时来源页不先 drawContent → 开场透出窗口底色；修复 = 锐利原层按 (1-blurAlpha) 垫底参与混合（BiliPai ab16ad9c4）——对任何 haze 转场最有价值（2026-11-08 新）。
- 转场位图坑族：HARDWARE 位图进软件 Canvas 直接 IAE → 建同配置 HARDWARE 目标+硬件画布；可变位图反向 PixelCopy 落软件（重载 API29+、SAM 推断失败 → `source.copy(ARGB_8888,false)`）；透明位图先 drawColor 底色；失败一律显式降级不硬崩（BiliPai SystemVideoCardTransition.kt）（2026-11-08 新）。
- MaterialBackdropBlur 磨砂参数：`downscaleFactor=1`、sigma=radius×0.45、padding=3σ，防大半径降采样滚动闪烁（BiliPai）（2026-11-08 新）。
- 跨应用划词翻译零权限正解 = `ACTION_PROCESS_TEXT` 对话框 Activity——**✅ 已落地**（miffan.md）。
- 装饰/可视工作的门控必须含**视口可见性**（离屏页前后台 gate 管不到），且绝不覆盖数据更新与副作用（BiliPai `DecorativeAnimationGate` ↔ 本仓 `VisualWorkPolicy.kt:13-14`，2026-11-10）。
- **材质配方与采样源不得同批改**：同批改则回归无法二分归因（BiliPai 当日互为逆提交整体回滚，2026-11-10）。
- 模态预测返回：手势只作预览（≤25%），完成回调可被业务校验拒绝 → 必须复位 `isCompleting` 与可交互外观（BiliPai `e763dcedb`，2026-11-10 候选 P3）。
- 第三方库内崩溃只能在构建期兜底（ASM 补丁 fail-closed：目标签名/方法缺失即构建失败，不许静默失效）（BiliPai `371166ba2`，2026-11-10 记账）。

### G. 模型与生成
- 显式 `tool_choice:"auto"`（tools 非空必发）+ 零字流退回非流式重发一次（已有正文不重发）——**✅ 第一件已落地 `064a8b772`**（NMoi e3cbed8 / ddd2860，bigeye llm.py:983 同证）。
- usage 归一：Anthropic input_tokens 不含 cache 需加 cache_read+cache_creation；Gemini usageMetadata 用 max 合并；message_start 的 cache 计数不可信（NMoi 判据表）。
- 小模型结构化决策判不出 → **{Ok/Uncertain/Failed} 三态 + Uncertain 静默回退**要显式声明（huadeng §八.2）。
- 真实 0 与未知占位区分（BiliPai FormatUtils）。
- 记忆注入 = 打分校准 + **token 预算**（字符预算不可照搬：1800 字符砍 54→7 条）——**✅ 已落地 `91d88551`**（yuihub §4.2）。
- **错误字符串清单不可作重试判据**：网关换措辞即退化成 0 次重试；用状态码/异常类型分流，只对实测文案补清单，致命额度/鉴权先行拦截（bigeye 同坑三轮，2026-11-10）。
- 截断/预算恢复必须**改参数**（放大 max_tokens 或换缓存 key）并留结构化停因（`termination_kind`/`stop_reason`）；严格思考源续写须回传 reasoning_content（bigeye，2026-11-10）。
- Claude 流式签名是 **delta 累加**不是覆盖；非流式相邻 thinking 同签名才合并，否则保 metadata（upstream `e1667163c`，2026-11-10 候选 P2）。

### H. 评估与流程方法论
- 已吸收判定用**代码等价核对**，禁用 merge-base 判祖先（手工直套恒误报）；「判已被上游推翻」才是 merge-base 合法用法（upstream §注意点 1/12）。
- 吸收前必查上游是否自己 revert 过该 fix（`d3a53e0a` 事故）；批次「已吸收核心件」≠逐提交已核，重改文件上的 fix 须逐条 grep（`aac6e963` 漏吸收）。
- fetch 区间 ≠ 欠账量：欠账看文档同步点不看 ref；评估表预判 ≠ 动手前实核（11-07 批四条候选实核改判等价）。
- 补丁不可照搬时先找本仓同源写法——**吸收的是教训不是类**；护栏/熔断必须 fail-open；带签名模型产出块不得改写回传。
- 三档证据不混用：源码 / 远端 / 抽样，二手须显式标注；**评估缺点比优点有用**；自述 ≠ 事实先核验再记账，凡要移植的先 grep 消费方；同名不同物拆开算（PB §4.2）。
- 「不做」清单本身就是资产（PB §4.3 含 10 条已明确不做方向）。
- 大改挂「观察期触发点」：语义接受 / 实测症状出现才升级，不预先动手。
- **静态红线脚本必须接进 CI 才算门禁**（AGENTS.md 写了跑法但没人跑 = 不存在）；测试按目录自动 marker、挂起用例带任务号且总数设上限；功能下线同一次改动删净（代码+用例+fixture+spec+文档）（purrcat `6a95539`，2026-11-10 候选 P1）。

## 三、历史教训与负面样本（每家一条承重的）

| 项目 | 教训 |
|---|---|
| agentd | 评测数字出自合成噪声模型，勿当真 |
| archify | Node CLI 装成 Android 技能跑不通——**抄契约不抄运行时** |
| bigeye | 「不建议生产」的免费负面样本：服务模型当下关注 ≠ 上下文体积 |
| solab | native 挂死 = MCP 永久不可用（见 §D 串行门） |
| dsh-go | PRoot 放大 2.8 万次路径 syscall；39% 市场插件一处插槽接不到 → **市场不宜先做**；CI 绿 ≠ 手机 OK |
| huadeng | 主动消息连修四层 P0 → 形态级风险高；判借鉴关系先查上游 → 逐字 diff → 才看历史 |
| miffan | `Settings.init` 语义反转照抄守卫 → 拿 dummy providers 发车；Compose 输入框不聚合 PROCESS_TEXT，勿用自家 App 判入口缺失 |
| billion-context | 「5×」是反事实——证据须标 [V]/[A] 并钉 sha |
| billion-context（2） | v6 物化渲染面 11-04 落地 → 11-05 两次方向反转 → 11-07 整条 revert（HEAD 已无 `fastpath-v6*.ts`）：**物化渲染/身份面不借**，只留「只缓存 post-rewrite core，映射每轮现算」 |
| BiliPai（2） | 材质配方与采样源**同批改** → 当日互为逆提交整体回滚：同批改无法二分归因（判据见 §F） |
| yuihub | 复用 Conversation 表做子代理 = 静默泄漏 |
| sillytavern | 默认空 map + ignoreUnknownKeys = 任意 JSON 静默导入「0 条目」；remember 缓存闭包捕获 dummy → 传回调读取器（曾 14 条覆盖成 1 条） |
| purrcat | 文档先于实现（trigger 无消费方）→ 评估必须源码核验；「Docker 隔离」实为 SYS_ADMIN+seccomp=unconfined 非安全边界；iframe 无 sandbox 同源可取 parent.document |
| NMoi | 「写了没接线」前科模式（已收敛，残留 2 处）；容器 netshim 代理被证伪（bionic/glibc 不兼容）；per-conversation workspace 当天回退——老数据默认留共享、迁移须增量可回退 |
| BiliPai | 硬件位图坑族（判据见 §F，已转正）；敢全 App 铺玻璃是因为**先有门控层和测试才铺的，顺序不能反** |
| upstream | cherry-pick 硬阻塞族：宿主不同构不可移植（db4982283 TTS/eb530b0ff 公式走上游新 MarkdownParser；c9a159a5c DB 迁移撞号+回归存量）；本仓常已有更强修复（9cb72482f 双防线 vs 4af510624）——判定前先搜本仓历史 |

## 四、当前挂账快照（2026-11-08）

- upstream 同步点 `fe5a28f9a`（3.1.2）；本地提交普遍未推送（upstream 侧 ≈1419+）；**推送/发版等授权**。
- upstream 挂账：任务完成主动通知模型（Notice/收件箱协议，唯一实质缺口）· #2271 Mistral 422 同构风险 · #2304 SSH workspace 待复现 · `ae599e8d7` 磁盘编码缓存增强。
- peer 候选池见 §五；C1–C6 已在后续本地提交落地，C7 与备份自校验本轮（2026-11-08）已实现；内容级脱敏仍待语义边界拍板。
- BiliPai 门控层零改动；nmoi/BiliPai 本轮全部细节见 [nmoi-agent.md §13.4](references/other%20projects/nmoi-agent.md) / [bilipai.md](references/other%20projects/bilipai.md)。
- **2026-11-10 同步**：upstream 同步点 `8a837b27e`（issue 扫描到 #2310）；peer 侧 NMoi 到 `92585e8`、BiliPai 到 `05c4e2ac5`、PurrCat 到 `7cd7134`、Miffan 远端 `11b8fb55b`、billion-context `4dea18ea`、dsh-Go `8f31b2e`；仍然只评不施。**新增两个本仓实证项**：CI 零测试门禁（purrcat F1）、3 个 .so 未 16 KB 页对齐（dsh-Go 引出的静态核验）。候选池见 §六。

## 五、本轮增量判决（2026-11-08：NMoi 173 提交 + BiliPai 108 提交）

总判决：**吸收 0 / 候选 7 / 其余记账或等价**。候选均未实施，按建议优先级：

| # | 来源 | 项 | 判决 | 依据（一行） | 落点 | 量级 |
|---|---|---|---|---|---|---|
| C1 | NMoi D248/269/270/272 | 上下文图片清单 + 淘汰 + View-Image 工具 | **P1** | 本仓历史图片无任何上下文管理，附件随消息永久重发 | 图片清单挂请求组装（input transformer）+ view_image 挂工具生态 | ~2 天 |
| C2 | NMoi D254 | 工具调用按车道并行 | **P1** | 本仓 `GenerationHandler.kt:804` 串行 forEach；判据见 §C | GenerationHandler 调度，注意与审批流交互 | ~1 天 |
| C3 | NMoi D185 | 任务锚点 | **P2** | 本仓 `CompressPrompt.kt` 无此字段；判据见 §A | CompressPrompt | ~0.5 天 |
| C4 | NMoi 65e13bf/D260 | DB 迁移前 .bak 快照 + 迁移幂等 | **P2** | 本仓降级有隔离重建、升级路径无快照；判据见 §E | Room 迁移包装层 | ~0.5 天 |
| C5 | BiliPai | 动效令牌收口 | **P3** | 本仓零中央动效令牌；判据见 §F | `ui/components/motion/MotionTokens.kt` | ~0.5 天 |
| C6 | BiliPai 0125561e6 | TextFieldState 单向同步纪律 | **P3**（规范件非代码） | 判据见 §F；本仓历史坑「丢字符/整对象写丢更新」同族病 | AGENTS.md TextField 规范扩展 | ~0.5 天 |
| C7 | NMoi 4a7d465 | 发图压缩 + 附件尺寸上限 | **已实现（2026-11-08）** | 纠正旧盘点：原已有 JPEG/采样；本轮收紧像素与字节预算，补附件流式上限与显式报错 | FileEncoder / FilesManager / providers | 见实施记录 |

**修正既有结论**：NMoi「stage 0」停用（原三大未动工项已落地，残留接线 2 处）；§13.1⑤ D46 彻底改判（「工具循环一律非流式」作废）；11-03 的「0.15ap/0.15aq/0.15an 未动工」记账作废。**已有等价勿立项**：世界书核心语义（**本仓是 NMoi 字段超集**——二次收窄/token 预算/多册绑定/ST 导入均我们有它没有，世界书不用跟）、只换一条 regen 与失败三选一（MessageNode 树天然支持）、六角色分模型（本仓 9 角色字段）、代理栈（本仓 per-provider 更细）、「正在生成」卡死修（本仓已根治）。

## 六、2026-11-10 同步轮判决（上游 16 提交 + 8 个来源）

> 范围：上游 `fe5a28f9a..8a837b27e`（16 提交）+ issue #2305–#2310；NMoi `d7fe1b0..92585e8`（32）；BiliPai `f87566b86..05c4e2ac5`（35）；PurrCat `adbba56..7cd7134`（61）；Miffan 远端 11-03→11-09（141）；bigeye 1；billion-context 11-03→11-10（671，抽样）；dsh-Go `cf2f7c86..8f31b2e`（542，抽样）；archify 3。Yuihub / senera / rikkahub-agent / agentd / SillyTavern 已核对为无新提交（senera 实际仓库是 `yuanplussfive/senera`，台账 URL 待改）。
> 本轮**只评不施**：未改任何源码、未提交。总判决：**吸收 0 / 候选 32（P2×11、P3×21）/ 其余已有等价或判据记账**。逐条明细回查各台账末节（[upstream-analysis.md](upstream-analysis.md)、[nmoi-agent.md §13.5](references/other%20projects/nmoi-agent.md)、[purrcat-analysis.md 附录 F](purrcat-analysis.md)、[miffan.md](references/other%20projects/miffan.md)、[bilipai.md](references/other%20projects/bilipai.md)、[bigeye.md §11.7](references/other%20projects/bigeye.md)、[billion-context.md](references/other%20projects/billion-context.md)、[dsh-go.md §七](references/other%20projects/dsh-go.md)、[archify.md](references/other%20projects/archify.md)）。

### 6.1 P2 候选池（11 条，按建议优先级）

| # | 来源 | 项 | 本仓落点 | 量级 |
|---|---|---|---|---|
| 1 | PurrCat `6a95539`+`bb33169` | **CI 门禁接线**：382 单测 + 100 androidTest + 9 个 audit/verify 脚本，CI 只 `assembleRelease`、零测试门禁（`daily-build.yml:132-133`、`release.yml:115-116`） | `.github/workflows/` | 0.5–1 天 |
| 2 | upstream `bcc649abc` | 一轮中途自动压缩（pause + 检查点 + 续跑提示）；本仓只有轮前压缩 + 工具循环优雅停止 | `GenerationHandler.kt:476` + ChatService 压缩线 | 1–2 天 |
| 3 | upstream `5b7ca774b` | 概览卡全文 `toText()` 交 `Text(maxLines=1)` 主线程测量 → 截 300 + 后台/conflate | `ChatList.kt:981/992` | 0.5 天 |
| 4 | upstream `921427f95` | web/App 输入 token 取最后一次 usage（旧账：`lastUsage`/`contextTokens` 从未落地） | `ai/ui/Message.kt:42` + `WebDto.kt:215` | 1 天 |
| 5 | upstream `e1667163c` | Claude 流式签名 delta 累加 + 非流式相邻 thinking 同签名才合并 | `ClaudeStreamDecoder.kt:164` + `ui/Message.kt:121` | 0.5–1 天 |
| 6 | NMoi `1dfda3c`（D296） | 压缩摘要机械携带本段工具/文件痕迹（12 条/600 字封顶，预算不够整节不接） | `ChatService.kt:2195` + `Message.kt:222-231` | 1–1.5 天 |
| 7 | billion-context `d126087ab` | 折叠区媒体句柄（URL/fileId/fileUri）机械随摘要携带，折叠后仍可回取 | 摘要输入 + 图片淘汰链 | 0.5 天 |
| 8 | Miffan `6efec385a` | `stripMarkdown()` 保留行内代码文本（现整段删 → TTS/VoiceMode 丢 code） | `MarkdownUtils.kt:48` | 0.5 小时 |
| 9 | Miffan `7b602f808`+`9f58005b9` | QuickJS 初始化移出启动主线程 + 使用点幂等 init | `RikkaHubApp.kt:148` | 0.5 天 |
| 10 | Miffan `7442ea738`×10 | 通用 Compose macrobenchmark 管线（fixture + 旅程 + runner + 汇总 + 全绘上报） | `app/baselineprofile` | 2–3 天 |
| 11 | dsh-Go（16 KB 适配引出） | **3 个 .so 4 KB 页对齐**（`libtex_poc` / `libpdf2svg_bridge` / `libgraphite2`）→ 16 KB 页设备 TikZ/PDF→SVG 链断 | `app/src/main/jniLibs/arm64-v8a/` | 0.5–1 天（需设备验证） |

### 6.2 P3 候选（21 条）

- **upstream（6）**：通知权限卡（~1h）· 信任用户 CA（需拍板）· 500k token 调试对话（~1h）· 压缩请求组装搬 IO（~0.5h）· 默认提供商全开（需拍板）· 地图搜索词变化回顶（一行）。
- **NMoi（4）**：世界书 4 个触发时序字段（`matchWholeWords`/`sticky`/`cooldown`/`delay`，~1d）· 世界书两级多选/批量导出（~1d）· 手动压缩切点预览（~0.5d）· 会话信息面板（~0.5d）。
- **BiliPai（5）**：弹窗/玻璃菜单预测返回（~1d）· 图片预览交接（~1d）· 规则插件编辑实时校验+试跑（~1d）· 视口可见性动效门控（0.5–1d）· 剪贴板读取兜底（~30m）。
- **PurrCat（2）**：测试卫生规范件（~0.5d）· 巨型文件拆分**观察期**（`ChatService.kt` 3262 行 / `GenerationHandler.kt` 1722 行，触发后 3–5d）。
- **Miffan（5）**：引用卡去 Markdown 标记（~0.5h）· 失败提示人话化（10 处直出 `e.message`，~1d）· 月日本地化 + 两个 Compose 小件（~0.5d）· 会话列表行 `contentType`（~0.5h）· DB 复合索引 + 收藏范围查询（~0.5d）。
- **bigeye（1）**：思考档位被 `mergeCustomBody` 静默覆盖 → 显式提示（~1h）。

### 6.3 本轮同构 bug / 风险（已在档，需拍板或单独立项）

- **#2306 多图上传跳过图片编辑（同构 bug 确认）**：本仓 `ChatFilesPickerSheet.kt:125` 仅 `selectedUris.size == 1` 才裁剪，多图直接跳过用户裁剪；上游也未修。修法 = 逐张裁剪队列（上游建议）。
- **3 个 .so 4 KB 对齐**：静态核验（未真机）；`useLegacyPackaging=true`、targetSdk 37，16 KB 页设备上三库无法加载。
- **Claude 签名残件**、**web/App 统计行仍显累计输入**、**`stripMarkdown` 删行内代码**：见 6.1 #5/#4/#8，均属「本仓自己也有」的旧账。
- **#2271 Mistral 422** 仍 open；**#2303 备份**、**#2305 提示词插入语义**（本仓已是追加语义）、**#2309 think 标签编辑后读不到**（待核，无关联 commit）为需求线/待核，不立项。

### 6.4 本轮修正的既有结论

- **NMoi §13.4 收窄**：本仓世界书仍是主键/次键/四逻辑/预算/扫描源/大小写/正则的超集，但**触发时序维度落后**（4 字段）；「NMoi 只有自有格式、ST 导入仅计划稿」作废（D309 已落且修真 bug）。
- **PurrCat**：附录七「`PARADIGM trigger` 无消费方」——上游 11-09 已自行删除该字段（`cb00658`），后续不再引用。
- **billion-context**：v6 物化渲染面整条 revert（反向证据进 §三）；既有「追加式布局/不可变头/breakeven/增量触发门」判据未被打翻。
- **dsh-Go**：架构结论不变，新增 16 KB 候选与 5 条账目（引擎更新保留用户环境、安装回执契约、命令通道权限边界、IME 证据纪律、未接收输入顺序恢复）。
- **NMoi「多 Key 领先」** 维持但补现状：`LruKeyRoulette` 仅搜索线接线，聊天侧全顺序模式（按请求轮询是可选策略非缺口）。

### 6.5 下一批建议（未获授权不实施）

- **P2 批次 A（~4–6 天，风险低收益高）**：6.1 #1 CI 门禁 + #8 MarkdownUtils + #9 QuickJS + #3 概览卡 + #5 Claude 签名；可一次落地并跑现有 382 单测。
- **P2 批次 B（需设备/拍板）**：#11 16 KB 重编（先模拟器验证）+ #2 轮中压缩 + #4 lastUsage + #6/#7 摘要媒体/工具清单。
- **拍板项**：信任用户 CA（降 MITM 防护）、默认提供商全开（首启 UX）、#2306 多图裁剪、通用 macrobenchmark 管线（2–3 天，需真机基线）。

### 6.6 2026-11-10 第二轮：落地执行（本地提交，未推送）

用户对上一轮「需拍板」项逐条答复后，本轮从「只评不施」转为实施。范围严格限定为
**信任用户 CA + #2306 多图裁剪 + P2 批次 A**；16 KB .so 重编、`bcc649abc` 轮中压缩、
`921427f95` lastUsage、`9bc0fd658` 默认提供商全开、macrobenchmark 管线均未动。

| 项 | 状态 | 落点 |
|---|---|---|
| 6.1 #1 CI 门禁 | **已落地** | `daily-build.yml` / `release.yml` 构建前新增「静态审计与单元测试」步：`audit_ime_trap.py` + `audit_app_skin.py` + `./gradlew test` |
| 6.1 #8 MarkdownUtils 行内代码 | **已落地** | 围栏块仍删、行内代码保留文本；并加私有区占位符保护，避免 `my_var_name`/`a*b` 被后续 `*`/`_` 规则二次清洗（`MarkdownUtilsTest` 10 例） |
| 6.1 #9 QuickJS 初始化 | **已落地** | `RikkaHubApp` 主线程 init 删除 → `postReadyInit` 后台预热；`JavascriptTool` 增 `QuickJsInit` 双检锁 + 工作线程兜底；`search/CustomJsSearchService` 使用点补 `QuickJSLoader.init()`（模块依赖方向无法共用 app 侧闸） |
| 6.1 #3 概览卡 | **已落地**（判据等价，结构不同） | 本仓概览是按节点气泡列表（无 `PreviewRound`/`toPreviewRounds`）：搜索过滤挪 `Dispatchers.Default + snapshotFlow.conflate()`、交给 `Text` 的串截 300 字符，匹配仍用全文；上游「按轮清洗」在本仓无对应物 |
| 6.1 #5 Claude 签名残件 | **已落地** | `signature_delta` 累加；流式 `redacted_thinking` 保存 `data`；`Message.mergeDelta` 非流式相邻 Reasoning 不再合并（流式按 block id 聚合本就正确）；新增 `ClaudeThinkingReplayTest`（5 例，断言下一次请求体） |
| 6.3 #2306 多图裁剪 | **已落地** | 新增 `useMultiCropLauncher` 逐张裁剪队列：每张确认即写成临时 JPEG 交调用方并删临时文件；中途取消时多图剩余原样加入（不丢已选）、单图取消仍不加、camera 路径不变 |
| 拍板：信任用户 CA | **已落地** | 新增 `app/src/main/res/xml/network_security_config.xml`（系统 + 用户 CA），manifest 挂载；`base-config cleartextTrafficPermitted="true"` 显式保留明文许可（声明后 manifest 的 `usesCleartextTraffic` 被忽略） |
| 6.1 #4 / #2 / #6 / #7 / #10 / #11 | 未动 | 仍待拍板或需设备验证 |
| 9bc0fd658 默认提供商全开 | **放弃** | 用户 2026-11-10 决定不采纳（首启 UX 策略，本仓出厂列表已自主分叉） |
| 6.1 #11 3 个 .so 16 KB 页对齐 | **暂缓（不做）** | 用户 2026-11-10 决定暂不实施；设备要求已核实并记入 dsh-go §7.1，随时可重启 |

验证：`:ai:compileDebugKotlin`、`:search:compileDebugKotlin`、`:app:compileDebugKotlin` 通过；
`:ai:testDebugUnitTest`、`:app:testDebugUnitTest` 通过；两条 audit 脚本 exit 0（IME 审计仅剩已存档告警）。

### 6.7 2026-11-10 第三轮：剩余候选落地（本地提交，未推送）

用户指定「继续」上一轮列出的剩余项：`bcc649abc` 轮中压缩、`921427f95` lastUsage、
摘要媒体/工具痕迹、macrobenchmark、P3 小件。本轮全部落地为本地提交（`b6488321e`、
`a2e9b3ef2`、`1248cf6af`、`19facb031`、`5c93457aa`），范围之外未动。

| 项 | 状态 | 落点 |
|---|---|---|
| 6.1 #4 lastUsage（#2307） | **已落地**（实际增量小于上游预期） | 本仓 `TokenUsage.accumulateRound()`（`06c506bde`）**已让 prompt/cached 取最新一步**、只对 completion 求和，上游 921427f95 的前提（`usage.sum()` 全字段累加）在本仓不存在。真正增量：① `UIMessage.lastUsage` 记录本步自己的 usage；② Web 补 `stepCount` + 步数徽标 + 累计输入 hover（Web 之前无步数徽标）；③ DTO/types/locales 与上游对齐；④ ai 序列化兼容测试 |
| 6.1 #6/#7 摘要痕迹 | **已落地** | `compression/CompressionTraces.kt`：确定性附录（工具名 ≤8、沙箱路径/文档/媒体句柄，12 条 / 600 字符 / 单句柄 ≤160；`data:` 只留 mime）；预算 > `targetTokens×0.25` 整节不接；注入模型的 prior 先剥旧附录、接力合并走代码；追加在覆盖率闸之后（闸分母不受污染）。20 例测试 |
| 6.1 #2 轮中压缩（`bcc649abc`） | **已落地**（适配本仓 GenerationHandler） | 工具批全部回填后回调暂停：压缩成功 → 检查点插当前回复之后、新开回复续写；失败报错继续原回复、同回复不重试；压缩后仍超阈值 → 本轮不再压缩（判据顺序阈值先于防抖）；重生成（`messageRange != null`）禁用；检查点后非用户消息加续跑提示（不落库）。占用取 `max(usage 锚点, 窗口本地估算+overhead)`；`compressToCheckpoint` 用 live 快照 + `NonCancellable` 提交。25 例测试 |
| 6.1 #10 macrobenchmark | **已落地（代码；未运行验证）** | `app/baselineprofile` 补齐管线：`PerfConfig`/`PerfMetrics`/`BenchmarkRunner`/`JourneyBenchmarks`（冷启动→聊天 / 长对话滚动 / 发送）+ fixtures 契约 + `perf/run_bench.sh` + `summarize.py` + README；Gradle 未改 |
| P3 地图搜索词变化回顶 | **已落地** | `ChatList` 加 `rememberLazyListState` + `LaunchedEffect(searchQuery)`（上游 5863c4e4c 残件） |
| P3 500k token 调试对话 | **已落地** | `DebugVM.createConversationWithTokens`（按 `estimateTokens` 累加、固定语料，供压缩/预算回归）+ 调试页按钮 + 三语 strings（上游 1c93ca72c 等价物） |
| P3 引用卡去 Markdown 标记 | **已落地** | `MessageQuoteCard` 展示层 `stripMarkdown()`，快照仍存原文（Miffan 9531ec767） |
| P3 会话列表行 `contentType` | **已落地** | `ConversationList` 的 `items` 补 `contentType`（日期头/置顶头/会话行分池，Miffan c558e5840 族） |
| 通知权限卡、压缩请求组装搬 IO、世界书时序字段、BiliPai/PurrCat/Miffan 其余项、C5/C6 规范件 | 未动 | 仍为 P3/观察期候选 |

验证：`:ai:compileDebugKotlin`、`:app:compileDebugKotlin` 通过（`-x :web:buildWebUi`，原因见下）；
`:app:testDebugUnitTest` 3344 例、`:ai:testDebugUnitTest` 527 例全绿；两条 audit 脚本 exit 0。

**两项未验证（已在交付说明中标明）**：
1. **web-ui 未构建/未类型检查**：本机 `web-ui/node_modules` 缺失、`pnpm` 不在 PATH，`pnpm install --offline`
   缺 `@babel/core` 元数据失败 → `:web:buildWebUi` 跑不了。Kotlin 侧与 JSON/类型改动只做了静态核对；
   打 APK 前需在有网环境跑 `pnpm -C web-ui build`（`pnpm -C web-ui typecheck` 更稳）。
2. **`:app:baselineprofile` 未编译**：`androidx.benchmark:benchmark-macro-junit4:1.4.1`、
   `androidx.test.uiautomator:2.4.0` 本机 Gradle 缓存没有，`--offline` 解析失败；且无设备 ⇒
   journey 指标未运行（README 已写「未运行验证」）。首次联网构建时需确认 API 兼容。

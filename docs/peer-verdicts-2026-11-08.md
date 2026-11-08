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

### B. 压缩与读回
- 压缩按节点 ID **不删原文只换视图**；压缩后读回以 CompactionCheckpoint 指纹、编辑消息自动失效（rikkahub-agent §二.3 / yuihub §四.7）。
- 世界书注入带 SourceTag 进档位白名单不降级（NMoi session.rs:536）（2026-11-08 新）。

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

### E. 存储与备份
- **迁移前留 `.v<旧版本>.bak` 副本 + 迁移函数可重执行**（NMoi 65e13bf/D260 duplicate column 真机教训）（2026-11-08 候选 C4）。
- 备份发布前自校验：VACUUM INTO 快照上跑 `integrity_check` + `foreign_key_check`（integrity_check 不查 FK）通过才发布（NMoi backup.rs，~0.5 天待办）。
- 备份 manifest + 双 SHA-256 + **staged 换库启动回滚**（推迟 startKoin 化解检测难题）（yuihub §四.8）。
- WAL 取证：主库缺失而 -wal/-shm 在 = 同步撕裂拒开；0 字节主库 + WAL 在 = 拒开（SQLite 会静默丢 WAL）——**启动路径**要查，不只恢复路径（NMoi L0）。
- U+FFFD 拒写二进制；裸 EOF 标 finishIncomplete 不当正常结束（yuihub §四.8/§四.5）。
- Room `fallbackToDestructiveMigrationOnDowngrade(true)`；sealed @Serializable 子类须有直接代码引用防 R8 删（huadeng §八.1）。
- 安装/更新事务化：SHA-256 校验 + staging 原子切换留旧环境；签名是更新最后防线（dsh-go §2.3）。
- 附件落**会话私有目录**、删会话清目录；库只存路径 BLOB 懒迁移（NMoi D216）。

### F. UI / 玻璃 / 动效 / 输入
- 液态玻璃门控层三件（SDK 分级 / 前后台 gate / hazeSourceCompat）+ 可读性一等设置——**✅ 已落地 2026-10-31**；ReduceMotion + VisualWorkPolicy——**✅ 已落地 `3254e33b0`**（bilipai.md）。
- **TextFieldState 单向同步纪律**：编辑态唯一权威是 TextFieldState / VM 字段只做投影 / 程序化写入先比较再 `setTextAndPlaceCursorAtEnd` / 绝不向活跃 IME 会话回写（BiliPai SearchScreen.kt:699）（2026-11-08 候选 C6，与 AGENTS.md TextField Host Rules 互补：宿主拓扑 vs 状态流向）。
- **动效令牌收口**：object + @Composable accessor + 命名时长/弹簧族（BiliPai AppMotionTokens.kt:85-189；本仓全仓零中央令牌）（2026-11-08 候选 C5）。
- 玻璃预设 = progress 三锚点曲线 + 一键预设**原子提交**（材质+高级参数同提交）防半套配方（BiliPai LiquidGlassTuning.kt）（2026-11-08 新）。
- **渐进模糊首帧闪烁**：恒定半径模糊只靠 layerAlpha 淡入时来源页不先 drawContent → 开场透出窗口底色；修复 = 锐利原层按 (1-blurAlpha) 垫底参与混合（BiliPai ab16ad9c4）——对任何 haze 转场最有价值（2026-11-08 新）。
- 转场位图坑族：HARDWARE 位图进软件 Canvas 直接 IAE → 建同配置 HARDWARE 目标+硬件画布；可变位图反向 PixelCopy 落软件（重载 API29+、SAM 推断失败 → `source.copy(ARGB_8888,false)`）；透明位图先 drawColor 底色；失败一律显式降级不硬崩（BiliPai SystemVideoCardTransition.kt）（2026-11-08 新）。
- MaterialBackdropBlur 磨砂参数：`downscaleFactor=1`、sigma=radius×0.45、padding=3σ，防大半径降采样滚动闪烁（BiliPai）（2026-11-08 新）。
- 跨应用划词翻译零权限正解 = `ACTION_PROCESS_TEXT` 对话框 Activity——**✅ 已落地**（miffan.md）。

### G. 模型与生成
- 显式 `tool_choice:"auto"`（tools 非空必发）+ 零字流退回非流式重发一次（已有正文不重发）——**✅ 第一件已落地 `064a8b772`**（NMoi e3cbed8 / ddd2860，bigeye llm.py:983 同证）。
- usage 归一：Anthropic input_tokens 不含 cache 需加 cache_read+cache_creation；Gemini usageMetadata 用 max 合并；message_start 的 cache 计数不可信（NMoi 判据表）。
- 小模型结构化决策判不出 → **{Ok/Uncertain/Failed} 三态 + Uncertain 静默回退**要显式声明（huadeng §八.2）。
- 真实 0 与未知占位区分（BiliPai FormatUtils）。
- 记忆注入 = 打分校准 + **token 预算**（字符预算不可照搬：1800 字符砍 54→7 条）——**✅ 已落地 `91d88551`**（yuihub §4.2）。

### H. 评估与流程方法论
- 已吸收判定用**代码等价核对**，禁用 merge-base 判祖先（手工直套恒误报）；「判已被上游推翻」才是 merge-base 合法用法（upstream §注意点 1/12）。
- 吸收前必查上游是否自己 revert 过该 fix（`d3a53e0a` 事故）；批次「已吸收核心件」≠逐提交已核，重改文件上的 fix 须逐条 grep（`aac6e963` 漏吸收）。
- fetch 区间 ≠ 欠账量：欠账看文档同步点不看 ref；评估表预判 ≠ 动手前实核（11-07 批四条候选实核改判等价）。
- 补丁不可照搬时先找本仓同源写法——**吸收的是教训不是类**；护栏/熔断必须 fail-open；带签名模型产出块不得改写回传。
- 三档证据不混用：源码 / 远端 / 抽样，二手须显式标注；**评估缺点比优点有用**；自述 ≠ 事实先核验再记账，凡要移植的先 grep 消费方；同名不同物拆开算（PB §4.2）。
- 「不做」清单本身就是资产（PB §4.3 含 10 条已明确不做方向）。
- 大改挂「观察期触发点」：语义接受 / 实测症状出现才升级，不预先动手。

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
| yuihub | 复用 Conversation 表做子代理 = 静默泄漏 |
| sillytavern | 默认空 map + ignoreUnknownKeys = 任意 JSON 静默导入「0 条目」；remember 缓存闭包捕获 dummy → 传回调读取器（曾 14 条覆盖成 1 条） |
| purrcat | 文档先于实现（trigger 无消费方）→ 评估必须源码核验；「Docker 隔离」实为 SYS_ADMIN+seccomp=unconfined 非安全边界；iframe 无 sandbox 同源可取 parent.document |
| NMoi | 「写了没接线」前科模式（已收敛，残留 2 处）；容器 netshim 代理被证伪（bionic/glibc 不兼容）；per-conversation workspace 当天回退——老数据默认留共享、迁移须增量可回退 |
| BiliPai | 硬件位图坑族（判据见 §F，已转正）；敢全 App 铺玻璃是因为**先有门控层和测试才铺的，顺序不能反** |
| upstream | cherry-pick 硬阻塞族：宿主不同构不可移植（db4982283 TTS/eb530b0ff 公式走上游新 MarkdownParser；c9a159a5c DB 迁移撞号+回归存量）；本仓常已有更强修复（9cb72482f 双防线 vs 4af510624）——判定前先搜本仓历史 |

## 四、当前挂账快照（2026-11-08）

- upstream 同步点 `fe5a28f9a`（3.1.2）；本地提交普遍未推送（upstream 侧 ≈1419+）；**推送/发版等授权**。
- upstream 挂账：任务完成主动通知模型（Notice/收件箱协议，唯一实质缺口）· #2271 Mistral 422 同构风险 · #2304 SSH workspace 待复现 · `ae599e8d7` 磁盘编码缓存增强。
- peer 候选池见 §五（C1–C7 未拍板）；NMoi 待办：备份自校验 ~0.5 天、内容级脱敏（语义边界待拍板）。
- BiliPai 门控层零改动；nmoi/BiliPai 本轮全部细节见 [nmoi-agent.md §13.4](references/other%20projects/nmoi-agent.md) / [bilipai.md](references/other%20projects/bilipai.md)。

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
| C7 | NMoi 4a7d465 | 发图压缩 + 附件尺寸上限 | **P3** | 本仓发送侧无位图压缩；可与 C1 同批 | 发送路径 | ~0.5 天 |

**修正既有结论**：NMoi「stage 0」停用（原三大未动工项已落地，残留接线 2 处）；§13.1⑤ D46 彻底改判（「工具循环一律非流式」作废）；11-03 的「0.15ap/0.15aq/0.15an 未动工」记账作废。**已有等价勿立项**：世界书核心语义（**本仓是 NMoi 字段超集**——二次收窄/token 预算/多册绑定/ST 导入均我们有它没有，世界书不用跟）、只换一条 regen 与失败三选一（MessageNode 树天然支持）、六角色分模型（本仓 9 角色字段）、代理栈（本仓 per-provider 更细）、「正在生成」卡死修（本仓已根治）。

# 借鉴项目增量评估判决书（2026-11-08）

> 本轮覆盖两家：NMoi-Agent（`1dd1a24..d7fe1b0`，173 提交，D185–D289）与 BiliPai（`7fec7694..f87566b86`，108 提交）。
> 证据与逐簇剖析见台账：[nmoi-agent.md §13.4](references/other%20projects/nmoi-agent.md)、[bilipai.md 增量节](references/other%20projects/bilipai.md)。
> 总判决：**吸收 0 / 候选 7 / 判据记账 14 / 已有等价 12+ / 不适用其余**。所有候选**均未实施**，等你拍板跟哪条。

## 一、候选池（按建议优先级排序）

| # | 来源 | 项 | 判决 | 依据（一行） | 落点 | 量级 |
|---|---|---|---|---|---|---|
| C1 | nmoi D248/269/270/272 | **上下文图片清单 + 淘汰 + View-Image 工具** | 候选 P1 | 本仓历史图片无任何上下文管理，附件随消息永久重发；NMoi 清单默认 20/硬顶 100、新图置前丢最旧、临时额度活到下次思考结束、模型可经工具把图拉回 | 请求组装处挂图片清单（input transformer）+ 工具生态挂 view_image | ~2 天 |
| C2 | nmoi D254 | **工具调用按车道并行** | 候选 P1 | 本仓 `GenerationHandler.kt:804` 对 tool_calls 串行 forEach；判据 = Read(4)/Workspace(1)/Process(4) 车道、未声明默认串行防写冲突、结果按调用下标回填 | GenerationHandler 工具执行调度 | ~1 天（注意与审批流交互） |
| C3 | nmoi D185 | **任务锚点**（压缩摘要尾附原样复述） | 候选 P2（11-03 挂账） | 本仓 `CompressPrompt.kt` 无此字段；判据不变 = 从最后一条 user 消息原样截取、不概括、不新增消息防 last_user 指错 | CompressPrompt | ~0.5 天 |
| C4 | nmoi 65e13bf/D260 | **DB 迁移前 .bak 快照 + 迁移幂等** | 候选 P2 | 本仓降级有隔离重建（beta.14）但升级路径无迁移前快照；判据 = `<库>.v<旧版本>.bak` + 迁移函数可重执行（duplicate column 真机教训） | Room 迁移包装层 | ~0.5 天 |
| C5 | BiliPai d271384dc 批次 | **动效令牌收口** | 候选 P3 | 本仓 grep 核实全仓零中央动效令牌（无命名 easing/时长，`tween(` 散落 8+ 组件）；抄形状（object + @Composable accessor + 命名时长/弹簧族）不抄双风格分支 | `ui/components/motion/MotionTokens.kt` 新建 | ~0.5 天 |
| C6 | BiliPai 0125561e6 | **TextFieldState 单向同步纪律** | 候选 P3（规范件，非代码） | 判据四条：编辑态唯一权威是 TextFieldState / VM 字段只做投影 / 程序化写入先比较再 `setTextAndPlaceCursorAtEnd` / 绝不向活跃 IME 会话回写。与本仓 AGENTS.md「TextField Host Rules」互补（那是宿主拓扑，这是状态流向） | AGENTS.md TextField 规范扩展 | ~0.5 天（含审计脚本） |
| C7 | nmoi 4a7d465 | **发图压缩 + 附件尺寸上限** | 候选 P3 | 本仓发送侧无位图压缩代码；可与 C1 同批做 | 发送路径 | ~0.5 天 |

## 二、判据记账（不立项，备查）

**来自 nmoi：**

1. **压缩触发线数据点**：NMoi 真机迭代把建议线/绝对线默认从 0.75/0.92 下调到 **0.45/0.55**、默认窗口 128k→1M（`budget.rs:313/721`）。本仓 ladder 触发默认 80%——方向相反的数据点，**等净化观察期双口径数据回来一起判**，不单独动默认值。
2. **edit_message 工具安全边界四件套**：只排队轮收尾统一落库 / 锁定消息拒绝 / 只读工作区不声明 / 改写落红。本仓无「模型改历史」能力，暂无需求场景。
3. **随包预设四态状态机**：Follow/LocalEdited/UpstreamNewer/Conflict，判据 = 本地摘要 vs base_hash、上游 version/hash（非时间戳）；内置册 = 虚拟视图不复制数据、「恢复默认」=逐条 follow、补缺不删。将来做随包提示词/预设内容更新时直接用。
4. **世界书命中说明 UI**：NMoi 的 `summarize`/`matched_keyword` 已导出未接线（又一个「写了没接线」）；本仓若做 lorebook 命中调试面板，直接实现「上下文面板显示每条命中关键词」。
5. **世界书扫描窗口含 assistant 消息与思考文本**：本仓「非 SYSTEM 消息」扫描是否含思考文本待核，含则无需动。

**来自 BiliPai：**

6. **渐进模糊首帧闪烁修复**（`ab16ad9c4`）：恒定半径模糊只靠 layerAlpha 淡入时来源页不先 drawContent → 开场 alpha≈0 透出窗口底色；修复 = 锐利原层按 (1-blurAlpha) 垫底参与混合（两层均录 GPU 层）。**对将来任何 haze 转场最有价值的一条。**
7. **转场位图坑族**（对任何做位图快照的 App 通用）：HARDWARE 位图进软件 Canvas 直接 IAE → 建同配置 HARDWARE 目标+硬件画布；可变位图建不了 HARDWARE → 反向 PixelCopy 落软件（PixelCopy 位图重载 API29+ 且 SAM 推断失败 → `source.copy(ARGB_8888,false)`）；透明位图先 drawColor 来源页底色再 drawBitmap；失败一律 `rejected("source_pixels_unavailable")` 显式降级，不硬崩。
8. **玻璃预设 progress 三锚点曲线 + 一键预设原子提交**（`LiquidGlassTuning.kt:14-40/107-124`）：20+ 参数收成 progress 曲线锚位而非各存各的预设值；一键预设同时提交材质 progress + 高级参数，防「半套配方」。将来做玻璃调参面板直接用。
9. **MaterialBackdropBlur 磨砂参数**：`downscaleFactor=1`、sigma=radius×0.45、padding=3σ，防大半径降采样放大滚动闪烁。ChatList 磨砂出现滚动闪烁时对照。
10. **KILL 前快照结构**：厂商广播不可移植，但结构通用——终止前 2.5s 预算内（goAsync+FLAG_ONEWAY）只采「有 windowFocus 的 activity + 滚动锚点 + 断点」；快照带 processSessionId/账号 mid 校验防串号、consume 一次性消费、恢复前先清旧请求态。
11. **场景选择与总开关解耦 + 迁移位**：场景位掩码独立保存，总开关关闭不清空场景选择，`SPLIT_SCENES_SAVED` 迁移位防旧值覆盖。
12. **FirstFrameTracker**：首帧归属 player 而非页面（WeakHashMap+StateFlow，mediaItemTransition 非 REPEAT/STATE_IDLE 复位）。做附件媒体预览时可迁移。
13. **FormatUtils 收口**：数量/相对时间格式化单源、真实 0 与未知占位区分。将来 i18n 盘点时顺手对照。
14. **磨砂 fallback 形状**：关玻璃后底栏 fallback 由壳层 Modifier 画磨砂、内部 Transparent。本仓可读性模式已等价，仅记形状。

## 三、已有等价（勿重复立项）

**nmoi（12 条）**：世界书核心语义——**本仓是 NMoi 的字段超集**（本仓有 secondaryKeywords+selectiveLogic 二次收窄、`lorebookInjectBudgetTokens` 预算裁剪、`lorebookIds` 多册任意绑定、SillyTavern 内嵌卡导入；NMoi 均无）⇒ **世界书这条线不用跟**；只换一条 regen（D284）与失败三选一（D227/D228）= MessageNode 树天然支持；六角色分模型 = 本仓 Settings 已有 9 角色字段；代理栈 = 本仓全局+per-provider 更细；「正在生成」卡死修 = 本仓已根治（SubagentRunner）；流式/自动保存/会话绑工作区/消息=parts/附件私有目录 = 同形。

**BiliPai**：门控层核心本批零改动；磨砂 fallback 与场景控制粒度本仓已等价。

## 四、修正既有结论

- **nmoi 阶段词「stage 0」停用**：原三大「未动工」项（世界书/档位/车道并行）本期全部落地接线，功能面越过可用线；残留接线只剩两处（世界书命中说明 UI、reserve_fixed 未与世界书体积挂钩）。
- **§13.1⑤ D46 彻底改判**：「工具循环一律非流式」作废；正确结论 = 显式 `tool_choice:"auto"` + 零字流退回非流式重发一次后，流式可用（本仓 `064a8b772` 已落第一件）。
- **世界书/档位/并行三条旧记账失效**：11-03 的「0.15ap/0.15aq/0.15an 均标未动工」作废，引用注意。

## 五、本轮负面样本

- **NMoi 容器 netshim 已停**：bionic/glibc 不兼容，容器代理接线撤掉只留清理（D237）——透明代理思路在 Android 容器上被证伪。
- **NMoi D231–D233 per-conversation workspace 当天回退**（11-03 已记，本期确认维持）：迁进去等于把老文件藏起来，老数据默认留共享、迁移须增量可回退。

> 执行约定沿用 [[upstream-selective-absorption-policy]]：用户拍板跟哪条 → 实施 → 真机验证；本地提交可自主做，**推送/发版等明确授权**。

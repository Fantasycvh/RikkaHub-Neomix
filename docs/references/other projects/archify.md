# archify（tt-a1i/archify）借鉴评估

> 评估：**2026-10-16**（原 §十二，**仅远端元数据 + README 调研**）。许可证 MIT。
> 定位：**不是同类 app**，而是「会话内产出可视产物」这条线的成熟参考实现（同批 #2123 专项评估见 `docs/upstream-analysis.md`）。结论：**抄契约、不抄运行时**（Node CLI 在 Android 宿主上跑不了）。
> 返回 [索引](../../peer-apps-benchmark.md)。

## 十二、archify（tt-a1i/archify）评估（2026-10-16，仅远端元数据 + README 调研）

> 定位不是"同类 app"，而是本仓正在评估的「会话内产出可视产物」那条线上的**成熟参考实现**
> （同批的 #2123 专项评估见 `docs/upstream-analysis.md`）——所以放这里对照。

**事实**（GitHub API 实取，2026-10-16）：MIT；**84.1k stars / 5.7k forks / 234 watchers**；284 open issues；
仓库体积约 **712MB**；JavaScript；2026-04-15 建、**10-16 当天仍在推**；当前稳定版 **v3.1.0**
（changelog 2026-10-14）；站点 archify.si；宣称 GitHub Trending 周榜全语言第一（2026-09-01）。

**它是什么**：把一段描述/计划/代码库变成**一个自包含的交互式 HTML** 图表产物。10 种图
（Architecture / Workflow / Sequence / Data Flow / Lifecycle / ERD / Class / Tree / Timeline / Waterfall）。

**真正值得记的是它的工程契约，不是"能画图"**：

| 机制 | 内容 | 为什么值得记 |
|---|---|---|
| **typed JSON IR** | 每个渲染模式都有 schema + 可复现源 | 把"模型画图"从"祈祷它输出对"变成有类型的中间表示 |
| **交付前原子校验** | schema / layout / HTML-SVG / 路由 / 标签-路由间隙全过，候选才**替换**上一版好产物 | 「失败不留半成品」——与本仓检查点/压缩同款取向 |
| **失败带修复清单** | `--json` 返回稳定规则码 + `supportedFixes`，只允许按清单修 | 让模型**能自修**，而不是重掷 |
| **产物自包含** | 一个 HTML 文件，拿到就能开、能分享 | 交付面最小 |
| **明确排除** | 不解析 Mermaid、不做通用自动布局、不做托管分享、不做 WYSIWYG | 反面同样有用：它把自己框死在「沟通产物」上 |

**它目标宿主是 DSH，不是 RikkaHub**：README 安装表里有 **DeepSeek Harness** 一栏
`dsh plugin --profile web add @tt-a1i/archify-dsh@1.0.0`（面向 `@deepseek-ai/dsh@0.1.2-rc.1` 开发者预览；
要求 **Node `^22.19.0 || >=24`**；无遥测，更新检查可 `ARCHIFY_UPDATE_CHECK_DISABLED=1` 关闭；
自称 "not an official DeepSeek product"）。topics 含 `deepseek-harness`、`dsh-plugin`。

**对本仓的可移植性：一半能借、一半借不动**

| 部分 | 能否进 Neomix | 依据 |
|---|---|---|
| 交付形态（自包含 HTML → 在聊天里显示） | ✅ **骨架已通** | 我们**已经在 WebView 里渲染 Mermaid**（`ui/components/richtext/Mermaid.kt:114`），且有**全屏 `Screen.WebView(contentId=…)`** 路由（`HighlightCodeBlock.kt:556`、`ChatMessage.kt:312`）。缺的是"通用产物渲染面"（高度/沙箱/全屏） |
| IR + 校验 + 原子替换 + 修复清单 | ✅ **抄契约** | 与本仓"可复现、可证伪、失败要能自修"完全同向 |
| **Node CLI**（`node archify/bin/archify.mjs validate/preview/deliver`） | ❌ **跑不了** | Neomix 工作区没有 Node 运行时（`data/model/Favorite.kt:12` 那个 `NODE` 枚举与此无关）⇒ **原样装成技能会得到一个"命令跑不通"的技能** |
| 全量 vendor | ❌ | 712MB + 284 open issues；只取 SKILL 规格 + schema 才合理 |

**结论：archify 对本仓的价值是"契约与参考"，不是"可安装的技能"。** Node 那半在 Android 宿主上是死的；
但 JSON IR + 校验 + 原子替换 + 自包含 HTML 这套，正是 #2123 评论串里所有人共同卡住的**交付**那一半的成熟答案。
若要吸收：**抄契约、不抄运行时** —— 模型直接产 HTML/IR，宿主负责校验、存、显示，失败给修复清单。

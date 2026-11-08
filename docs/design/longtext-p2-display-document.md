# 长文 P2：显示文档与 BetterText 区块接口

P2 为后续懒加载建立显示投影，不改变会话存储、发送上下文、编辑合并、复制和导出格式；聊天列表仍按消息渲染。P3 再处理标准布局静态懒加载、索引/跳转和选择；P4 接流式与其他布局；超大单块专项保留到 P5。

## 消息显示模型

`MessageDisplayDocument` 保存消息 UUID、原始完整 parts，以及 header、body、footer 的身份描述。body 直接复用现有 `groupMessageParts` 的思维链/正文/图表分组与折叠策略，不再实现第二套排序规则。每个 body 条目标注原始 part 下标，重复相等 part 仍保持独立索引；空白正文按原策略不渲染，但保留在 sourceParts 中。

正文 Markdown 的解析会话由 `MessageTextDocumentId(messageId, partIndex)` 隔离，切换消息分支不会沿用另一条消息的解析状态。part 没有独立持久化 UUID，因此使用原始 part 下标；该坐标只用于原始消息内的编辑目标，不能与显示字符范围互换。结构编辑或分支切换需要重新协调显示投影。

当前仍使用原有 header/body/footer 布局组件和完整 Markdown Column；这些身份描述不等于已经把消息拆进 LazyColumn。

## 受控的本地模块

`bettertext-markdown` 替换 `com.github.re-ovo.bettertext:markdown:v0.1.5`。保留原包名、`MarkdownDocument`/节点类型、完整文档渲染 API 与九个上游主源文件，以及 MIT 许可证和三个上游测试文件。来源和差异见 [模块说明](../../bettertext-markdown/README.md)。LaTeX/highlight Maven 模块及已有 `bettertext-text` 替换继续兼容。

依赖必须保留发布物中的 JetBrains Markdown `25d181c` 与 Jsoup `1.23.2`。应用虽然请求 `d79a97cc8e`，此前 Gradle 冲突解析选中了 `25d181c`。只保留应用声明会改变单波浪线删除线等行为，已通过旧 APK 快照及上游测试核实。

新增接口：

- `MarkdownStreamParser.parseRenderDocument()`：一次解析同时返回原 MarkdownDocument 与显示投影。
- `rememberMarkdownRenderDocument()`：沿用首次同步解析、后续后台合流的生命周期，不额外解析一遍。
- `Markdown(unit = ...)`：直接渲染已解析的安全区块，继承相同主题、组件插槽、正文样式和链接回调，并保留全局首块/标题间距语义。宿主负责可见区块间距和一致的宽度约束；空节点单元不应添加可见间距。

## 区块与坐标约定

`MarkdownRenderUnit` 是解析器现有 AST 分组的安全边界，不是固定字数切片。HTML 跨多个 AST 节点时可能生成多个顶层 Markdown 节点，它们作为同一单元保留，避免丢失容器上下文。列表、表格和代码围栏在本阶段保持整体。

每个单元具有 parser-session 内单调分配的 Long 身份、sourceRange、syntaxRange、firstBlockIndex 和已解析节点。sourceRange 为半开 UTF-16 区间，连续覆盖完整 renderSource，包括空白和引用定义；syntaxRange 标注该组真实语法范围。纯空白/定义也可产生没有可见节点的单元。所有单元的 nodes 展平后与原文档 blocks 相同，不对源码片段再次独立解析。

存在三个不同文本空间：

1. 原始消息文本：用于编辑、复制、发送、导出。
2. 应用展示文本：经过当前渲染截断保护、视觉正则和公式环境/空行归一化。
3. `renderSource`：BetterText 进一步归一化 CRLF、LaTeX 定界符，并可能为流式未完成语法补入字符后的解析文本。

所有区块范围只属于第三个空间；传给解析器的完整输入（第二个空间）保存在 MarkdownDocument.source 中，原始消息 parts（第一个空间）仍由消息模型保存。P2 不提供第三个空间到原始消息的反向编辑映射，不得将区块范围直接用于原文编辑或复制截取。

## 稳定身份与失效

沿用上游流式策略：保留公共前缀中更早的 chunk，至少重新解析最后两组。稳定前缀节点实例可复用，尾块按同一语法起点协调身份，因此增长中的代码围栏、从段落变成 Setext 标题的块不会因为内容变化换 key。不使用内容 hash。

非追加编辑提升 generation 并分配新单元身份；每个新输入提升 revision，重复相同输入返回原投影。P3 使用 key 时必须同时带消息/文本段身份，不能将 session-local Long 单独作为全列表键。`reparsedFrom` 是显示坐标中的保守失效起点，不是编辑坐标或“前面永不变化”的承诺。

补齐上游已有的引用定义限制：尾部出现引用定义时重新转换全文，让更早的引用也正确解析；对含定义文档做非追加编辑时同样失效全文，覆盖定义删除。此路径有意放弃前缀节点复用，但仍可协调追加时同起点的单元身份；文本语义变化不应被稳定 key 掩盖。

## 后续接入边界

P3 仍需把消息→区块范围、引用/搜索跳转、贴底及阅读锚点集中映射；标准气泡的整条背景/圆角、统一选择范围和屏幕外内容复制也需一起验证。不要嵌套无高度限制的纵向 LazyColumn，不要把可编辑 TextField 拆进 Lazy item。

当前首次解析、公式归一化及独立超大段落/代码/列表/表格仍可产生明显成本。P2 不承诺帧率提升，也不代表完整懒加载或长文编辑分段模式已经完成。

# IA 盘点表 · 第三轮首批四页（2026-11-15）

上游设计稿：[design-ui-round3-skins-geometry-motion.md](../drafts/design-ui-round3-skins-geometry-motion.md) §4 / §8.3
范围：偏好设置索引 / 通用设置 / 显示设置 / 模型设置。行号以实施后文件为准。

列说明：

- **形态**：Switch / Pill（页内分段或 FilterChip）/ Select / Slider / TextField / 跳转
- **结论**：`留` 原位；`chip` 行尾加当前值 chip；`摘要` 索引页值摘要；`高级` 收进 CardGroup 高级折叠段
- 规则：**带 Switch 的行不加 chip**（开关本身即值，避免读屏重复播报）；内联 Pill/Select/Slider 已经逐项显示值的行也不加 chip。

## 1. 偏好设置索引（SettingPreferencesPage.kt）

| 行号 | 设置项 | 形态 | 结论 |
|------|--------|------|------|
| L83 | 主题与外观 | 跳转 | 摘要「动态色 / 固定主题」 |
| L90 | 通知 | 跳转 | 摘要「已开启 / 已关闭」（emphasized = 开启） |
| L102 | 通用 | 跳转 | 摘要「回车发送 / 回车换行」 |
| L111 | 界面 | 跳转 | 摘要 = 当前皮肤名（AppSkinRegistry 匹配，回落「默认」） |
| L118 | 应用标识 | 跳转 | 留（纯操作页，无持久「当前方案」可摘要） |
| L124 | 网络 | 跳转 | 摘要「已配置 / 直连」 |

## 2. 通用设置（SettingPreferencesGeneralPage.kt）

| 行号 | 设置项 | 形态 | 结论 |
|------|--------|------|------|
| L114 | 启动时新建会话 | Switch | 留 |
| L124 | 回车发送 | Switch | 留 |
| L136 | 显示消息跳转器 | Switch | 留 |
| L149 | 跳转器位置 | Switch | 留 |
| L162 | 自动滚动 | Switch | 留 |
| L174 | 生成时动效 | Switch | 留 |
| L188 | 长消息按需显示 | Switch | 留 |
| L196 | 应用图标样式加载指示 | Switch | 留 |
| L210 | 模糊效果 | Switch | 留 |
| L239 | 输入栏浮动胶囊 | Switch（**已入高级段**） | 高级「输入栏与玻璃效果」 |
| L253 | 输入栏图标淡入 | Switch（**已入高级段**） | 高级 |
| L266 | 玻璃主题 | Switch（**已入高级段**） | 高级 |
| L287 | 顶栏模糊 | Switch（**已入高级段**） | 高级 |
| L309 | 页面玻璃 | Switch + Pill（**已入高级段**） | 高级 |
| L385 | 生成完成振动 | Switch（原有内联配置） | 留 |
| L397 | 跳过图片裁剪 | Switch + Slider | 留 |
| L409 | 长文本转文件 | Switch + Slider | 留 |
| L422 | 长文本阈值 | Slider | 留 |
| L443 | 音量键滚动 | Switch + Slider | 留 |
| L456 | 音量键滚动比例 | Slider | 留 |
| L525–L602 | 重试组（开关 / 延迟 / 保留部分 / 部分后续 / 自动继续 / 部分字符） | Switch + TextField | 留（开关即值；文本框自身显值） |
| L646 | TTS 只读引用 | Switch | 留 |
| L658 | TTS 读括号外 | Switch | 留 |
| L670 | 自动播放 TTS | Switch | 留 |
| L710–L740 | 循环护栏 | Pill + Switch | 留（Pill 已示值） |
| L753 | 规则提醒 | Switch | 留 |
| L765 | 规则提醒等级 | Pill | 留（Pill 已示值） |
| L795–L830 | 回合预算 / 手机步数 | Slider | 留 |
| L842 | 子代理并发 | Switch | 留 |
| L856 | 任务时长限制 | Switch | 留 |
| L870–L917 | 后台任务组 | Switch | 留 |
| L927 | 工具 schema 精简 | Pill | 留 |
| L990–L1029 | 保活组 | Switch + Pill | 留（Pill 已示值） |

结论：通用页 49 项中除「输入栏与玻璃效果」7 项收高级外，其余均原位；**未新增 chip**——枚举行全部使用内联 Pill/Select，已逐项显值，行尾 chip 只会在读屏里重复播报。

## 3. 显示设置（SettingPreferencesUIPage.kt）

| 行号 | 设置项 | 形态 | 结论 |
|------|--------|------|------|
| L256 | 显示用户头像 | Switch + Slider | 留 |
| L268 | 显示助手气泡 | Switch + Slider | 留 |
| L280 | 气泡不透明度 | Slider | 留 |
| L301–L385 | 列表图标 / 模型名 / 生成来源 / 时间 / Token / 思考内容 / 整轮折叠 / 自动折叠 | Switch | 留 |
| L408 | LaTeX 渲染 | Switch | 留 |
| L420 | 图表渲染 | Switch | 留 |
| L432 | 聊天字体族 | Select | **chip**（family.labelUI()） |
| L459 | 自定义字体 | 按钮 | 留 |
| L502 | 字号 | Slider | 留（滑杆已示值） |
| L542 | 头像框 | Pill | **chip**（当前头像框） |
| L595 | 输入框背景 | Pill | 留（Pill 已示值） |
| L626 | 思维链样式 | Pill | **chip**（当前样式） |
| L666–L700 | 输入框配色方案（明/暗） | Pill | 留 |
| L719–L743 | 代码块自动换行 / 自动折叠 / 行号 | Switch（**已入高级段**） | 高级「代码块显示设置」 |
| L764 | 用量模式 | Pill | **chip**（已用 / 剩余） |
| L859 | 用量配色方案 | Pill | 留 |

## 4. 模型设置（SettingModelPage.kt）

| 行号 | 设置项 | 形态 | 结论 |
|------|--------|------|------|
| L220 | 模型 | CardGroup + Switch | 留 |
| L333 | 模型卡片大小 | Slider | 留（滑杆已示值） |
| L410 | 上下文图片 | 数值输入 | 留（输入框已示值） |
| L472 | 自动压缩 | Switch | 留 |
| L682 | 上下文长度覆盖 | TextField（**已入高级段**） | 高级；hint 显示「跟随模型默认 / 已覆盖」 |
| L725 | 启用建议 | Switch | 留 |
| L802 | 选择模型 | 跳转 | 留 |
| L864 | OCR 后端 | Pill（**已入高级段**） | 高级；hint 显示当前后端名；DeepSeek 参数随折叠隐藏 |

## 5. 遗留 / 挂账

- 设置搜索是独立全局搜索页（`SettingGlobalSearchPage`），页内无搜索框 → 「命中折叠项自动展开」缺前提，本轮继续挂账。
- 模型页「模型卡片大小 / 上下文图片」为单卡多滑杆/输入，值 chip 无法表达复合值，维持原位。
- 「应用标识」为纯操作页（创建启动器快捷方式），无持久当前值，索引页不做摘要。

# RikkaHub Plus 应用图标 · 风格描述与生成指南

> 用于外部 AI 生图工具（Midjourney / SD / 即梦 / DALL·E 等）生成应用图标的风格基准。
> 生成稿需满足 Android adaptive icon 工程约束（见第三节），否则需后处理。

---

## 一、项目定位（生成时的语义锚点）

- **产品**：RikkaHub Plus —— 原生 Android LLM 聊天客户端（fork 自 RikkaHub）
- **核心**：多提供商 AI 对话（OpenAI / Claude / Google 兼容接口）、Material You 设计、Workspace 沙箱、MCP 扩展、隐私优先
- **气质关键词**：AI 对话 · 智能核心 · 现代简洁 · 科技感 · 沉稳不花哨 · 原生质感
- **应传达**：一眼看出是"AI 助手 / 对话"类应用，而非游戏或工具

---

## 二、视觉风格基准（当前倾向）

基于你选定的参考图提炼的特征：

| 维度 | 描述 |
|---|---|
| 整体 | 浅色/近白底为主，主体居中，留白充分，简洁克制 |
| 主视觉 | **青蓝色渐变（cyan → blue）的环形能量核心**——环状结构、内部发光，象征"AI 智能中枢" |
| 上部元素 | 顶部一排细小的装饰元素（类似代码/星点/数据点），呼应"对话 / 模型"语义 |
| 下部元素 | 一条自核心向下的**光轨 / 坠落轨迹**（星芒或粒子拖尾），增加动感与纵深感 |
| 风格 | 现代扁平/半扁平 + 微妙渐变，无写实、无复杂场景、无文字 |
| 配色 | 主色青蓝渐变；背景浅色（白 / 极浅灰）；辅助色极少，克制 |
| 情绪 | 干净、智能、轻盈、有生命力（轨迹动感），不压抑不花哨 |

---

## 三、Android 工程约束（生成时必须遵守或后续处理）

1. **构图**：正方形 1:1；主体居中对齐，占画面 **中心约 60–66%**（四边留白 ≥ 15%）——Android 会在圆形区域内裁切，主体太靠边会被切掉
2. **圆角**：由系统套圆角，生图**不要**自带圆角或边框，做成满幅方图即可
3. **无文字**：禁止任何文字 / 字母 / 数字（小尺寸下不可读，且侵权风险）
4. **小尺寸可辨**：要经得起缩到 48px 仍能认出主体（避免细小纹理、密集噪点）
5. **三态需求**：除彩色主图标外，工程还需一张**单色版**（monochrome，纯黑白轮廓、无渐变）用于 Android 主题化图标——生成时若能顺带一张黑白轮廓版最好，否则可后处理
6. **透明背景**：彩色版若含背景色块，需能与主体分离（后处理抠图用）；纯色/渐变背景最易处理

---

## 四、可直接复制的生成 Prompt

### 中文版

> Android 应用图标，方形，现代极简风格。浅色（近白）背景。主体居中：一个青蓝渐变的环形能量核心，圆环内部发光，象征 AI 智能中枢；核心上方点缀一排细小代码/星点元素，呼应对话与模型；核心下方有一条向下延伸的光轨或粒子坠落轨迹，增加动感。整体干净、科技、轻盈。无文字，无边框，无圆角，主体占中心 60%，四周留白充足。扁平加微妙渐变，适合手机桌面图标，缩到 48 像素仍清晰可辨。

### 英文版（推荐，兼容主流生图工具）

> Android app launcher icon, 1:1 square, modern minimal style. Very light (near-white) background. Centered main subject: a ring-shaped energy core with cyan-to-blue gradient, glowing from within, symbolizing an AI intelligence hub. Above the core, a thin row of tiny code-snippet / star dots echoing chat & models; below, a single downward light trail / comet streak of particles for a sense of motion. Clean, techy, airy, premium. No text, no letters, no border, no rounded corners, generous margin — subject occupies central ~60%. Flat with subtle gradient, legible even at 48px as a phone launcher icon.

### 单色版（monochrome，供工程三态）

> Same composition as a pure single-color silhouette: flat black-on-transparent, the ring energy core with the row of dots above and the light trail below, no gradients, no text, white background removed — an outline/shape recognizable at small size.

---

## 五、生成后集成备忘（工程侧）

- 替换 `app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/` 的 5 套资源：
  - `ic_launcher.png`（legacy 合成图）
  - `ic_launcher_background.png`
  - `ic_launcher_foreground.png`（前景，透明底，主体按安全区缩放）
  - `ic_launcher_monochrome.png`（单色版）
- `mipmap-anydpi-v26/ic_launcher.xml` 引用关系不变（background / foreground / monochrome）
- README 顶部 `docs/icon.png`（192×192）同步替换
- `Export.kt` 引用 `R.mipmap.ic_launcher_foreground` 不变
- 验证：`gradle :app:assembleDebug` → 真机看圆/方/主题化三种形态

# 贡献指南 | Contributing

> 本仓是 [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) 的**非官方 fork**（应用显示名 **Neomix**，
> 包名 `app.androidrik.rikkahub`），由个人维护。开始之前请先读 [README](README.md) 的
> 「与上游的关系」与「反馈渠道」两节。

## 一、先走 Discussions，不要直接开 PR

本仓**有意不接 issue**（仓库设置里 issue 功能是关的），反馈与讨论统一走
[💬 Discussions](https://github.com/Fantasycvh/RikkaHub-Neomix/discussions)。

**动手写代码之前，请先在 Discussions 里说明你要改什么、为什么**。原因不是走流程，而是这个仓的取舍很特殊
（见第二节），很可能你花时间做的东西方向就不在本仓的接受范围内。先讨论能省掉双方的时间。

### 属于上游的问题，请直接给上游

本仓与上游**无从属关系**，上游也不处理本仓的问题。如果某个 bug 在上游 [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)
同样存在，**请提到[上游仓库](https://github.com/rikkahub/rikkahub/issues)**——提到这里不会更快，只会多一次转述。
本仓只处理**本项目自身引入或本项目独有的**问题。

## 二、改动不被合并，也不会回推上游

- **不回推上游**：上游明确不接受本仓的改动，故本项目**不会**向上游提交任何 PR。你的改动只会在本仓生效。
- **不做整体同步**：本仓不整体合并上游，而是按需**人工逐条评估后选择性吸收**（关键修复 / 低成本高价值功能）。
  所以「把上游某个提交搬过来」这类 PR 需要你先说明**为什么本仓需要它**，并附上评估理由。
  评估记录见 [`docs/upstream-analysis.md`](docs/upstream-analysis.md)。

## 三、PR 的接受标准：小、可验证、带测试

**会被接受的样子：**

1. **小** —— 一个 PR 只做一件事。范围越小越可能被合并；跨模块的大改请先拆成多个 PR，或先在 Discussions 对齐方案。
2. **可验证** —— 描述里能说清「改之前是什么现象、改之后是什么现象」，以及**你是怎么验证的**。
   涉及界面/交互的改动，请附**真机**验证结果（截图或文字描述都可以）。
3. **带测试** —— 新增逻辑要配 JVM 单测。若某处确实无法用单测覆盖（@Composable 接线、只在真机暴露的行为），
   本仓沿用的做法是**源码级守卫**（读源码断言接线），见
   `app/src/test/java/.../AvatarReferenceRewriteWiringTest.kt`、`ai/src/test/java/.../ChatCompletionsRequestBodySerializationTest.kt` 这类先例。
   如果你做了**反证**（故意破坏代码、确认守卫会红），在描述里写出来，这会显著提高可信度。
4. **不改动无关文件** —— 不要顺手重排格式、改无关文案、升级依赖。

**会被直接拒绝的样子：**

- 把上游某个提交整体搬过来（且没说明本仓为什么需要）；
- 大规模重构 / 全仓风格统一 / 换用某个库（除非先在 Discussions 达成一致）；
- 提交 AI 生成、且未经你自己核实与测试的代码或描述（本仓 issue 模板里也有同款要求）；
- 只改文档却声称修了 bug，或声称测试通过但给不出命令与结果。

## 四、怎么跑测试

本仓的「全量」**不止 `:app:` 与 `:ai:`**，漏跑模块是本仓踩过的坑，请按下面这条跑全模块：

```bash
# 全模块 JVM 单测（含 common / workspace / search / speech / document / highlight / material3 / web）
./gradlew testDebugUnitTest -x :web:buildWebUi --console=plain

# 只想快速验证能编译（跳过 web-ui 构建）
./gradlew :app:compileDebugKotlin -x :web:buildWebUi -q
```

> **注意**：本仓 **CI 不会对 PR 自动跑测试**（`.github/workflows/daily-build.yml` 仅支持手动触发，
> 且本仓不提供预编译包），所以**必须由你本地跑通并在 PR 描述里贴结果**。

## 五、提交与语言

- **提交信息用中文**，写清「改了什么 / 为什么这么改 / 怎么验证的」；吸收自上游的改动请标注来源提交哈希。
- 代码与注释风格沿用仓库既有形态（4 空格缩进、最长 120 列，见 `.editorconfig`）。
- 涉及用户可见文案时，**六个语言都要补齐**：`values`(en) / `values-zh` / `values-zh-rTW` / `values-ja` / `values-ko-rKR` / `values-ru`。

## 六、许可

本仓以 **AGPL-3.0** 发布（保留上游原 `LICENSE` 与出处）。你提交的贡献将同样以 AGPL-3.0 许可分发。
请在提交前确认你有权贡献那段代码；**不要**粘贴来源不明的代码（尤其是从闭源项目或他人仓库里抄来的）。

## 七、响应时间

本项目由个人维护，**不承诺及时响应**。PR 可能被长时间搁置、也可能被直接关闭且不附详细理由——
这是本仓刻意保留的取舍，请勿因此重复提交或催促。

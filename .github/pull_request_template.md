<!--
提交前请先读 CONTRIBUTING.md。本仓是非官方 fork、由个人维护：
不整体合并上游、不回推上游、不承诺及时响应，且 PR 只接受「小、可验证、带测试」的改动。
Before opening a PR, read CONTRIBUTING.md. This is an unofficial fork maintained by one person.
-->

## 改了什么 | What does this PR do

<!-- 一句话说明。一个 PR 只做一件事。 -->

## 为什么本仓需要它 | Why does this fork need it

<!--
说明动机。若是「从上游搬过来的提交」，请额外说明本仓为什么需要它
（上游逐条评估记录见 docs/upstream-analysis.md）。
-->

## 怎么验证的 | How did you verify it

<!--
改之前是什么现象、改之后是什么现象，以及你实际怎么验证的。
涉及界面/交互的改动请给真机结果（截图或文字描述）。
注意：本仓 CI **不会**对 PR 自动跑测试，所以这里必须是你自己跑过的结果。
-->

## 测试 | Tests

<!--
跑了什么命令、结果如何。例如：
```
./gradlew testDebugUnitTest -x :web:buildWebUi --console=plain
# -> BUILD SUCCESSFUL，:app 1506 项 / :ai 259 项，0 失败
```
新增逻辑请配单测；无法单测的接线处请加**源码级守卫**，并说明是否做过反证。
-->

## 自查 | Checklist

- [ ] 我已阅读 [CONTRIBUTING.md](CONTRIBUTING.md)，并确认这个改动**不在**「会被直接拒绝」的范围内
- [ ] 这个 PR **只做一件事**，没有夹带无关改动（格式重排 / 无关文案 / 依赖升级等）
- [ ] 这是**本仓自身**的问题；若上游同样存在，我已知悉应提到上游仓库
- [ ] 涉及用户可见文案时，我已补齐六个语言
- [ ] 我没有粘贴来源不明、或未经我自己核实与测试的代码

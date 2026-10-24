# 思考退化护栏：续写判据重修（占位）

> 状态：**占位草稿（2026-10-24，未展开，未动代码；本地草稿，未提交）**。
> 从 `design-context-purification.md` §14 剥出单独立项。剥出的直接原因：该问题影响净化设计 P0 命中率的统计口径（假阳性会话须排除）。

## 问题

核实净化设计（2026-10-23）时发现：**续写路径上的「复述」判据不稳，两个方向都会错**。

1. **假阳性**：续写追加到**同一条**消息（`GenerationHandler.kt:663-690`），于是 `reasoning` 是累积的，`containment(累积_N, 累积_{N-1}) ≡ 1.0` ⇒ 每次续写都被判成「复述」。一个长回答遇到 3 次裸断流（`finishIncomplete` 默认自动续写，`CONTINUE_LIMIT = 3`），就会以「思考退化」被停掉并显示错误原因。
2. **假阴性**：`shingles()` 只取最后 4000 字符（`ThinkingDegenerationGuard.kt:229`），单次续写若新增 > ~4000 字符，containment 归零、`stalledCalls` 清零。

## 方向（未定稿）

- 续写分支只拿**新增段**做判据；
- 或在 `onCallFinished` 里区分「同一消息的续写」与「新的一次调用」，续写不计入 `stalledCalls`。

两条方向不互斥，可组合；定稿前需先核对 `onCallFinished` / 续写分支的现行调用时序（行号以 2026-10-23 核对为准，动手前重验）。

## 关联

- 净化设计的命中率统计必须先排除本问题的假阳性会话：`design-context-purification.md` §12 已定 1；
- 净化判据因此坚持「内容自证的字面重复」，不以「护栏停过」为触发：`design-context-purification.md` §4.2、§14 末段。

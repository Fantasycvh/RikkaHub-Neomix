import io, re

def rw(p, old, new, cnt=1):
    s = io.open(p, encoding='utf-8').read()
    assert old in s, 'NOT FOUND in %s:\n%s' % (p, old[:180])
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s.replace(old, new, cnt))
    print('ok:', p.split('/')[-1])

# ---- 1) ConversationEntity: frozen_anchors（可空 ⇒ AutoMigration 无需 spec）----
rw('app/src/main/java/app/androidrik/rikkahub/data/db/entity/ConversationEntity.kt',
"""    @ColumnInfo("memory_disabled", defaultValue = "0")
    val memoryDisabled: Boolean = false,
)""",
"""    @ColumnInfo("memory_disabled", defaultValue = "0")
    val memoryDisabled: Boolean = false,
    /**
     * **冻结的锚点快照**（L1，会话开始时一次 + 用户手动「固化记忆」时重写）。
     *
     * 为什么需要冻结：L1 在 **system** 层，而 system 是第一条消息、前缀缓存从位置 0 起
     * 连续匹配 —— 改它一次，其后**全部重算**（真机实测命中率 98% → 0%）。冻结把这件事
     * 变成**显式且可预期**：会话内不再因锚点集合/内容的变动而被动失效。
     *
     * 三态：
     * - `null` = **未冻结**（本次升级前的存量会话）⇒ 用实时锚点，行为与改动前一致；
     * - `""`   = 已冻结、且当时**没有锚点** ⇒ 不产生 Anchors 块（保持"无锚点 ⇒ 无块"不变式）；
     * - 其他   = 冻结的渲染文本。
     *
     * 见 `docs/design/design-memory-injection-tiering.md`。
     */
    @ColumnInfo("frozen_anchors")
    val frozenAnchors: String? = null,
)""")

# ---- 2) AppDatabase: 37 -> 38 ----
p = 'app/src/main/java/app/androidrik/rikkahub/data/db/AppDatabase.kt'
s = io.open(p, encoding='utf-8').read()
assert 'version = 37,' in s
s = s.replace('version = 37,', 'version = 38,', 1)
m = None
for mm in re.finditer(r'\n        AutoMigration\(from = \d+, to = \d+(?:, spec = \w+::class)?\),', s):
    m = mm
assert m
s = s[:m.end()] + ('\n        // 37→38：会话级锚点冻结快照（可空纯列 ⇒ 无需 spec）。\n'
                   '        // 存量会话为 null = 未冻结（用实时锚点），行为与改动前一致。\n'
                   '        // 见 docs/design/design-memory-injection-tiering.md\n'
                   '        AutoMigration(from = 37, to = 38),') + s[m.end():]
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok: AppDatabase -> 38')

# ---- 3) Conversation 模型 ----
rw('app/src/main/java/app/androidrik/rikkahub/data/model/Conversation.kt',
"""    val memoryDisabled: Boolean = false,""",
"""    val memoryDisabled: Boolean = false,
    /**
     * **冻结的锚点快照**（L1）：会话开始时一次 + 用户手动「固化记忆」时重写。
     *
     * `null` = 未冻结（用实时锚点，存量会话的行为）；`""` = 冻结且当时无锚点。
     * 见 `docs/design/design-memory-injection-tiering.md`。
     */
    val frozenAnchors: String? = null,""")

# ---- 4) 三处映射 ----
rw('app/src/main/java/app/androidrik/rikkahub/data/repository/ConversationRepository.kt',
"""                    memoryDisabled = entity.memoryDisabled,""",
"""                    memoryDisabled = entity.memoryDisabled,
                    frozenAnchors = entity.frozenAnchors,""")
rw('app/src/main/java/app/androidrik/rikkahub/data/repository/ConversationRepository.kt',
"""            memoryDisabled = conversation.memoryDisabled,""",
"""            memoryDisabled = conversation.memoryDisabled,
            frozenAnchors = conversation.frozenAnchors,""")

# ---- 5) PromptAssembly：三处加 frozenAnchorText 参数 ----
P = 'app/src/main/java/app/androidrik/rikkahub/data/ai/PromptAssembly.kt'
rw(P, """    memoryDisabled: Boolean = false,
): List<SystemBlock> {""",
"""    memoryDisabled: Boolean = false,
    /**
     * **冻结的锚点快照**：非 null 时**以它为准**，不再用 [memories] 实时渲染。
     * `null` = 未冻结（实时渲染，行为与改动前一致）。
     * 见 `docs/design/design-memory-injection-tiering.md`。
     */
    frozenAnchorText: String? = null,
): List<SystemBlock> {""")

rw(P,
"""        val anchorText = buildMemoryAnchorPrompt(memories)""",
"""        // 冻结优先：非 null 时以快照为准（"" = 冻结时无锚点 ⇒ 不产生块，不变式仍成立）
        val anchorText = frozenAnchorText ?: buildMemoryAnchorPrompt(memories)""")

rw(P, """    memoryDisabled: Boolean = false,
): String {""",
"""    memoryDisabled: Boolean = false,
    frozenAnchorText: String? = null,
): String {""")

rw(P, """    memoryDisabled: Boolean = false,
): List<UIMessage> {""",
"""    memoryDisabled: Boolean = false,
    frozenAnchorText: String? = null,
): List<UIMessage> {""")

# BetterText Markdown compatibility module

Vendored from [re-ovo/bettertext v0.1.5](https://github.com/re-ovo/bettertext/tree/v0.1.5/markdown),
commit `46324cfb1da1bba043d5ba75ea09b2f1ada06f17`, under the included MIT license.
All nine original main Kotlin files and three upstream test files are retained. Gradle substitutes
only `com.github.re-ovo.bettertext:markdown`; the existing text compatibility module and published
LaTeX/highlight modules keep their original APIs and packages.

Local extensions:

- `MarkdownStreamParser.parseRenderDocument()` returns the existing `MarkdownDocument` plus parser-safe
  render units, half-open ranges, session-local numeric identities, revision/generation and the
  conservative reparse start. Kept chunks retain nodes; append-only reparsed units retain identity by
  syntax start, not by content hash. Non-append edits allocate new identities.
- `renderSource` is the parser-normalized, optionally streaming-completed text. Units partition it
  without dropping definitions or whitespace. It is a separate coordinate space from the original
  `MarkdownDocument.source` and the app's raw/regex-transformed messages. There is no reverse edit map.
- `Markdown(unit = ...)` renders a previously parsed unit with the same context/slots/style/link handler
  as the whole-document overload. It preserves global heading position; the host supplies spacing
  between visible units and consistent width constraints. Empty-source-only units add no visible item.
- Reference definitions appearing in a reparsed tail invalidate the complete conversion, so links in
  older chunks resolve too. Non-append edits to documents with definitions also invalidate globally,
  including definition removals. This fixes the upstream parser's documented backward-reference limit.

Unchanged: Markdown grammar/conversion, nesting, streaming-end completion, public document/node types,
whole-document rendering and the synchronous first parse/background conflated updates. No LazyColumn
or viewport logic is added here. Lists, tables, large code blocks and HTML groups remain atomic.

The app requests JetBrains Markdown `d79a97cc8e`, but Gradle selected the published module's `25d181c`
over it. Retain that dependency: dropping it silently loses single-tilde strikethrough, as detected by
the upstream tests and old-device snapshot. Both this module and the app therefore resolve `25d181c`.
Jsoup remains `1.23.2`, as in the published v0.1.5 dependency. Keep versions aligned when updating.

P2 validation includes upstream parser/streaming tests, projection invariants, reference invalidation,
10k/50k/100k source coverage, app message projection tests, a device parser snapshot against the old AAR
application, and whole-document versus unit-renderer pixel comparisons. See
[`docs/design/verification/longtext-p2-2026-11-09.md`](../docs/design/verification/longtext-p2-2026-11-09.md).

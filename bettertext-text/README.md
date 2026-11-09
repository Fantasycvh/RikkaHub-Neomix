# BetterText text compatibility module

Vendored from [re-ovo/bettertext v0.1.5](https://github.com/re-ovo/bettertext/tree/v0.1.5/text),
under the included MIT license. The seven text-module source files retain their original packages
and public signatures so the existing Markdown, highlight and LaTeX artifacts remain compatible.
Gradle substitutes only `com.github.re-ovo.bettertext:text` with this project.

Local changes:

- Apply `TextStyle.brush` to the native StaticLayout paint at drawing time, using the measured
  text bounds. Layout, links, selection and semantics continue through BetterText.
- Explicit foreground spans clear the inherited shader, preserving link and code colors.
- Restore the native paint after drawing, so changing or disabling a brush leaves no cached shader.

Keep these changes small when updating upstream; validate both gradient pixels and solid-span
colors with `ChatAppearanceRenderingTest`.

P3 adds an optional shared selection host for lazy content, retaining the original public
`BetterSelectionContainer` signature. The overload with `documentText` enrolls only explicit
`BetterSelectionGroup` descendants. Groups may span lazy items; ordinary drags stay inside
the active group. `separatorBefore` defaults to the upstream newline; raw-source chunks use
an empty separator to preserve their original text exactly.

The document callback supplies complete original source for Select All without composing or
pinning offscreen items. It releases selection pins, highlights newly mounted texts in that
group, and copies the supplied source. This mode omits drag handles; start a new local selection
to adjust a range. Ordinary drag selection retains upstream pinning and rendered-text copying.
It can still retain many items during a very long drag.

Selection geometry notifications compare bounds and layout/prepared-text identities before
changing Compose state. This prevents toolbar/popup layout callbacks from causing a feedback
loop. Validate lazy selection, group isolation, raw chunk boundaries and source-backed copying
with `ChatLazyRenderingInstrumentedTest` alongside the appearance suite.

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

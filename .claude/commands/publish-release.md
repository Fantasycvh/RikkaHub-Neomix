请基于git log生成更新日志，然后创建一个新的release

## 更新日志

- 查看从上一次release tag到当前commit的修改
- 总结更新内容并生成更新日志, BUG修复和UI调整尽可能合并，确保总更新日志条目数量不超过 10条
- 日志中避免出现技术名词

使用以下格式:

```markdown
更新内容:

- xxx
- xxx

Updates:

- xxx
- xxx
```

(双语更新日志)

更新日志生成完后，请求用户确认更新日志是否合理，等到用户确认可以发布后，创建release

## 发布

**构建统一走项目内脚本**（Git Bash）：`bash scripts/build.sh release` —— 脚本自动固定缓存迁移路径（GRADLE_USER_HOME/PUB_CACHE → <gradle-home>）、pnpm PATH、`--max-workers=2`，成功后自动把 arm64 产物复制改名到 `app/release/`（Neomix-<version>-arm64-v8a.apk）。任意 gradle task 可透传（`bash scripts/build.sh ":app:testDebugUnitTest"`）。

使用github cli创建release, 并上传 app/release/xxx.apk 文件

- 仅上传arm64版本
- **APK 命名固定格式：`应用名（英文）-版本-架构`**（如 `Neomix-2.4.10.16-arm64-v8a.apk`），
  其中架构用 Gradle splits 的 ABI 名（arm64-v8a）；发布前将构建产物复制到 `app/release/` 并按此重命名
- release标题使用版本号, 描述使用更新日志
- 命令固定带 `-R Fantasycvh/RikkaHub-Neomix`（gh 默认指向上游时不会误发到上游）
- **创建分两步**（`gh release create <tag> <apk>` 一次性传 76MB 资产偶发挂起、asset 为空）：
  1. `gh release create <tag> --draft --title "v<ver> (<code>)" --notes-file <绝对路径>`
  2. `gh release upload <tag> "app/release/Neomix-<ver>-arm64-v8a.apk"`
- **bump 前先清掉 `app/release/` 里同名旧产物**（历史草稿会留下同文件名，易误传）

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

构建前固定缓存路径：`export GRADLE_USER_HOME=<gradle-home>/gradle`（PUB_CACHE 同理指向 <gradle-home>/pub-cache；用户已迁移，C 盘旧缓存已清理）。构建命令用 `./gradlew :app:assembleRelease --max-workers=2`。

使用github cli创建release, 并上传 app/release/xxx.apk 文件

- 仅上传arm64版本
- **APK 命名固定格式：`应用名（英文）-版本-架构`**（如 `RikkaHub Plus-2.4.10.16-arm64-v8a.apk`），
  其中架构用 Gradle splits 的 ABI 名（arm64-v8a）；发布前将构建产物复制到 `app/release/` 并按此重命名
- release标题使用版本号, 描述使用更新日志
- tag名字使用版本号，不带v

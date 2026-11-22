# 导航统计（docs/design/drafts/design-nav-stats.md）依赖 `Screen::class.simpleName`
# 作为稳定路由名；R8 默认混淆会把类名压成 crc/esc 之类的短名（2026-11-22 release 真机实测）。
-keepnames class app.androidrik.rikkahub.Screen { *; }
-keepnames class app.androidrik.rikkahub.Screen$* { *; }

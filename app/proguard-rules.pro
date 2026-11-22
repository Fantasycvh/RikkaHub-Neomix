# 导航统计（docs/design/drafts/design-nav-stats.md）依赖 `Screen::class.simpleName`
# 作为稳定路由名；R8 默认混淆会把类名压成 crc/esc 之类的短名（2026-11-22 release 真机实测）。
-keepnames class app.androidrik.rikkahub.Screen { *; }
-keepnames class app.androidrik.rikkahub.Screen$* { *; }

# Phone Use 虚拟屏（Shizuku UserService）：Shizuku server 在独立进程里按**类名**从 APK
# 反射实例化该服务（构造器 (Context)），R8 混淆/裁剪会使其无法加载；AIDL Stub 跨进程
# 反序列化同样要求原名。见 docs/design/verification/phoneuse-virtual-screen-2026-11-23.md。
-keep class app.androidrik.rikkahub.phoneuse.PhoneUseDisplayService { *; }
-keep class app.androidrik.rikkahub.phoneuse.IPhoneUseDisplayService { *; }
-keep class app.androidrik.rikkahub.phoneuse.IPhoneUseDisplayService$* { *; }


# Phone Use 虚拟屏幕：真机验证闭环记录

日期：2026-11-23。范围：PR #8（squash 合并 `704700f97e`）+ 同批修复。设备 PJX110（6c841f95），
Android 15（API 35）/ ColorOS 15，root=KernelSU，debug 包 `app.androidrik.rikkahub.debug`。

本记录把虚拟屏投屏实验的**全部真机验证项**收口：生命周期、悬浮窗 dismiss 关屏、模型轮次端到端、
Shizuku ADB（shell uid）模式、Shizuku 未启动错误路径，以及验证期间发现并修复的两个崩溃/门控问题。

## 验证矩阵

| 项目 | 用例 | 结果 |
| --- | --- | --- |
| 生命周期·启动 | 本地工具页 Start virtual display → Shizuku UserService 建屏 | ✅ displayId 建立，flags 齐全（FLAG_SECURE / FLAG_TRUSTED / FLAG_OWN_DISPLAY_GROUP / FLAG_PRESENTATION / FLAG_TOUCH_FEEDBACK_DISABLED），720×1280@280dpi |
| 生命周期·Stop | Stop virtual display 按钮 | ✅ dumpsys 无该 Display，观察窗同步收起 |
| 生命周期·无障碍解绑 | 系统里关闭 Phone Use 无障碍服务 | ✅ detach→close 联动（复验 3 次） |
| 生命周期·锁屏 | 灭屏/锁屏 → deviceMonitor 500ms 轮询 | ✅ 虚拟屏自动关闭 |
| 生命周期·错误文案 | 无障碍未开时 Start | ✅「Enable the Neomix Phone Use accessibility service first.」正确显示 |
| 悬浮窗 dismiss | 悬浮聊天面板（生成中）渲染含表格回复 → 点 × dismiss | ✅ 面板不崩溃；`onDismiss → PhoneUseVirtualDisplays.current.value?.close()` 关虚拟屏（dumpsys 确认），观察窗随之消失 |
| 模型轮次 E2E | DeepSeek-V4.1-Flash（硅基流动）驱动一轮完整操作 | ✅ 10 步 · 5 工具：open_app / observe / list_recent / wait 全链路；open_app 中文标签模糊匹配失败后按包名降级成功；list_recent 正确返回虚拟屏任务；观察窗全程实时呈现操作现场 |
| Shizuku ADB 模式 | Shizuku server 以 shell uid 运行（非 root） | ✅ 虚拟屏照常创建；Display 属性与 root 模式逐项一致（owner uid 2000，uniqueId `virtual:com.android.shell,2000,...`）；`am start --display` 拉起 Settings 成功；观察窗预览实时正常。**API 35 上 shell uid 足够，不强制 root** |
| Shizuku 未启动 | kill shizuku_server → 点 Start | ✅ 红字「Start Shizuku first.」，无崩溃、按钮可重试 |
| 崩溃恢复 | 应用进程崩溃（见下节 TableCard bug）后虚拟屏状态 | ✅ Display 存活（UserService 为独立进程，PPID 1）；应用重启自动重绑、观察窗恢复。孤儿屏靠 Stop / dismiss 收回 |

## 验证期间发现并修复的问题

1. **浮窗渲染表格崩溃**（`39cc7f5ee6`）：悬浮聊天面板（无 Activity 宿主的 ComposeView）里
   `TableCard` 的 `rememberLauncherForActivityResult` 直接抛
   `IllegalStateException: No ActivityResultRegistryOwner...`。修复：无 owner 时不创建 launcher、
   隐藏「导出 CSV」入口（复制走 Clipboard 不受影响）。
2. **phone_use 工具表缺失**（`d03147075a`）：手动重生成/续写把 `phoneUseAllowedForTurn` 重置为
   false 且不写回，装配期无 `phone_use`；旧候选历史里的调用被模型模仿重发 →
   `runPreparedTool` 落空，带整段堆栈的错误结果污染上下文。修复：重生成/续写视同 App 交互回合
   装配 phone_use；工具表缺失改抛 `ToolNotAvailableException`，回填不带堆栈的单行可读错误、
   轮次继续（`GenerationToolLoopTest` 新增降级用例，全量单测绿）。

## 已知遗留

- 虚拟屏 BACK 到最后一个任务退无可退时可能黑屏（无 home 可回，未专门验证）
- 若重新启用 ColorOS Mod（LSPosed 模块），需复验 `am start --display` 是否被拦（当前模块禁用状态链路畅通）
- 副屏桌面：ColorOS launcher 的 home 任务钉在 display 0，无法拉起；真·副屏桌面需另装 SecondaryDisplayLauncher 类应用，暂不做

## 设备操作备忘

- `uiautomator dump` 偶发返回过期坐标导致 tap 落空，**以截图为准再重 tap**；本地工具页实验段在最顶部时
  会被吸顶标题遮挡错误红字，滚动到中段即可见
- Git Bash 下 adb shell 路径参数需 `MSYS_NO_PATHCONV=1`
- Shizuku 恢复（shell 模式）：`su -c 'CLASSPATH=$(pm path moe.shizuku.privileged.api | sed s/package://) nohup app_process /system/bin --nice-name=shizuku_server moe.shizuku.server.ShizukuServer >/dev/null 2>&1 &'`

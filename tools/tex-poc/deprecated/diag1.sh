#!/bin/bash
# 崩溃 tombstone 头部：signal、fault addr、顶部函数名
echo "=== crash buffer 头部 ==="
su -c "logcat -d -b crash" 2>/dev/null | head -18
echo "=== tombstone 信号行 ==="
su -c "logcat -d -b crash" 2>/dev/null | grep -m3 -E "signal |Abort message|Fatal signal"
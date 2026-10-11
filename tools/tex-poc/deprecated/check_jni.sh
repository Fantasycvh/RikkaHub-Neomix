#!/bin/bash
echo "=== source head ==="
head -30 /root/dvisvgm-3.2.1/src/dvisvgm_jni.cpp
echo "=== obj symbols ==="
nm /root/dvisvgm-3.2.1/src/dvisvgm_jni.o | head -20
echo "=== mtimes ==="
ls -la /root/dvisvgm-3.2.1/src/dvisvgm_jni.cpp ${TEX_POC_DIR:?set TEX_POC_DIR}/deprecated/dvisvgm_jni.cpp
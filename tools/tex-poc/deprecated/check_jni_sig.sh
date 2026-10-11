#!/bin/bash
echo "=== dvisvgm_jni.cpp signature ==="
grep -n -A8 "Java_app_androidrik_rikkahub_tikz_TectonicNative_dvisvgmMain" /root/dvisvgm-3.2.1/src/dvisvgm_jni.cpp | head -14
echo "=== kotlin external ==="
grep -n "dvisvgmMain\|dvisvgmexec\|loadLibrary" ${APP_REPO_DIR:?set APP_REPO_DIR}/app/src/main/java/app/androidrik/rikkahub/tikz/TectonicNative.kt | head -6
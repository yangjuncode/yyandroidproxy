#!/bin/bash

# 1. 检查 gradlew 权限，必要时才修改
if [ ! -x "./gradlew" ]; then
    chmod +x ./gradlew
fi

echo "--------------------------------------------------"
echo "Building Release APK (Same as Android Studio Build menu)"
echo "--------------------------------------------------"

# 2. 执行与 AS 菜单一致的构建任务
./gradlew :app:assembleRelease

if [ $? -eq 0 ]; then
    echo "--------------------------------------------------"
    echo "BUILD SUCCESSFUL"
    echo "APK Location:"
    find app/build/outputs/apk/release -name "*.apk"
    echo "--------------------------------------------------"
else
    echo "--------------------------------------------------"
    echo "BUILD FAILED"
    echo "--------------------------------------------------"
    exit 1
fi

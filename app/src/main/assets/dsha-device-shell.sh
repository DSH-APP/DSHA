#!/bin/bash
# DSHA_ADB_SCRIPT_VERSION=17
# 原生层选择 root / Shizuku / ADB，直接通道不依赖 ADB 密钥或配对库。
exec python3 /root/.dsh/adb-shell.py "$@"

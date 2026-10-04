#!/bin/bash
# DSHA 会话自愈（薄包装）：只负责把 zstandard 装上（pip/unzip/python-zipfile 三重兜底），
# 实际扫描/修复/隔离全部交给 heal-sessions.py（Python os.walk，避开 find/link2symlink 坑）。
set -u
LOG=/root/.dsh/heal.log

if ! python3 -c "import zstandard" 2>/dev/null; then
  ZWHL=$(ls /root/.dsh/zstandard-0.25.0-*.whl 2>/dev/null | head -1)
  if [ -n "$ZWHL" ]; then
    # 1) pip（新版 rootfs 有 pip 时最优）
    (python3 -m pip install --break-system-packages --no-index "$ZWHL" >/tmp/zstd-pip.log 2>&1) || true
    python3 -c "import zstandard" 2>/dev/null || {
      # 2) 无 pip / pip 失败：手工解压 wheel
      SITE=$(python3 -c "import site; print(site.getsitepackages()[0])" 2>/dev/null)
      [ -z "$SITE" ] && SITE="/usr/local/lib/python3.12/dist-packages"
      mkdir -p "$SITE"
      if command -v unzip >/dev/null 2>&1; then
        unzip -qo "$ZWHL" -d "$SITE" 2>/dev/null || true
      else
        python3 -m zipfile -e "$ZWHL" "$SITE" 2>/dev/null || \
        python3 -c "import zipfile; zipfile.ZipFile('$ZWHL').extractall('$SITE')" 2>/dev/null || true
      fi
    }
  fi
  if ! python3 -c "import zstandard" 2>/dev/null; then
    echo "[$(date '+%F %T')] zstandard 安装失败（pip/unzip/zipfile 均无效）" >> "$LOG"
    echo "ZSTD_INSTALL_FAIL"
    exit 1
  fi
fi

python3 /root/.dsh/heal-sessions.py
exit $?
#!/bin/bash
# 仅用于新解压的内置 Ubuntu；保留 dpkg 正常维护脚本与软件包数据库，不访问网络。
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
mkdir -p /tmp /var/tmp
chmod 1777 /tmp /var/tmp
cd /root/.dsha-bundled-tools
sha256sum --status -c SHA256SUMS
dpkg --force-confold --unpack ./*.deb
read -r -a packages < packages.txt
dpkg --configure "${packages[@]}"
curl --version
git --version
cp version.txt /root/.dsha-ubuntu-tools-version
rm -f -- ./*.deb SHA256SUMS packages.txt version.txt
cd /root
rmdir .dsha-bundled-tools
printf '\nDSHA_UBUNTU_TOOLS_READY\n'

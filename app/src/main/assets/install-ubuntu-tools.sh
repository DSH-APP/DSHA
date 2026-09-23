#!/bin/bash
# 仅用于新解压的内置 Ubuntu；保留 dpkg 正常维护脚本与软件包数据库，不访问网络。
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
mkdir -p /tmp /var/tmp
chmod 1777 /tmp /var/tmp
install_dir="${1:-/root/.dsha-bundled-tools}"
[[ "$install_dir" =~ ^/root/\.dsha-bundled-tools(-[a-f0-9-]{36})?$ ]] || exit 64
cd "$install_dir"
sha256sum --status -c SHA256SUMS
dpkg --force-confold --unpack ./*.deb
read -r -a packages < packages.txt
dpkg --configure "${packages[@]}"
curl --version
git --version
cp version.txt /root/.dsha-ubuntu-tools-version
rm -f -- ./*.deb SHA256SUMS packages.txt version.txt
cd /root
rmdir "$install_dir"
printf '\nDSHA_UBUNTU_TOOLS_READY\n'

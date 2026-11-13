#!/data/data/com.termux/files/usr/bin/bash
# Run in Termux. Installs Debian PRoot plus the selected Arduino CLI board core.
set -euo pipefail
BOARD_CORE="${1:-arduino:avr}"
pkg update -y
pkg install -y proot-distro
if [ ! -d "$PREFIX/var/lib/proot-distro/installed-rootfs/debian" ]; then
  proot-distro install debian
fi
proot-distro login debian -- bash -lc '
set -e
apt-get update
apt-get install -y curl ca-certificates unzip python3
arch="$(dpkg --print-architecture)"
if [ "$arch" = "arm64" ]; then asset="Linux_ARM64"; elif [ "$arch" = "amd64" ]; then asset="Linux_64bit"; else echo "Unsupported architecture: $arch"; exit 2; fi
curl -fsSL "https://downloads.arduino.cc/arduino-cli/arduino-cli_1.5.1_${asset}.tar.gz" -o /tmp/arduino-cli.tar.gz
tar -xzf /tmp/arduino-cli.tar.gz -C /usr/local/bin arduino-cli
mkdir -p /root/.arduino15
cat > /root/.arduino15/arduino-cli.yaml <<EOF
board_manager:
  additional_urls:
    - https://espressif.github.io/arduino-esp32/package_esp32_index.json
    - https://arduino.esp8266.com/stable/package_esp8266com_index.json
    - https://github.com/stm32duino/BoardManagerFiles/raw/main/package_stmicroelectronics_index.json
    - https://github.com/openwch/board_manager_files/raw/main/package_ch32v_index.json
EOF
arduino-cli core update-index
arduino-cli core install "'"$BOARD_CORE"'"
arduino-cli version
'

#!/bin/bash
#set -e

apt install -y openjdk-21-jdk-headless unzip wget libarchive-tools

cat > /etc/profile.d/androidsdk.sh << 'EOF'
export ANDROID_SDK_ROOT=/usr/local/lib/android-sdk
export PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin
export PATH=$PATH:$ANDROID_SDK_ROOT/build-tools/34.0.0/bin
export PATH=$PATH:$ANDROID_SDK_ROOT/platform-tools
EOF

source /etc/profile.d/androidsdk.sh

wget -nc https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip

mkdir -p /usr/local/lib/android-sdk/cmdline-tools/latest
bsdtar -xf commandlinetools-linux-14742923_latest.zip -C /usr/local/lib/android-sdk/cmdline-tools/latest --strip-components 1



yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

# ESP32 test images (make builds blink*_esp32.bin with arduino-cli)
arduino-cli config init || true
arduino-cli config add board_manager.additional_urls https://espressif.github.io/arduino-esp32/package_esp32_index.json
arduino-cli core update-index
arduino-cli core install esp32:esp32

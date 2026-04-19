#!/bin/bash
#set -e

apt install -y openjdk-21-jdk-headless unzip wget

cat > /etc/profile.d/androidsdk.sh << 'EOF'
export ANDROID_SDK_ROOT=/usr/local/lib/android-sdk
export PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin
export PATH=$PATH:$ANDROID_SDK_ROOT/build-tools/34.0.0/bin
export PATH=$PATH:$ANDROID_SDK_ROOT/platform-tools
EOF

source /etc/profile.d/androidsdk.sh

wget -nc https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip
wget -nc https://services.gradle.org/distributions/gradle-8.10.2-bin.zip

mkdir -p /usr/local/lib/android-sdk/cmdline-tools/latest
bsdtar -xf commandlinetools-linux-14742923_latest.zip -C /usr/local/lib/android-sdk/cmdline-tools/latest --strip-components 1

bsdtar -xf gradle-8.10.2-bin.zip --strip-components 2 -C /usr/local/bin "gradle-8.10.2/bin"
bsdtar -xf gradle-8.10.2-bin.zip --strip-components 2 -C /usr/local/lib "gradle-8.10.2/lib"


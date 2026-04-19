An android java application for programming MCUs via USB (supporting usb serial and DFU). the app name is UsbThing, with domain name scinova.net, the project is build with gradle and openjdk on debian bookworm.

A base class Programmer, with derived classes ESP32Programmer, STM32Programmer and AVRProgrammer.

Main view is composed of a number of sections:
1. An programmer selector from AVR/ESP32/STM32, the choice remembered persistently.
2. A file selector that allows to select a file, display the selected filename and file size at the top of the section, and remember the file persistently. No permissions to external usb-storage is needed, as we are using the usb.
3. a scrollable list of connected usb devices (list only the pid/vid). near each item there is a button, that displays the permission dialog, allowing to grant permission to that specific device. if permission is granted, the button will dissapear for that device. as there can be a number of connected devices, the user can select any device, and the button and permission dialog will be shown only for devices not already granted permission.
4. a scrollable log section, that displays the logs, automatically scrolled to the bottom (to see the last log line). All classes should generate log messages for all the relevant events. for easy readability, we should call the method "log(message)".
5. a info section, with a "info" button, that when pressed, will get detailed info from the selected device (for chips where it is possible to do so, not for avrs that do not expose such data). the type of the mcu and protocol is defined by the user selection, not by the actual hardware pid/vids. in the future, we could implement automatical mcu selection, but for now we can ignore if the actual hardware mismatches.



# Android SDK Setup

## System

`/etc/profile.d/androidsdk.sh`:

```sh
export ANDROID_SDK_ROOT=/usr/local/lib/android-sdk
export PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin
export PATH=$PATH:$ANDROID_SDK_ROOT/build-tools/34.0.0/bin
export PATH=$PATH:$ANDROID_SDK_ROOT/platform-tools
```

```sh
apt install openjdk-21-jdk-headless
```

## Android SDK

Download: https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip

```sh
unzip -j commandlinetools-linux-14742923_latest.zip -d /usr/local/lib/android-sdk/cmdline-tools/latest/
```

```sh
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

```sh
sdkmanager --list_installed


## Gradle

Download: https://services.gradle.org/distributions/gradle-8.10.2-bin.zip

```sh
unzip -j gradle-8.10.2-bin.zip "gradle-8.10.2/bin/*" -d /usr/local/bin
unzip gradle-8.10.2-bin.zip "gradle-8.10.2/lib/*" -d /usr/local/lib
```
























```/etc/profile.d/androidsdk.sh

export ANDROID_SDK_ROOT=/usr/local/lib/android-sdk
export PATH=$PATH:/usr/local/lib/android-sdk/cmdline-tools/latest/bin
export PATH=$PATH:/usr/local/lib/android-sdk/build-tools/34.0.0/bin
export PATH=$PATH:/usr/local/lib/android-sdk/platform-tools
```


openjdk-21-jdk-headless

https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip

https://services.gradle.org/distributions/gradle-8.10.2-bin.zip


sdkmanager --licenses

sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"


gradle wrapper

./gradlew assembleRelease
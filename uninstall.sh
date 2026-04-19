# uninstall.sh - Remove files and directories from Android SDK & Gradle setup
# Run with: sudo ./uninstall.sh

#set -e

CMDLINE_ZIP="commandlinetools-linux-14742923_latest.zip"
GRADLE_ZIP="gradle-8.10.2-bin.zip"

# Remove environment file
rm -f /etc/profile.d/androidsdk.sh

# Remove Android SDK directory entirely
rm -rf /usr/local/lib/android-sdk


##!/bin/bash
#s#et -e

# Hardcoded for absolute certainty
GRADLE_ZIP="gradle-8.10.2-bin.zip"

if [ ! -f "$GRADLE_ZIP" ]; then
    echo "ERROR: $GRADLE_ZIP is missing from $(pwd)"
    exit 1
fi

echo "--- Starting Gradle Removal ---"

# 1. Clear /usr/local/bin
# We don't need a zip list for this; we know what the binary is named.
if [ -f "/usr/local/bin/gradle" ]; then
    rm -v "/usr/local/bin/gradle"
else
    echo "Binary /usr/local/bin/gradle not found."
fi

# 2. Clear /usr/local/lib
# Since we know the extraction was meant to be the 'lib' folder of Gradle
if [ -d "/usr/local/lib" ]; then
    echo "Scanning /usr/local/lib for Gradle files..."
    
    # We use the zip to find the specific files that SHOULD be there
    # If this returns nothing, we'll know immediately.
    FILES=$(unzip -Z1 "$GRADLE_ZIP" "gradle-8.10.2/lib/*" | sed 's|^gradle-8.10.2/lib/||' | sort -r)
    
    if [ -z "$FILES" ]; then
        echo "ERROR: Could not read contents of $GRADLE_ZIP. Is unzip installed?"
        exit 1
    fi

    for rel_path in $FILES; do
        [ -z "$rel_path" ] && continue
        target="/usr/local/lib/${rel_path%/}"
        
        if [ -e "$target" ] || [ -L "$target" ]; then
            if [ -d "$target" ]; then
                rmdir -v "$target" 2>/dev/null || true
            else
                rm -v "$target"
            fi
        fi
    done
fi

echo "--- Removal Complete ---"



# Note: Android SDK already removed above; no further action needed.

echo "Uninstall complete."


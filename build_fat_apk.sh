#!/bin/bash

# Exit on any error
set -e

echo "==================================================="
echo "🚀 Building Nua Fat APK with bundled AI models..."
echo "==================================================="

echo "1. Building the Android App Bundle (AAB)..."
./gradlew bundleDebug

echo "2. Generating Universal Fat APK using Bundletool..."
# We use bundletool to extract a massive universal APK from the AAB
# that contains all install-time asset packs (the models)
java -jar bundletool.jar build-apks \
  --bundle=app/build/outputs/bundle/debug/app-debug.aab \
  --output=nua-fat-apk.apks \
  --mode=universal \
  --overwrite

echo "3. Extracting universal.apk from the APKS archive..."
# The .apks file is just a zip archive containing universal.apk
LATEST_VERSION=$(ls nua-fat-testing-v*.apk 2>/dev/null | grep -o 'v[0-9]*\.apk' | grep -o '[0-9]*' | sort -n | tail -1)
if [ -z "$LATEST_VERSION" ]; then
    NEXT_VERSION=1
else
    NEXT_VERSION=$((LATEST_VERSION + 1))
fi
APK_NAME="nua-fat-testing-v${NEXT_VERSION}.apk"

unzip -p nua-fat-apk.apks universal.apk > "$APK_NAME"

echo "4. Cleaning up intermediate bundletool artifacts..."
rm -f nua-fat-apk.apks

echo "==================================================="
echo "✅ Success! Your Fat APK is ready: $APK_NAME"
echo "You can now install it on your device using:"
echo "adb install -r $APK_NAME"
echo "==================================================="

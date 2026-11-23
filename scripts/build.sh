#!/usr/bin/env bash
# Gradle-free 构建：native(ndk-build) + java(javac) + d8 + aapt2 + zip + zipalign + apksigner
# 用法: ANDROID_HOME=/path/to/sdk bash scripts/build.sh [debug|release]
set -euo pipefail

MODE="${1:-debug}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$ROOT/app"
NATIVE="$ROOT/native"
OUT="$ROOT/build"
CACHE="$ROOT/.build-cache"

ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$ANDROID_HOME" ]; then
  echo "ERROR: ANDROID_HOME / ANDROID_SDK_ROOT 未设置" >&2
  exit 1
fi
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
BT="$ANDROID_HOME/build-tools/34.0.0"
NDK="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"

for p in "$ANDROID_JAR" "$BT/d8" "$BT/aapt2" "$BT/zipalign" "$BT/apksigner" "$NDK/ndk-build"; do
  [ -e "$p" ] || { echo "ERROR: 缺少 $p" >&2; exit 1; }
done

API_VERSION="102.0.0"
API_AAR="$CACHE/libxposed-api-$API_VERSION.aar"
API_JAR="$CACHE/libxposed-api.jar"

mkdir -p "$OUT" "$CACHE"

echo "==> [1/7] libxposed api $API_VERSION"
if [ ! -f "$API_JAR" ]; then
  if [ ! -f "$API_AAR" ]; then
    curl -fsSL --retry 3 -o "$API_AAR" \
      "https://repo1.maven.org/maven2/io/github/libxposed/api/$API_VERSION/api-$API_VERSION.aar"
  fi
  unzip -p "$API_AAR" classes.jar > "$API_JAR"
fi
ls -l "$API_JAR"

echo "==> [2/7] native (ndk-build, arm64-v8a)"
rm -rf "$OUT/native-obj" "$OUT/libs"
"$NDK/ndk-build" -C "$NATIVE" \
  NDK_OUT="$OUT/native-obj" \
  NDK_LIBS_OUT="$OUT/libs" \
  APP_ABI=arm64-v8a \
  -j"$(nproc 2>/dev/null || echo 4)"

echo "==> [3/7] java (javac -> classes.jar)"
rm -rf "$OUT/classes" "$OUT/classes.jar" "$OUT/classes.dex"
mkdir -p "$OUT/classes"
find "$APP/java" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -nowarn \
  -cp "$ANDROID_JAR:$API_JAR" \
  -d "$OUT/classes" @"$OUT/sources.txt"
(cd "$OUT/classes" && jar cf "$OUT/classes.jar" .)

echo "==> [4/7] d8"
"$BT/d8" --min-api 26 --lib "$ANDROID_JAR" --output "$OUT" "$OUT/classes.jar"
ls -l "$OUT/classes.dex"

echo "==> [5/7] aapt2 link"
rm -f "$OUT/base.apk"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 34

echo "==> [6/7] 打包 classes.dex + so + META-INF"
rm -f "$OUT/unsigned.apk"
python3 - "$OUT/base.apk" "$OUT/unsigned.apk" "$OUT/classes.dex" \
  "$OUT/libs/arm64-v8a/libazcap.so" "$APP/resources" <<'PY'
import os, sys, zipfile

base, out, dex, so, resdir = sys.argv[1:6]
with zipfile.ZipFile(base) as zin, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        zout.writestr(item, zin.read(item.filename))
    zout.write(dex, "classes.dex")
    zout.write(so, "lib/arm64-v8a/libazcap.so")
    for root, _dirs, files in os.walk(resdir):
        for name in files:
            path = os.path.join(root, name)
            zout.write(path, os.path.relpath(path, resdir))
for n in ("classes.dex", "lib/arm64-v8a/libazcap.so"):
    with zipfile.ZipFile(out) as z:
        assert n in z.namelist(), n
print("apk entries:", ", ".join(sorted(zipfile.ZipFile(out).namelist())))
PY

echo "==> [7/7] zipalign + 签名"
KS="${KEYSTORE:-$ROOT/signing/dev.keystore}"
KS_PASS="${KEYSTORE_PASS:-azcap123}"
KEY_ALIAS="${KEY_ALIAS:-azcap}"
KEY_PASS="${KEY_PASS:-azcap123}"
if [ ! -f "$KS" ]; then
  echo "ERROR: keystore 不存在: $KS" >&2
  exit 1
fi

"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
rm -f "$OUT/AzurLaneCapture.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" \
  --ks-key-alias "$KEY_ALIAS" --key-pass "pass:$KEY_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT/AzurLaneCapture.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify --print-certs "$OUT/AzurLaneCapture.apk" | head -20

cp "$OUT/AzurLaneCapture.apk" "$OUT/AzurLaneCapture-$MODE.apk"
echo "OK: $OUT/AzurLaneCapture-$MODE.apk"
ls -l "$OUT/AzurLaneCapture-$MODE.apk"

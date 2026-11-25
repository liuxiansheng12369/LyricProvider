#!/usr/bin/env bash
# 构建 thirdparty/ 下的第三方词幕源，产物统一命名 <providerId>-release.apk
# 用法：build_thirdparty.sh <输出目录>
# 需要：ANDROID_HOME；release 签名通过 RELEASE_STORE_FILE / RELEASE_* 环境变量传入（可缺省，缺省出未签名包）
set -euo pipefail

OUT=${1:-tp-apks}
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[ -n "$SDK" ] || { echo "::error::找不到 ANDROID_HOME / ANDROID_SDK_ROOT"; exit 1; }
BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
[ -n "$BT" ] || { echo "::error::找不到 build-tools"; exit 1; }

mkdir -p "$OUT"

build_one() {
  local id=$1 module=$2
  local dir="thirdparty/$id"
  echo "::group::构建 $id（$module）"
  echo "sdk.dir=$SDK" > "$dir/local.properties"
  (
    cd "$dir"
    chmod +x gradlew
    ./gradlew "$module" --console=plain --stacktrace \
      -Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8"
  )
  echo "::endgroup::"
}

# 取该模块最新产出的 apk（文件名随签名配置而变：xxx-release.apk / xxx-release-unsigned.apk）
newest_apk() {
  local dir=$1
  local apk
  apk=$(ls -t "$dir"/*.apk 2>/dev/null | head -1 || true)
  [ -n "$apk" ] || { echo "::error::$dir 下没有 apk"; exit 1; }
  echo "$apk"
}

finish() {
  local id=$1 src=$2
  local dst="$OUT/$id-release.apk"
  "$BT/zipalign" -f -p 4 "$src" "$dst"
  if [ -n "${RELEASE_STORE_FILE:-}" ] && [ -f "${RELEASE_STORE_FILE}" ]; then
    "$BT/apksigner" sign \
      --ks "$RELEASE_STORE_FILE" \
      --ks-key-alias "${RELEASE_KEY_ALIAS:?缺少 RELEASE_KEY_ALIAS}" \
      --ks-pass "pass:${RELEASE_STORE_PASSWORD:?缺少 RELEASE_STORE_PASSWORD}" \
      --key-pass "pass:${RELEASE_KEY_PASSWORD:?缺少 RELEASE_KEY_PASSWORD}" \
      --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
      "$dst"
    echo "$id 已签名"
    "$BT/apksigner" verify --print-certs "$dst" | grep -i 'SHA-256 digest' || true
  else
    echo "::warning::$id 未提供签名配置，产出未签名包，仅用于构建自检"
  fi
  "$BT/aapt2" dump badging "$dst" | head -1
}

# 1) 酷狗概念版
build_one kugou-lite ":app:assembleRelease"
finish kugou-lite "$(newest_apk thirdparty/kugou-lite/app/build/outputs/apk/release)"

# 2) Mei
build_one mei ":mei-provider:assembleRelease"
finish mei "$(newest_apk thirdparty/mei/mei-provider/build/outputs/apk/release)"

# 3) PixelPlayer
build_one pixelplay ":app:assembleRelease"
finish pixelplay "$(newest_apk thirdparty/pixelplay/app/build/outputs/apk/release)"

ls -lh "$OUT"

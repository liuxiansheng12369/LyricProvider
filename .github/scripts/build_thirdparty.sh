#!/usr/bin/env bash
# 构建 thirdparty/ 下的第三方词幕源，产物统一命名 <providerId>-release.apk
# 用法：build_thirdparty.sh <输出目录>
# 需要：ANDROID_HOME；release 签名通过 RELEASE_STORE_FILE / RELEASE_* 环境变量传入（可缺省，缺省则出未签名包）
set -euo pipefail

OUT=${1:-tp-apks}
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[ -n "$SDK" ] || { echo "::error::找不到 ANDROID_HOME / ANDROID_SDK_ROOT"; exit 1; }
BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
[ -n "$BT" ] || { echo "::error::找不到 build-tools"; exit 1; }

mkdir -p "$OUT"

build_one() {
  local id=$1 module=$2 artifact=$3
  local dir="thirdparty/$id"
  echo "::group::构建 $id（$module）"
  echo "sdk.dir=$SDK" > "$dir/local.properties"
  (
    cd "$dir"
    chmod +x gradlew
    ./gradlew "$module" --console=plain --stacktrace \
      -Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8"
  )
  local apk="$dir/$artifact"
  if [ ! -f "$apk" ]; then
    echo "::error::$id 没有产出 $artifact"
    exit 1
  fi
  echo "产物：$apk"
  echo "::endgroup::"
}

assemble_unsigned() {
  local id=$1 artifact=$2
  local src="thirdparty/$id/$artifact"
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
  else
    echo "::warning::$id 未提供签名配置，产出未签名包，仅用于构建自检"
  fi
  "$BT/aapt2" dump badging "$dst" | head -1
  echo "$dst"
}

build_one kugou-lite ":app:assembleRelease" "app/build/outputs/apk/release/app-release-unsigned.apk"
assemble_unsigned kugou-lite "app/build/outputs/apk/release/app-release-unsigned.apk"

build_one mei ":mei-provider:assembleRelease" "mei-provider/build/outputs/apk/release/mei-provider-release.apk"
cp "thirdparty/mei/mei-provider/build/outputs/apk/release/mei-provider-release.apk" "$OUT/mei-release.apk"
"$BT/aapt2" dump badging "$OUT/mei-release.apk" | head -1

build_one pixelplay ":app:assembleRelease" "app/build/outputs/apk/release/app-release-unsigned.apk"
assemble_unsigned pixelplay "app/build/outputs/apk/release/app-release-unsigned.apk"

ls -lh "$OUT"

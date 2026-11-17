#!/usr/bin/env bash
# 国内加速版：给 GitHub release/raw 下载加 gh 代理前缀。
# 放到 src/wow-forever-android-main/tools/ 下运行，或 cd 到 forever 根目录后 ./tools/... 调用。
# 用法：./fetch-components-cn.sh [本地已有组件目录]
#   - 环境变量 GH_PROXY：代理前缀，默认 https://ghfast.top/；失效时换 https://gh-proxy.com/、https://ghproxy.net/ 等
#   - 环境变量 COMPONENTS_URL：覆盖 release 基础地址
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$REPO_DIR/app/src/main/assets/bundled_components"
RELEASE_URL="${COMPONENTS_URL:-https://github.com/jaredgei/wow-forever-android/releases/download/components-v1}"
GH_PROXY="${GH_PROXY:-https://ghfast.top/}"
FILES=(
  proton-11.0-1-arm64ec.txz
  dxvk-2.4.1-wow-aarch64-test.wcp
  turnip-wow-scheduler-test.zip
  turnip-V32-RP6sched.zip
)

mkdir -p "$DEST"
for f in "${FILES[@]}"; do
  if [ -s "$DEST/$f" ]; then
    echo "have $f"
  elif [ -n "${1:-}" ] && [ -s "$1/$f" ]; then
    cp "$1/$f" "$DEST/$f"
    echo "copied $f"
  else
    if [ "$f" = "turnip-V32-RP6sched.zip" ]; then
      url="${GH_PROXY}https://raw.githubusercontent.com/arusiasotto/wow-forever-a840/main/driver/turnip-V32-RP6sched.zip"
      curl -fL --retry 3 -o "$DEST/$f.part" "$url"
      mv "$DEST/$f.part" "$DEST/$f"
      echo "downloaded $f"
    elif [ "$f" = "proton-11.0-1-arm64ec.txz" ]; then
      # FrontMage (WoWBox) Wine runtime，从 WoWBox 的 runtime-assets tarball 里抽出。
      curl -fL --retry 3 -o /tmp/wowbox-runtime.tar.gz "${GH_PROXY}https://github.com/FrontMage/WoWBox/releases/download/v0.3.0/WoWBox-v0.3.0-runtime-assets.tar.gz"
      tar -xzf /tmp/wowbox-runtime.tar.gz -C /tmp "app/src/main/assets/wine-runtime-wowbox-rwoa-openfonts-6d87ba31.txz"
      mv "/tmp/app/src/main/assets/wine-runtime-wowbox-rwoa-openfonts-6d87ba31.txz" "$DEST/$f"
      rm -f /tmp/wowbox-runtime.tar.gz
      echo "downloaded $f (extracted from WoWBox runtime)"
    else
      url="${GH_PROXY}${RELEASE_URL}/$f"
      curl -fL --retry 3 -o "$DEST/$f.part" "$url"
      mv "$DEST/$f.part" "$DEST/$f"
      echo "downloaded $f"
    fi
  fi
done

(cd "$DEST" && shasum -a 256 -c "$REPO_DIR/tools/components.sha256")

#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$REPO_DIR/app/src/main/assets/bundled_components"
RELEASE_URL="${COMPONENTS_URL:-https://github.com/jaredgei/wow-forever-android/releases/download/components-v1}"
# FrontMage (WoWBox) runtime — its Wine build has the ARM64 socket fix the GameNative Proton lacks.
WOWBOX_RUNTIME_URL="https://github.com/FrontMage/WoWBox/releases/download/v0.3.0/WoWBox-v0.3.0-runtime-assets.tar.gz"
WOWBOX_WINE_MEMBER="app/src/main/assets/wine-runtime-wowbox-rwoa-openfonts-6d87ba31.txz"
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
    elif [ "$f" = "turnip-V32-RP6sched.zip" ]; then
        curl -fL --retry 3 -o "$DEST/$f.part" "https://raw.githubusercontent.com/arusiasotto/wow-forever-a840/main/driver/turnip-V32-RP6sched.zip"
        mv "$DEST/$f.part" "$DEST/$f"
        echo "downloaded $f"
    elif [ "$f" = "proton-11.0-1-arm64ec.txz" ]; then
        # The FrontMage Wine runtime is bundled inside WoWBox's runtime-assets tarball.
        curl -fL --retry 3 -o /tmp/wowbox-runtime.tar.gz "$WOWBOX_RUNTIME_URL"
        tar -xzf /tmp/wowbox-runtime.tar.gz -C /tmp "$WOWBOX_WINE_MEMBER"
        mv "/tmp/$WOWBOX_WINE_MEMBER" "$DEST/$f"
        rm -f /tmp/wowbox-runtime.tar.gz
        echo "downloaded $f (extracted from WoWBox runtime)"
    else
        curl -fL --retry 3 -o "$DEST/$f.part" "$RELEASE_URL/$f"
        mv "$DEST/$f.part" "$DEST/$f"
        echo "downloaded $f"
    fi
done

(cd "$DEST" && shasum -a 256 -c "$REPO_DIR/tools/components.sha256")

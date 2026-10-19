#!/usr/bin/env bash
# OpenChatCut Android — guest packer (run in GitHub Actions).
# Builds everything the APK ships in assets/occ/:
#   guest/rootfs.tar.gz       Debian trixie arm64 base (debootstrap, Debian archive)
#   guest/node.tar.gz         Node 24 arm64 runtime (official tarball)
#   guest/guest-server.mjs    esbuild bundle of upstream desktop/embedded-server.ts
#   guest/bootstrap.sh        guest first-run script
#   guest/package.json(+lock) for guest `npm ci`
#   guest/dist/               upstream renderer build output
#   host/bin/proot            Termux arm64 proot (bionic; device-native)
#   host/libexec/proot/loader(+32)  Termux external exec loaders
set -euo pipefail

ARCH=arm64
SUITE=trixie
SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$SRC_DIR/android/app/src/main/assets/occ"
STAGE=/tmp/occ-rootfs-build

mkdir -p "$OUT/guest" "$OUT/host/bin" "$OUT/host/libexec/proot"


# ── 1) Debian trixie arm64 base (debootstrap; unpack-only, no guest configure) ──
if [ "${SKIP_ROOTFS_BUILD:-0}" = "1" ] && [ -s "$OUT/guest/rootfs.tar.gz" ]; then
  echo "rootfs.tar.gz cached"
else
  rm -rf "$STAGE"; mkdir -p "$STAGE"
  DEBOOT="debootstrap"
  HOSTARCH="$(dpkg --print-architecture 2>/dev/null || uname -m)"
  if [ "$HOSTARCH" != "$ARCH" ] && command -v qemu-debootstrap > /dev/null 2>&1; then
    DEBOOT="qemu-debootstrap"   # x86_64 runner building an arm64 rootfs
  fi
  echo "host arch: $HOSTARCH  target: $ARCH  debootstrap: $DEBOOT"
  if ! "$DEBOOT" --variant=minbase --include=libstdc++6 --arch="$ARCH" "$SUITE" \
      "$STAGE/rootfs" http://deb.debian.org/debian > /tmp/occ-pack-rootfs.log 2>&1; then
    echo "debootstrap FAILED — last 60 lines:"
    tail -60 /tmp/occ-pack-rootfs.log
    exit 1
  fi
  echo nameserver 1.1.1.1 > "$STAGE/rootfs/etc/resolv.conf"
  echo "OpenChatCut Android" > "$STAGE/rootfs/etc/hostname"
  printf '127.0.0.1\tlocalhost\n' > "$STAGE/rootfs/etc/hosts"
  (cd "$STAGE/rootfs" && tar czf "$OUT/guest/rootfs.tar.gz" .)
  echo "rootfs.tar.gz: $(du -h "$OUT/guest/rootfs.tar.gz" | cut -f1)"
  rm -rf "$STAGE/rootfs"
fi

# ── 2) Node 24 arm64 (official tarball → tar.gz) ──
if [ ! -s "$OUT/guest/node.tar.gz" ]; then
  NODE_VER=v24.10.0
  rm -rf "$STAGE"; mkdir -p "$STAGE/node-x"
  curl -fsSL "https://nodejs.org/dist/${NODE_VER}/node-${NODE_VER}-linux-arm64.tar.gz" -o "$STAGE/node.tgz"
  tar xzf "$STAGE/node.tgz" -C "$STAGE/node-x"
  (cd "$STAGE/node-x/node-${NODE_VER}-linux-arm64" && tar czf "$OUT/guest/node.tar.gz" .)
  rm -rf "$STAGE/node-x" "$STAGE/node.tgz"
fi

# ── 3) guest server bundle (esbuild, upstream server code only) ──
npx --no-install esbuild "$SRC_DIR/mobile/guest/guest-server.mjs" \
  --bundle --platform=node --format=esm --packages=external \
  --outfile="$OUT/guest/guest-server.mjs"

cp "$SRC_DIR/mobile/guest/bootstrap.sh" "$OUT/guest/bootstrap.sh"
chmod +x "$OUT/guest/bootstrap.sh"
cp "$SRC_DIR/package.json" "$SRC_DIR/package-lock.json" "$OUT/guest/"

# ── 4) renderer output (dist/) — served by upstream's own static middleware ──
if [ -d "$SRC_DIR/dist" ] && [ -n "$(ls -A "$SRC_DIR/dist" 2>/dev/null)" ]; then
  rm -rf "$OUT/guest/dist"
  mkdir -p "$OUT/guest/dist"
  cp -a "$SRC_DIR/dist/." "$OUT/guest/dist/"
else
  echo "warning: $SRC_DIR/dist missing — build the renderer before packing" >&2
fi

# ── 5) proot: Termux arm64 build (bionic) + external exec loaders ──
rm -rf "$STAGE/proot"; mkdir -p "$STAGE/proot"
python3 "$SRC_DIR/tools/fetch-proot.py" "$STAGE/proot"

( cd "$STAGE/proot" && ar p proot.deb data.tar.xz | tar xJ )
PROOTDIR="$STAGE/proot/data/data/com.termux/files/usr"
cp "$PROOTDIR/bin/proot" "$OUT/host/bin/proot"
cp "$PROOTDIR/libexec/proot/loader" "$OUT/host/libexec/proot/loader"
if [ -f "$PROOTDIR/libexec/proot/loader32" ]; then
  cp "$PROOTDIR/libexec/proot/loader32" "$OUT/host/libexec/proot/loader32"
else
  : > "$OUT/host/libexec/proot/loader32"   # arm64-only guest; 32-bit loader unused
fi
if [ -f "$STAGE/proot/talloc.deb" ]; then
  ( cd "$STAGE/proot" && ar p talloc.deb data.tar.xz | tar xJ ) || true
fi

# Termux deb payloads carry 0700 dirs/files from their tar — normalize, otherwise
# the runner user (and gradle) cannot even stat them.
find "$OUT" -type d -exec chmod 755 {} +
find "$OUT" -type f -exec chmod 644 {} +
chmod 755 "$OUT/host/bin/proot" "$OUT/host/libexec/proot/loader" "$OUT/host/libexec/proot/loader32"

# ── 6) Android skin css (injected by the host shell) ──
cp "$SRC_DIR/mobile/android/android-skin.css" "$OUT/android-skin.css"

echo "packed:"; du -sh "$OUT"/* "$OUT/guest"/* 2>/dev/null | sort -h

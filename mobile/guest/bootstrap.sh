#!/bin/sh
# OpenChatCut Android — guest bootstrap (idempotent; runs inside proot Debian).
# One-time dependency install, then boots upstream's own embedded server.
set -u
cd /occ

export PATH="/occ/node/bin:/usr/bin:/bin"
export HOME=/occ/data
mkdir -p /occ/data/tmp
export TMPDIR=/occ/data/tmp
export npm_config_update_notifier=false
export npm_config_fund=false
export npm_config_audit=false
# npm >= 11.7 gates lifecycle scripts; the ones our runtime deps need are trusted.
export npm_config_dangerously_allow_all_scripts=true

say() { echo "OCC_LINE $*"; }

if [ ! -x /occ/node/bin/node ]; then
  echo "OCC_FAIL missing node runtime in /occ/node"
  exit 4
fi
say "node $(/occ/node/bin/node -v)"

if [ ! -f node_modules/.package-lock.json ]; then
  say "installing dependencies (one time, a few minutes)…"
  if npm ci --omit=dev --no-audit --no-fund --loglevel=error > /occ/data/npm.log 2>&1; then
    say "dependencies installed"
  else
    say "npm ci failed — trying plain install"
    if npm install --omit=dev --no-audit --no-fund --loglevel=error >> /occ/data/npm.log 2>&1; then
      say "dependencies installed"
    else
      tail -30 /occ/data/npm.log
      echo "OCC_FAIL dependency install failed (see /occ/data/npm.log)"
      exit 5
    fi
  fi
else
  say "dependencies already installed"
fi

say "starting OpenChatCut server…"
exec node guest-server.mjs

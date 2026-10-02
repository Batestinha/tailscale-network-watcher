#!/data/data/com.termux/files/usr/bin/sh
set -eu

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
cd "$PROJECT_DIR"

if [ -x /data/data/com.termux/files/usr/bin/aapt2 ]; then
    set -- -Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2 "$@"
fi

exec ./gradlew "$@" test assembleRelease

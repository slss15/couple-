#!/usr/bin/env bash
# Builds couple-server-windows-x64-<version>.zip on Linux (CI: ubuntu-latest) by cross-jlinking a Windows JRE.
#
#   deploy/windows/build-package.sh <version> [out-dir]
#
# Expects (run from the repo root, after):
#   cd backend && ./gradlew -PappVersion=<version> :installDist :updater:installDist
#   cd admin && npm ci && npm run build
# Downloads (sha256-pinned in versions.env, cached in $CACHE_DIR): Temurin JDK for this Linux host and for Windows
# x64 (same release, so jlink accepts the Windows jmods), and WinSW.
set -euo pipefail

version=${1:?usage: build-package.sh <version> [out-dir]}
out=${2:-dist-windows}
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
cache=${CACHE_DIR:-$root/.cache/windows-package}
case "$version" in *[!0-9A-Za-z._+-]*|"") echo "invalid version '$version'" >&2; exit 2 ;; esac

# shellcheck source=deploy/windows/versions.env disable=SC1091
. "$here/versions.env"
mkdir -p "$cache" "$out"

fetch() { # url sha256 dest
  local url=$1 sum=$2 dest=$3
  if [ ! -f "$dest" ] || ! echo "$sum  $dest" | sha256sum -c --status; then
    echo "download $url"
    curl -fsSL --retry 3 --retry-delay 5 -o "$dest.part" "$url"
    mv "$dest.part" "$dest"
  fi
  echo "$sum  $dest" | sha256sum -c --quiet || { echo "::error::sha256 mismatch for $url" >&2; exit 1; }
}

rel_enc=${TEMURIN_RELEASE//+/%2B}
ver_us=${TEMURIN_RELEASE#jdk-}; ver_us=${ver_us//+/_}
base="https://github.com/adoptium/temurin21-binaries/releases/download/$rel_enc"
case "$(uname -m)" in
  x86_64) host_arch=x64; host_sum=$TEMURIN_LINUX_X64_SHA256 ;;
  aarch64|arm64) host_arch=aarch64; host_sum=$TEMURIN_LINUX_AARCH64_SHA256 ;;
  *) echo "unsupported build host $(uname -m)" >&2; exit 2 ;;
esac
fetch "$base/OpenJDK21U-jdk_${host_arch}_linux_hotspot_${ver_us}.tar.gz" "$host_sum" "$cache/jdk-linux-$host_arch.tgz"
fetch "$base/OpenJDK21U-jdk_x64_windows_hotspot_${ver_us}.zip" "$TEMURIN_WINDOWS_X64_SHA256" "$cache/jdk-windows-x64.zip"
fetch "https://github.com/winsw/winsw/releases/download/$WINSW_VERSION/$WINSW_ASSET" "$WINSW_SHA256" "$cache/WinSW.exe"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/host" "$work/win"
tar xzf "$cache/jdk-linux-$host_arch.tgz" -C "$work/host"
unzip -q "$cache/jdk-windows-x64.zip" -d "$work/win"
host_jdk=$(echo "$work"/host/jdk-*)
win_jdk=$(echo "$work"/win/jdk-*)

backend_lib="$root/backend/build/install/couple-backend/lib"
updater_lib="$root/backend/updater/build/install/couple-updater/lib"
admin_dist="$root/admin/dist"
for d in "$backend_lib" "$updater_lib" "$admin_dist"; do
  [ -d "$d" ] || { echo "::error::missing $d (build backend/updater/admin first)" >&2; exit 1; }
done

# Guard: every module jdeps finds must be in JLINK_MODULES (new dependency => update versions.env).
need=$(for lib in "$backend_lib" "$updater_lib"; do
  # jdeps prints split-package warnings on stdout; the module list is the last line.
  "$host_jdk/bin/jdeps" --ignore-missing-deps --print-module-deps --multi-release 21 --class-path "$lib/*" "$lib"/*.jar \
    | grep -E '^(java|jdk)\.[a-z.,]+$' | tail -n1
done | tr ',' '\n' | sort -u)
[ -n "$need" ] || { echo "::error::jdeps produced no module list" >&2; exit 1; }
missing=$(comm -23 <(echo "$need") <(echo "$JLINK_MODULES" | tr ',' '\n' | sort -u) || true)
if [ -n "$missing" ]; then
  echo "::error::JRE would miss modules: $(echo "$missing" | tr '\n' ' ')- add them to deploy/windows/versions.env JLINK_MODULES" >&2
  exit 1
fi

name="couple-server-windows-x64-$version"
pkg="$work/$name"
mkdir -p "$pkg"
# --strip-java-debug-attributes, not --strip-debug: native stripping would run Linux objcopy on Windows DLLs.
"$host_jdk/bin/jlink" --module-path "$win_jdk/jmods" --add-modules "$JLINK_MODULES" \
  --strip-java-debug-attributes --no-header-files --no-man-pages --compress=zip-6 --output "$pkg/jre"
[ -f "$pkg/jre/bin/java.exe" ] || { echo "::error::jlink did not produce a Windows JRE" >&2; exit 1; }

mkdir -p "$pkg/app/lib" "$pkg/updater/lib" "$pkg/winsw" "$pkg/service" "$pkg/config" "$pkg/cloudflared"
cp "$backend_lib"/*.jar "$pkg/app/lib/"
# Linux/macOS-only native transports are never loaded on Windows.
rm -f "$pkg"/app/lib/netty-transport-native-epoll-*-linux-*.jar "$pkg"/app/lib/netty-transport-native-kqueue-*.jar
cp "$updater_lib"/*.jar "$pkg/updater/lib/"
cp -r "$admin_dist" "$pkg/admin"
# Same as admin/Dockerfile: load the runtime config the backend serves at /config.js.
if ! grep -q '/config.js' "$pkg/admin/index.html"; then
  sed -i 's#</head>#<script src="/config.js"></script></head>#' "$pkg/admin/index.html"
fi
grep -q '/config.js' "$pkg/admin/index.html"
cp "$cache/WinSW.exe" "$pkg/winsw/WinSW.exe"
cp "$here"/service/*.xml.in "$pkg/service/"
cp "$here/cloudflared/config.yml.in" "$pkg/cloudflared/"
cp "$here/config/couple.env.example" "$pkg/config/"
cp "$here/install.ps1" "$here/uninstall.ps1" "$here/versions.env" "$pkg/"
printf '%s\n' "$version" > "$pkg/VERSION"
cat > "$pkg/README.txt" <<TXT
Couple server $version for Windows x64.
Install/upgrade (elevated PowerShell):  powershell -ExecutionPolicy Bypass -File .\\install.ps1
Full guide: https://github.com/slss15/couple-/blob/main/deploy/windows/SETUP-windows.md
TXT

# Deterministic-ish zip with forward slashes, created by Python (no dependency on the zip CLI).
python3 - "$work" "$name" "$out/$name.zip" <<'PY'
import os, sys, zipfile
work, name, dest = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for dirpath, dirnames, filenames in os.walk(os.path.join(work, name)):
        dirnames.sort()
        for f in sorted(filenames):
            full = os.path.join(dirpath, f)
            arc = os.path.relpath(full, work).replace(os.sep, "/")
            info = zipfile.ZipInfo(arc, date_time=(2020, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            with open(full, "rb") as fh:
                z.writestr(info, fh.read(), compresslevel=9)
PY
(cd "$out" && sha256sum "$name.zip" > "$name.zip.sha256")
echo "built $out/$name.zip ($(du -h "$out/$name.zip" | cut -f1)), sha256 $(cut -d' ' -f1 "$out/$name.zip.sha256")"

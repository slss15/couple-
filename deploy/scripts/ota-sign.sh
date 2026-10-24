#!/usr/bin/env bash
# Signs a release manifest with the OTA release key.
#   usage: OTA_SIGNING_KEY=<base64 seed> [OTA_PUBLIC_KEY=<base64 pub>] ota-sign.sh release.json [keydir]
# Writes release.json.sig = standard base64 of the Ed25519 signature over the exact bytes of release.json.
# The key is materialised as a PKCS#8 file in keydir (default $RUNNER_TEMP or a mktemp dir) and deleted on exit.
# When OTA_PUBLIC_KEY is set, the signature is verified against it and a mismatch fails the script.
set -euo pipefail
manifest=${1:?usage: ota-sign.sh release.json [keydir]}
: "${OTA_SIGNING_KEY:?OTA_SIGNING_KEY is not set}"
keydir=${2:-${RUNNER_TEMP:-$(mktemp -d)}}
key="$keydir/ota-signing-key.der"
pubder="$keydir/ota-public-key.der"
sigbin="$keydir/ota-manifest.sig.bin"
cleanup() { rm -f "$key" "$pubder" "$sigbin"; }
trap cleanup EXIT
umask 077
seed_len=$(printf '%s' "$OTA_SIGNING_KEY" | base64 -d 2>/dev/null | wc -c)
[ "$seed_len" -eq 32 ] || { echo "::error::OTA_SIGNING_KEY must be base64 of 32 bytes" >&2; exit 1; }
# PKCS#8 DER = fixed 16-byte Ed25519 prefix + 32-byte seed.
{ printf '\x30\x2e\x02\x01\x00\x30\x05\x06\x03\x2b\x65\x70\x04\x22\x04\x20'; printf '%s' "$OTA_SIGNING_KEY" | base64 -d; } > "$key"
openssl pkeyutl -sign -rawin -inkey "$key" -keyform DER -in "$manifest" -out "$sigbin"
base64 -w0 < "$sigbin" > "$manifest.sig"
derived=$(openssl pkey -inform DER -in "$key" -pubout -outform DER | tail -c 32 | base64 | tr -d '\n')
if [ -n "${OTA_PUBLIC_KEY:-}" ]; then
  if [ "$derived" != "$OTA_PUBLIC_KEY" ]; then
    echo "::error::OTA_SIGNING_KEY does not match OTA_PUBLIC_KEY (the key pinned in the app)" >&2; exit 1
  fi
  # Independent check with the public key only.
  # SPKI DER = fixed 12-byte Ed25519 prefix + 32-byte public key.
  { printf '\x30\x2a\x30\x05\x06\x03\x2b\x65\x70\x03\x21\x00'; printf '%s' "$OTA_PUBLIC_KEY" | base64 -d; } > "$pubder"
  openssl pkeyutl -verify -rawin -pubin -inkey "$pubder" -keyform DER -in "$manifest" -sigfile "$sigbin" >/dev/null
fi
echo "signed $manifest -> $manifest.sig (public key $derived)"

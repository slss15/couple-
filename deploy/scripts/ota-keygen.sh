#!/usr/bin/env bash
# Generates the OTA release-manifest signing keypair (Ed25519), distinct from the server's bootstrap key.
#   OTA_SIGNING_KEY  = base64 of the raw 32-byte seed      -> GitHub secret (ideally in a protected Environment)
#   OTA_PUBLIC_KEY   = base64 of the raw 32-byte public key -> GitHub variable (+ optional backend env OTA_PUBLIC_KEY)
# Nothing is written to disk; the private key only ever exists in this process's pipes.
# Requires OpenSSL >= 1.1.1.
set -euo pipefail
command -v openssl >/dev/null || { echo "openssl not found" >&2; exit 1; }
der=$(openssl genpkey -algorithm ed25519 -outform DER | base64 | tr -d '\n')
# PKCS#8 DER for Ed25519 is a fixed 16-byte prefix followed by the 32-byte seed.
seed=$(printf '%s' "$der" | base64 -d | tail -c 32 | base64 | tr -d '\n')
pub=$(printf '%s' "$der" | base64 -d | openssl pkey -inform DER -pubout -outform DER | tail -c 32 | base64 | tr -d '\n')
cat <<OUT
OTA_SIGNING_KEY=$seed
OTA_PUBLIC_KEY=$pub

1. GitHub -> Settings -> Secrets and variables -> Actions:
   - Secret   OTA_SIGNING_KEY = (value above)   (recommended: put it in Environment "ota-release" with yourself as required reviewer)
   - Variable OTA_PUBLIC_KEY  = (value above)
2. Optional: backend env OTA_PUBLIC_KEY=(value above) so the server also rejects unsigned/forged release.json.
3. Store OTA_SIGNING_KEY offline (password manager). Losing it means shipping a release that pins a new public key.
OUT

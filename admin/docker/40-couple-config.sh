#!/bin/sh
# Writes runtime config for the SPA. Values come from container env, so one image fits any domain.
set -eu
esc() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
cat > /tmp/couple-config.js <<JS
window.__COUPLE_CONFIG__ = {
  apiBase: "$(esc "${COUPLE_API_BASE:-}")",
  version: "$(esc "${COUPLE_VERSION:-dev}")"
};
JS
echo "couple-admin: wrote /tmp/couple-config.js (apiBase='${COUPLE_API_BASE:-}')"

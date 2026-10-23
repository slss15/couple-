# syntax=docker/dockerfile:1
# One image for any domain: the SPA is built once with VITE_API_BASE="" (same-origin API)
# and nginx proxies /api to the backend. Per-deploy settings are rendered at container
# start into /config.js (window.__COUPLE_CONFIG__), see docker/40-couple-config.sh.

# ---- build (runs on the build platform; output is static files) ----
FROM --platform=$BUILDPLATFORM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build \
 && sed -i 's#</head>#<script src="/config.js"></script></head>#' dist/index.html \
 && grep -q '/config.js' dist/index.html

# ---- runtime ----
FROM nginxinc/nginx-unprivileged:1.27-alpine
ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="couple-admin" \
      org.opencontainers.image.source="https://github.com/slss15/couple-" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"
ENV COUPLE_VERSION=${VERSION} \
    COUPLE_API_BASE="" \
    BACKEND_UPSTREAM="backend:8080"
COPY docker/nginx.conf /etc/nginx/templates/default.conf.template
COPY --chmod=0755 docker/40-couple-config.sh /docker-entrypoint.d/40-couple-config.sh
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1

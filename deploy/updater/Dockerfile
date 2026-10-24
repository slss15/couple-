# syntax=docker/dockerfile:1
# Small updater: Python stdlib + docker CLI + compose plugin. Multi-arch via alpine.
FROM alpine:3.20
RUN apk add --no-cache python3 docker-cli docker-cli-compose ca-certificates tzdata
WORKDIR /app
COPY updater.py /app/updater.py
ENV STATE_DIR=/state COMPOSE_DIR=/compose HOOK_PORT=9000 PYTHONUNBUFFERED=1
EXPOSE 9000
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD python3 -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:9000/_deploy/healthz',timeout=2).status==200 else 1)"
ENTRYPOINT ["python3", "/app/updater.py"]

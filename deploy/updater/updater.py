#!/usr/bin/env python3
"""Couple server-side updater.

Two triggers, one deploy pipeline:
  * push: GitHub Actions POSTs a signed webhook to /_deploy/hook (reached through the
    Cloudflare ingress: Caddy or cloudflared route /_deploy/* here). Responds 202 at once.
  * poll: every UPDATE_INTERVAL seconds compare the remote digest of
    ghcr.io/<owner>/couple-<svc>:<UPDATE_CHANNEL> with what is deployed (fallback for
    missed webhooks).

Deploy = pull by digest -> retag to the compose tag -> `docker compose up -d --no-deps
--force-recreate <svc>` -> wait for HTTP 200 on the health URL -> on failure retag the
previous digest, recreate again and mark the new digest bad (the poller skips it until a
different digest appears).

Only the Python stdlib plus the docker CLI (with the compose plugin) are required.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import http.client
import json
import os
import queue
import re
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
SHA_RE = re.compile(r"^[0-9a-f]{7,40}$")
MAX_BODY = 8192
# Internal (compose-network) calls never go through an HTTP proxy from the environment.
DIRECT = urllib.request.build_opener(urllib.request.ProxyHandler({}))

# Outbound registry host, a compile-time constant by design: every URL this module builds goes to
# ghcr.io and nothing else (images are published as ghcr.io/slss15/couple-*). Keeping the host out of
# the environment means no env value can redirect these requests to another machine.
REGISTRY = "ghcr.io"


def log(msg: str) -> None:
    print(f"{time.strftime('%Y-%m-%dT%H:%M:%S%z')} updater: {msg}", flush=True)


class Config:
    def __init__(self, env=None):
        e = os.environ if env is None else env
        g = lambda k, d="": (e.get(k) or d)  # noqa: E731
        self.enabled = (g("UPDATE_ENABLED", "true").strip().lower() in ("1", "true", "yes", "on"))
        self.interval = int(g("UPDATE_INTERVAL", "300"))
        self.channel = g("UPDATE_CHANNEL") or g("COUPLE_TAG") or "edge"
        self.window = g("UPDATE_WINDOW")  # "HH:MM-HH:MM" local time (TZ env); poller only
        self.health_timeout = int(g("HEALTH_TIMEOUT", "90"))
        self.registry = REGISTRY
        self.owner = g("IMAGE_OWNER", "slss15").lower()
        self.services = [s.strip() for s in g("SERVICES", "backend,admin").split(",") if s.strip()]
        self.health_urls = {
            "backend": g("HEALTH_URL_BACKEND", "http://backend:8080/api/v1/health"),
            "admin": g("HEALTH_URL_ADMIN", "http://admin:8080/"),
        }
        self.ghcr_user = g("GHCR_USER")
        self.ghcr_token = g("GHCR_TOKEN")
        self.compose_project = g("COMPOSE_PROJECT_NAME", "couple")
        self.compose_dir = g("COMPOSE_DIR", "/compose")
        self.compose_file = g("COMPOSE_FILE_PATH", os.path.join(self.compose_dir, "docker-compose.yml"))
        self.state_dir = g("STATE_DIR", "/state")
        self.hook_secret = g("DEPLOY_HOOK_SECRET")
        self.hook_port = int(g("HOOK_PORT", "9000"))
        self.hook_max_skew = int(g("HOOK_MAX_SKEW", "300"))
        self.status_post_url = g("STATUS_POST_URL", "http://backend:8080/api/v1/internal/updater/status")
        self.internal_token = g("MQTT_HOOK_SECRET")
        self.docker = g("DOCKER_BIN", "docker")
        self.health_poll = float(g("HEALTH_POLL_SECONDS", "3"))

    def repo(self, svc: str) -> str:
        return f"{self.registry}/{self.owner}/couple-{svc}"


# --------------------------------------------------------------------------- HMAC
def sign(secret: str, ts: str, body: bytes) -> str:
    return hmac.new(secret.encode(), ts.encode() + b"." + body, hashlib.sha256).hexdigest()


class ReplayCache:
    """Remembers accepted signatures until they can no longer pass the timestamp check."""

    def __init__(self, path: str | None, ttl: int):
        self.path, self.ttl, self.lock = path, ttl, threading.Lock()
        self.seen: dict[str, float] = {}
        if path and os.path.exists(path):
            try:
                with open(path) as f:
                    self.seen = {k: float(v) for k, v in json.load(f).items()}
            except (OSError, ValueError):
                self.seen = {}

    def check_and_add(self, key: str, now: float) -> bool:
        """True if key is new (and records it); False if it is a replay."""
        with self.lock:
            self.seen = {k: v for k, v in self.seen.items() if v > now}
            if key in self.seen:
                return False
            self.seen[key] = now + 2 * self.ttl + 60
            if self.path:
                try:
                    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(self.path) or ".", prefix=".replay-", suffix=".tmp")
                    try:
                        with os.fdopen(fd, "w") as f:
                            json.dump(self.seen, f)
                        os.replace(tmp, self.path)
                    except BaseException:
                        try:
                            os.unlink(tmp)
                        except OSError:
                            pass
                        raise
                except OSError:
                    pass
            return True


def verify_hook(secret: str, ts_header: str | None, sig_header: str | None, body: bytes,
                now: float, max_skew: int, replay: ReplayCache) -> tuple[int, str, dict | None]:
    """Returns (http_status, reason, parsed_body). 200 means verified (caller answers 202)."""
    if not secret:
        return 503, "hook disabled (DEPLOY_HOOK_SECRET unset)", None
    if not ts_header or not sig_header:
        return 401, "missing signature headers", None
    if not re.fullmatch(r"\d{1,12}", ts_header):
        return 401, "bad timestamp", None
    expected = sign(secret, ts_header, body)
    if not hmac.compare_digest(expected, sig_header.strip().lower()):
        return 401, "bad signature", None
    if abs(now - int(ts_header)) > max_skew:
        return 401, "timestamp outside allowed window", None
    try:
        data = json.loads(body)
    except ValueError:
        return 400, "body is not JSON", None
    if not isinstance(data, dict):
        return 400, "body must be an object", None
    if str(data.get("ts")) != ts_header:
        return 401, "body ts does not match header", None
    if not replay.check_and_add(expected, now):
        return 409, "replayed request", None
    return 200, "ok", data


def validate_payload(cfg: Config, data: dict) -> tuple[str | None, dict | None]:
    svc = data.get("service")
    if svc not in cfg.services:
        return f"unknown service {svc!r}", None
    image = str(data.get("image", ""))
    base = image.split("@", 1)[0]
    if ":" in base.split("/")[-1]:
        base = base.rsplit(":", 1)[0]
    prefix = f"{cfg.registry}/{cfg.owner}/couple-"
    if not base.startswith(prefix) or base != cfg.repo(svc):
        return f"image {image!r} not allowed (must be {cfg.repo(svc)})", None
    digest = str(data.get("digest", ""))
    if not DIGEST_RE.match(digest):
        return "digest must be sha256:<64 hex>", None
    sha = str(data.get("sha", ""))
    if sha and not SHA_RE.match(sha):
        return "sha must be hex", None
    return None, {"service": svc, "digest": digest, "sha": sha}


# --------------------------------------------------------------------------- helpers
def in_window(window: str, now: datetime) -> bool:
    if not window:
        return True
    m = re.fullmatch(r"(\d{1,2}):(\d{2})-(\d{1,2}):(\d{2})", window.strip())
    if not m:
        log(f"invalid UPDATE_WINDOW {window!r}; ignoring")
        return True
    a = int(m[1]) * 60 + int(m[2])
    b = int(m[3]) * 60 + int(m[4])
    cur = now.hour * 60 + now.minute
    return a <= cur < b if a <= b else (cur >= a or cur < b)


class Docker:
    def __init__(self, cfg: Config):
        self.cfg = cfg

    def run(self, *args: str, timeout: int = 600) -> subprocess.CompletedProcess:
        return subprocess.run([self.cfg.docker, *args], capture_output=True, text=True, timeout=timeout)

    def login(self) -> None:
        if self.cfg.ghcr_user and self.cfg.ghcr_token:
            p = subprocess.run([self.cfg.docker, "login", self.cfg.registry, "-u", self.cfg.ghcr_user,
                                "--password-stdin"], input=self.cfg.ghcr_token, capture_output=True, text=True)
            log("docker login ok" if p.returncode == 0 else f"docker login failed: {p.stderr.strip()[:200]}")

    def local_digest(self, repo: str, tag: str) -> str | None:
        p = self.run("image", "inspect", "--format", "{{json .RepoDigests}}", f"{repo}:{tag}", timeout=60)
        if p.returncode != 0:
            return None
        try:
            for rd in json.loads(p.stdout.strip() or "[]") or []:
                if rd.startswith(repo + "@"):
                    return rd.split("@", 1)[1]
        except ValueError:
            pass
        return None

    def pull(self, ref: str) -> bool:
        p = self.run("pull", ref, timeout=1800)
        if p.returncode != 0:
            log(f"pull {ref} failed: {p.stderr.strip()[:300]}")
        return p.returncode == 0

    def tag(self, src: str, dst: str) -> bool:
        return self.run("tag", src, dst, timeout=60).returncode == 0

    def recreate(self, svc: str) -> bool:
        p = self.run("compose", "-p", self.cfg.compose_project, "--project-directory", self.cfg.compose_dir,
                     "-f", self.cfg.compose_file, "up", "-d", "--no-deps", "--force-recreate", svc, timeout=600)
        if p.returncode != 0:
            log(f"compose up {svc} failed: {p.stderr.strip()[:300]}")
        return p.returncode == 0


def http_get_status(url: str, timeout: float = 5) -> int:
    try:
        with DIRECT.open(urllib.request.Request(url, method="GET"), timeout=timeout) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception:  # noqa: BLE001
        return 0


def _registry_call(path: str, method: str, headers: dict, timeout: int = 20) -> tuple[int, dict, bytes]:
    """One request to the registry host, which is the compile-time constant REGISTRY (never env-derived).

    `path` starts with "/" and may only carry regex-validated name/tag segments (see remote_digest), so the
    request line cannot be redirected or smuggled; the connection host being a literal rules out SSRF.
    Note: unlike urllib's default opener this does not honour *_proxy env vars — the compose updater is
    expected to have direct outbound (the whole design: no inbound, direct egress).
    """
    conn = http.client.HTTPSConnection(REGISTRY, timeout=timeout)
    try:
        conn.request(method, path, headers=headers)
        resp = conn.getresponse()
        body = resp.read()
        return resp.status, {k.lower(): v for k, v in resp.getheaders()}, body
    finally:
        conn.close()


def remote_digest(cfg: Config, svc: str, tag: str) -> str | None:
    """HEAD the manifest on the registry (anonymous token for public packages)."""
    # Both names end up in registry URLs: only accept the Docker-legal spellings so a bad
    # UPDATE_CHANNEL / SERVICES env var cannot smuggle path tricks into the request.
    if svc not in cfg.services or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", tag):
        log(f"remote digest: refusing invalid service/tag {svc}:{tag!r}")
        return None
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,62}", cfg.owner):
        log(f"remote digest: refusing invalid IMAGE_OWNER {cfg.owner!r}")
        return None
    name = f"{cfg.owner}/couple-{svc}"
    try:
        auth = {}
        if cfg.ghcr_user and cfg.ghcr_token:
            cred = base64.b64encode(f"{cfg.ghcr_user}:{cfg.ghcr_token}".encode()).decode()
            auth["Authorization"] = f"Basic {cred}"
        status, _, body = _registry_call(f"/token?scope=repository:{name}:pull&service={REGISTRY}", "GET", auth)
        if status != 200:
            log(f"remote digest {svc}:{tag}: token endpoint returned {status}")
            return None
        token = json.loads(body).get("token", "")
        headers = {"Authorization": f"Bearer {token}", "Accept": ", ".join([
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.docker.distribution.manifest.list.v2+json",
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.v2+json"])}
        status, hdrs, _ = _registry_call(f"/v2/{name}/manifests/{tag}", "HEAD", headers)
        if status != 200:
            log(f"remote digest {svc}:{tag}: manifest HEAD returned {status}")
            return None
        d = hdrs.get("docker-content-digest")
        return d if d and DIGEST_RE.match(d) else None
    except Exception as e:  # noqa: BLE001
        log(f"remote digest {svc}:{tag} failed: {e}")
        return None


# --------------------------------------------------------------------------- state + engine
class Updater:
    def __init__(self, cfg: Config, docker=None, health=None, remote=None, clock=time.time, sleep=time.sleep):
        self.cfg = cfg
        self.docker = docker or Docker(cfg)
        self.health = health or http_get_status
        self.remote = remote or (lambda svc, tag: remote_digest(cfg, svc, tag))
        self.clock, self.sleep = clock, sleep
        self.lock = threading.RLock()
        self.jobs: queue.Queue = queue.Queue()
        os.makedirs(cfg.state_dir, exist_ok=True)
        self.state_path = os.path.join(cfg.state_dir, "updater.json")
        self.state = self._load()
        self.replay = ReplayCache(os.path.join(cfg.state_dir, "hook-replay.json"), cfg.hook_max_skew)

    # -- state
    def _load(self) -> dict:
        st = {"current": {}, "bad": {}, "deploys": {}, "lastCheck": None, "lastResult": None,
              "lastError": None, "channel": self.cfg.channel}
        try:
            with open(self.state_path) as f:
                st.update(json.load(f))
        except (OSError, ValueError):
            pass
        st["channel"] = self.cfg.channel
        for svc in self.cfg.services:
            st["current"].setdefault(svc, None)
            st["bad"].setdefault(svc, [])
        return st

    def save(self) -> None:
        with self.lock:
            fd, tmp = tempfile.mkstemp(dir=self.cfg.state_dir, prefix=".updater-", suffix=".json.tmp")
            try:
                with os.fdopen(fd, "w") as f:
                    json.dump(self.state, f, indent=2, sort_keys=True)
                os.replace(tmp, self.state_path)
            except BaseException:
                try:
                    os.unlink(tmp)
                except OSError:
                    pass
                raise
            payload = self.public_status()
        self._post_status(payload)

    def _post_status(self, payload: dict) -> None:
        if not self.cfg.status_post_url or not self.cfg.internal_token:
            return
        try:
            req = urllib.request.Request(self.cfg.status_post_url, data=json.dumps(payload).encode(),
                                         method="POST", headers={"Content-Type": "application/json",
                                                                 "X-Internal-Token": self.cfg.internal_token})
            DIRECT.open(req, timeout=5).close()
        except Exception:  # noqa: BLE001 - backend may be down or not implement it yet
            pass

    def public_status(self) -> dict:
        """Non-sensitive view, served at GET /_deploy/status and posted to the backend."""
        with self.lock:
            return {
                "channel": self.cfg.channel,
                "enabled": self.cfg.enabled,
                "current": dict(self.state["current"]),
                "deploys": json.loads(json.dumps(self.state["deploys"])),
                "lastCheck": self.state["lastCheck"],
                "lastResult": self.state["lastResult"],
                "lastError": self.state["lastError"],
            }

    def _set_deploy(self, svc: str, **kw) -> None:
        with self.lock:
            d = self.state["deploys"].setdefault(svc, {})
            d.update(kw)
            d["updatedAt"] = int(self.clock() * 1000)
        self.save()

    def current(self, svc: str) -> str | None:
        with self.lock:
            cur = self.state["current"].get(svc)
        if cur:
            return cur
        cur = self.docker.local_digest(self.cfg.repo(svc), self.cfg.channel)
        if cur:
            with self.lock:
                self.state["current"][svc] = cur
        return cur

    # -- core
    def wait_healthy(self, svc: str) -> bool:
        url = self.cfg.health_urls.get(svc)
        if not url:
            return True
        deadline = self.clock() + self.cfg.health_timeout
        while self.clock() < deadline:
            if self.health(url) == 200:
                return True
            self.sleep(self.cfg.health_poll)
        return False

    def deploy(self, svc: str, digest: str, source: str, sha: str = "") -> str:
        """Runs one deploy synchronously; returns the terminal state."""
        repo, tag = self.cfg.repo(svc), self.cfg.channel
        prev = self.current(svc)
        self._set_deploy(svc, digest=digest, sha=sha, source=source, state="deploying", error=None,
                         previous=prev)
        if prev == digest:
            self._finish(svc, "noop", None)
            return "noop"
        ref = f"{repo}@{digest}"
        if not self.docker.pull(ref):
            self._finish(svc, "failed", "pull_failed")
            return "failed"
        if not self.docker.tag(ref, f"{repo}:{tag}") or not self.docker.recreate(svc):
            ok = "recreate_failed"
        elif self.wait_healthy(svc):
            with self.lock:
                self.state["current"][svc] = digest
            self._finish(svc, "success", None)
            log(f"{svc} -> {digest} ({source}) healthy")
            return "success"
        else:
            ok = "health_timeout"
        log(f"{svc} {digest} failed ({ok}); rolling back to {prev}")
        with self.lock:
            bad = self.state["bad"].setdefault(svc, [])
            if digest not in bad:
                bad.append(digest)
                del bad[:-20]
        if not prev:
            self._finish(svc, "failed", ok)
            return "failed"
        pref = f"{repo}@{prev}"
        restored = (self.docker.tag(pref, f"{repo}:{tag}") or (self.docker.pull(pref) and self.docker.tag(
            pref, f"{repo}:{tag}"))) and self.docker.recreate(svc) and self.wait_healthy(svc)
        if restored:
            self._finish(svc, "rolled_back", ok)
            return "rolled_back"
        self._finish(svc, "rollback_failed", ok)
        return "rollback_failed"

    def _finish(self, svc: str, state: str, error: str | None) -> None:
        with self.lock:
            self.state["lastResult"] = f"{svc}:{state}"
            if error:
                self.state["lastError"] = f"{svc}:{error}"
        self._set_deploy(svc, state=state, error=error, finishedAt=int(self.clock() * 1000))

    def poll_once(self, now: datetime | None = None) -> None:
        now = now or datetime.now()
        with self.lock:
            self.state["lastCheck"] = int(self.clock() * 1000)
        if not self.cfg.enabled:
            self.save()
            return
        if not in_window(self.cfg.window, now):
            self.save()
            return
        for svc in self.cfg.services:
            remote = self.remote(svc, self.cfg.channel)
            if not remote:
                with self.lock:
                    self.state["lastError"] = f"{svc}:remote_digest_unavailable"
                continue
            if remote == self.current(svc):
                continue
            if remote in self.state["bad"].get(svc, []):
                log(f"{svc}: remote {remote} is marked bad; waiting for a newer image")
                continue
            self.deploy(svc, remote, "poll")
        self.save()

    def enqueue_hook(self, svc: str, digest: str, sha: str) -> None:
        self._set_deploy(svc, digest=digest, sha=sha, source="hook", state="queued", error=None,
                         requestedAt=int(self.clock() * 1000))
        self.jobs.put(("hook", svc, digest, sha))

    # -- loops
    def worker(self) -> None:
        while True:
            kind, *args = self.jobs.get()
            try:
                if kind == "hook":
                    self.deploy(args[0], args[1], "hook", args[2])
                elif kind == "poll":
                    self.poll_once()
            except Exception as e:  # noqa: BLE001
                log(f"job {kind} crashed: {e!r}")
                with self.lock:
                    self.state["lastError"] = f"internal:{type(e).__name__}"
                self.save()

    def poller(self) -> None:
        while True:
            self.jobs.put(("poll",))
            time.sleep(max(30, self.cfg.interval))


def make_handler(up: Updater):
    class Handler(BaseHTTPRequestHandler):
        server_version = "couple-updater"
        sys_version = ""

        def log_message(self, fmt, *args):  # quieter access log
            log(f"http {self.address_string()} {fmt % args}")

        def _json(self, code: int, obj: dict) -> None:
            body = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):  # noqa: N802
            path = self.path.split("?", 1)[0]
            if path in ("/_deploy/status", "/_deploy/healthz"):
                self._json(200, up.public_status() if path.endswith("status") else {"ok": True})
            else:
                self._json(404, {"error": "not found"})

        def do_POST(self):  # noqa: N802
            if self.path.split("?", 1)[0] != "/_deploy/hook":
                return self._json(404, {"error": "not found"})
            try:
                n = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                n = -1
            if n <= 0 or n > MAX_BODY:
                return self._json(413 if n > MAX_BODY else 400, {"error": "bad body size"})
            body = self.rfile.read(n)
            code, reason, data = verify_hook(up.cfg.hook_secret, self.headers.get("X-Deploy-Timestamp"),
                                             self.headers.get("X-Deploy-Signature"), body, up.clock(),
                                             up.cfg.hook_max_skew, up.replay)
            if code != 200:
                log(f"hook rejected: {reason}")
                return self._json(code, {"error": reason})
            err, job = validate_payload(up.cfg, data)
            if err:
                log(f"hook rejected: {err}")
                return self._json(400, {"error": err})
            if not up.cfg.enabled:
                return self._json(503, {"error": "updates disabled (UPDATE_ENABLED=false)"})
            up.enqueue_hook(job["service"], job["digest"], job["sha"])
            log(f"hook accepted: {job['service']} {job['digest']} sha={job['sha']}")
            return self._json(202, {"accepted": True, "service": job["service"], "digest": job["digest"]})

    return Handler


def main() -> int:
    cfg = Config()
    up = Updater(cfg)
    log(f"start channel={cfg.channel} enabled={cfg.enabled} interval={cfg.interval}s "
        f"services={','.join(cfg.services)} hook={'on' if cfg.hook_secret else 'off'} window={cfg.window or '-'}")
    if isinstance(up.docker, Docker):
        up.docker.login()
    for svc in cfg.services:
        up.current(svc)
    up.save()
    threading.Thread(target=up.worker, daemon=True).start()
    threading.Thread(target=up.poller, daemon=True).start()
    srv = ThreadingHTTPServer(("0.0.0.0", cfg.hook_port), make_handler(up))
    srv.serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())

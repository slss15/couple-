"""Unit + integration tests for updater.py (stdlib unittest; docker is stubbed).

Run: python3 -m unittest -v deploy/updater/test_updater.py
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from datetime import datetime
from http.server import ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(__file__))
import updater as U  # noqa: E402

SECRET = "s3cret"
D1 = "sha256:" + "1" * 64
D2 = "sha256:" + "2" * 64
D3 = "sha256:" + "3" * 64
REPO = "ghcr.io/qimingnan17/couple-backend"


class FakeDocker:
    def __init__(self, local=None, pull_ok=True):
        self.calls, self.local, self.pull_ok = [], dict(local or {}), pull_ok
        self.tags = {}

    def local_digest(self, repo, tag):
        return self.local.get(repo)

    def pull(self, ref):
        self.calls.append(("pull", ref))
        return self.pull_ok

    def tag(self, src, dst):
        self.calls.append(("tag", src, dst))
        self.tags[dst] = src
        return True

    def recreate(self, svc):
        self.calls.append(("up", svc))
        return True


class Clock:
    def __init__(self, t=1_700_000_000.0):
        self.t = t

    def __call__(self):
        return self.t

    def sleep(self, s):
        self.t += s


def make(tmp, docker, healthy, env=None, remote=None):
    e = {"STATE_DIR": tmp, "DEPLOY_HOOK_SECRET": SECRET, "HEALTH_TIMEOUT": "10", "HEALTH_POLL_SECONDS": "1",
         "SERVICES": "backend,admin", "UPDATE_CHANNEL": "edge", "STATUS_POST_URL": ""}
    e.update(env or {})
    clock = Clock()
    up = U.Updater(U.Config(e), docker=docker, health=healthy, remote=remote or (lambda s, t: None),
                   clock=clock, sleep=clock.sleep)
    return up, clock


class HmacTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.replay = U.ReplayCache(os.path.join(self.tmp, "r.json"), 300)
        self.now = 1_700_000_000

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def body(self, ts):
        return json.dumps({"service": "backend", "image": REPO, "digest": D1, "sha": "abc1234", "ts": ts}).encode()

    def test_valid(self):
        ts = str(self.now)
        b = self.body(self.now)
        code, reason, data = U.verify_hook(SECRET, ts, U.sign(SECRET, ts, b), b, self.now, 300, self.replay)
        self.assertEqual((code, reason), (200, "ok"))
        self.assertEqual(data["digest"], D1)

    def test_bad_signature(self):
        ts = str(self.now)
        b = self.body(self.now)
        code, reason, _ = U.verify_hook(SECRET, ts, U.sign("wrong", ts, b), b, self.now, 300, self.replay)
        self.assertEqual((code, reason), (401, "bad signature"))
        # tampered body with the original signature
        sig = U.sign(SECRET, ts, b)
        b2 = b.replace(b"backend", b"admin")
        self.assertEqual(U.verify_hook(SECRET, ts, sig, b2, self.now, 300, self.replay)[0], 401)

    def test_expired_and_future_timestamp(self):
        for skew in (-301, 301):
            ts = str(self.now + skew)
            b = self.body(int(ts))
            code, reason, _ = U.verify_hook(SECRET, ts, U.sign(SECRET, ts, b), b, self.now, 300, self.replay)
            self.assertEqual((code, reason), (401, "timestamp outside allowed window"))

    def test_replay(self):
        ts = str(self.now)
        b = self.body(self.now)
        sig = U.sign(SECRET, ts, b)
        self.assertEqual(U.verify_hook(SECRET, ts, sig, b, self.now, 300, self.replay)[0], 200)
        self.assertEqual(U.verify_hook(SECRET, ts, sig, b, self.now + 5, 300, self.replay)[:2],
                         (409, "replayed request"))
        # replay cache survives a restart
        again = U.ReplayCache(os.path.join(self.tmp, "r.json"), 300)
        self.assertEqual(U.verify_hook(SECRET, ts, sig, b, self.now + 5, 300, again)[0], 409)

    def test_header_body_ts_mismatch_and_missing(self):
        ts = str(self.now)
        b = self.body(self.now - 10)
        self.assertEqual(U.verify_hook(SECRET, ts, U.sign(SECRET, ts, b), b, self.now, 300, self.replay)[0], 401)
        self.assertEqual(U.verify_hook(SECRET, None, None, b, self.now, 300, self.replay)[0], 401)
        self.assertEqual(U.verify_hook("", ts, "x", b, self.now, 300, self.replay)[0], 503)

    def test_payload_validation(self):
        cfg = U.Config({"SERVICES": "backend,admin"})
        ok = {"service": "backend", "image": REPO, "digest": D1, "sha": "abc1234"}
        self.assertIsNone(U.validate_payload(cfg, ok)[0])
        self.assertIsNone(U.validate_payload(cfg, {**ok, "image": REPO + ":edge"})[0])
        bad = [
            {**ok, "image": "ghcr.io/evil/couple-backend"},
            {**ok, "image": "docker.io/qimingnan17/couple-backend"},
            {**ok, "image": "ghcr.io/qimingnan17/couple-admin"},  # mismatched service
            {**ok, "image": "ghcr.io/qimingnan17/couple-backend-evil"},
            {**ok, "service": "mysql"},
            {**ok, "digest": "latest"},
            {**ok, "sha": "; rm -rf /"},
        ]
        for b in bad:
            self.assertIsNotNone(U.validate_payload(cfg, b)[0], b)


class DeployTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def test_success(self):
        d = FakeDocker(local={REPO: D1})
        up, _ = make(self.tmp, d, lambda url: 200)
        self.assertEqual(up.deploy("backend", D2, "hook", "abc1234"), "success")
        self.assertEqual(up.state["current"]["backend"], D2)
        self.assertIn(("pull", f"{REPO}@{D2}"), d.calls)
        self.assertEqual(d.tags[f"{REPO}:edge"], f"{REPO}@{D2}")
        st = json.load(open(os.path.join(self.tmp, "updater.json")))
        self.assertEqual(st["deploys"]["backend"]["state"], "success")
        self.assertEqual(st["current"]["backend"], D2)

    def test_noop_when_current(self):
        d = FakeDocker(local={REPO: D1})
        up, _ = make(self.tmp, d, lambda url: 200)
        self.assertEqual(up.deploy("backend", D1, "hook"), "noop")
        self.assertEqual(d.calls, [])

    def test_health_failure_rolls_back_and_marks_bad(self):
        d = FakeDocker(local={REPO: D1})
        health = {"ok": False}
        up, _ = make(self.tmp, d, lambda url: 200 if health["ok"] else 503)

        orig_tag = d.tag

        def tag(src, dst):  # previous image becomes healthy again once restored
            health["ok"] = src.endswith(D1)
            return orig_tag(src, dst)
        d.tag = tag
        self.assertEqual(up.deploy("backend", D2, "hook"), "rolled_back")
        self.assertEqual(up.state["current"]["backend"], D1)
        self.assertIn(D2, up.state["bad"]["backend"])
        self.assertEqual(d.tags[f"{REPO}:edge"], f"{REPO}@{D1}")
        self.assertEqual(up.state["deploys"]["backend"]["error"], "health_timeout")

    def test_rollback_failed(self):
        d = FakeDocker(local={REPO: D1})
        up, _ = make(self.tmp, d, lambda url: 500)
        self.assertEqual(up.deploy("backend", D2, "hook"), "rollback_failed")

    def test_no_previous_is_failed(self):
        up, _ = make(self.tmp, FakeDocker(), lambda url: 500)
        self.assertEqual(up.deploy("backend", D2, "poll"), "failed")

    def test_pull_failure(self):
        d = FakeDocker(local={REPO: D1}, pull_ok=False)
        up, _ = make(self.tmp, d, lambda url: 200)
        self.assertEqual(up.deploy("backend", D2, "hook"), "failed")
        self.assertEqual(up.state["current"]["backend"], D1)
        self.assertNotIn(("up", "backend"), d.calls)

    def test_poll_deploys_new_digest_and_skips_bad(self):
        d = FakeDocker(local={REPO: D1, "ghcr.io/qimingnan17/couple-admin": D1})
        remote = {"backend": D2, "admin": D1}
        up, _ = make(self.tmp, d, lambda url: 200, remote=lambda s, t: remote[s])
        up.state["bad"]["backend"] = [D2]
        up.poll_once(datetime(2026, 1, 1, 12, 0))
        self.assertEqual(up.state["current"]["backend"], D1)  # D2 is bad
        remote["backend"] = D3
        up.poll_once(datetime(2026, 1, 1, 12, 0))
        self.assertEqual(up.state["current"]["backend"], D3)
        self.assertNotIn(("pull", f"ghcr.io/qimingnan17/couple-admin@{D1}"), d.calls)

    def test_poll_respects_disabled_and_window(self):
        d = FakeDocker(local={REPO: D1})
        up, _ = make(self.tmp, d, lambda url: 200, env={"UPDATE_ENABLED": "false", "SERVICES": "backend"},
                     remote=lambda s, t: D2)
        up.poll_once()
        self.assertEqual(d.calls, [])
        up2, _ = make(tempfile.mkdtemp(dir=self.tmp), d, lambda url: 200,
                      env={"UPDATE_WINDOW": "03:00-05:00", "SERVICES": "backend"}, remote=lambda s, t: D2)
        up2.poll_once(datetime(2026, 1, 1, 12, 0))
        self.assertEqual(d.calls, [])
        up2.poll_once(datetime(2026, 1, 1, 4, 0))
        self.assertEqual(up2.state["current"]["backend"], D2)

    def test_window(self):
        self.assertTrue(U.in_window("", datetime(2026, 1, 1, 12)))
        self.assertTrue(U.in_window("23:00-02:00", datetime(2026, 1, 1, 1)))
        self.assertFalse(U.in_window("23:00-02:00", datetime(2026, 1, 1, 12)))


class HttpIntegrationTest(unittest.TestCase):
    """Real HTTP listener + the GitHub-side deploy-hook.sh client."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.docker = FakeDocker(local={REPO: D1})
        self.health = {"code": 200}
        cfg = U.Config({"STATE_DIR": self.tmp, "DEPLOY_HOOK_SECRET": SECRET, "HEALTH_TIMEOUT": "2",
                        "HEALTH_POLL_SECONDS": "0.2", "UPDATE_CHANNEL": "edge", "STATUS_POST_URL": "",
                        "HOOK_PORT": "0"})
        self.up = U.Updater(cfg, docker=self.docker, health=lambda url: self.health["code"],
                            remote=lambda s, t: None)
        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), U.make_handler(self.up))
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        threading.Thread(target=self.up.worker, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.srv.server_address[1]}"
        self.script = os.path.join(os.path.dirname(__file__), "..", "scripts", "deploy-hook.sh")

    def tearDown(self):
        self.srv.shutdown()
        shutil.rmtree(self.tmp)

    def run_script(self, digest, secret=SECRET):
        env = {**os.environ, "DEPLOY_HOOK_URL": self.url, "DEPLOY_HOOK_SECRET": secret,
               "DEPLOY_WAIT_SECONDS": "20", "DEPLOY_POLL_SECONDS": "1"}
        return subprocess.run(["bash", self.script, "backend", REPO, digest, "abc1234"], env=env,
                              capture_output=True, text=True, timeout=60)

    def test_script_success(self):
        p = self.run_script(D2)
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertEqual(self.up.state["current"]["backend"], D2)

    def test_script_reports_rollback_as_failure(self):
        self.health["code"] = 503
        p = self.run_script(D3)
        self.assertEqual(p.returncode, 1, p.stdout + p.stderr)
        self.assertIn("rollback_failed", p.stderr)  # health never recovers in this stub

    def test_script_bad_secret(self):
        p = self.run_script(D2, secret="nope")
        self.assertEqual(p.returncode, 2, p.stdout + p.stderr)
        self.assertIn("401", p.stderr)

    def test_unknown_path_404_and_status_shape(self):
        import urllib.error
        with self.assertRaises(urllib.error.HTTPError) as cm:
            U.DIRECT.open(self.url + "/api/v1/health")
        self.assertEqual(cm.exception.code, 404)
        st = json.load(U.DIRECT.open(self.url + "/_deploy/status"))
        self.assertEqual(set(st), {"channel", "enabled", "current", "deploys", "lastCheck", "lastResult",
                                   "lastError"})


if __name__ == "__main__":
    unittest.main()

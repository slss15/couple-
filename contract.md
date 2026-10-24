# Couple monorepo — P0 contract (authoritative; all three modules follow it)
Placeholder app name: "Couple" (to be renamed). Android package: com.qimingnan.couple. Assumes both partners use Android.

## Base
- REST base: {baseUrl}/api/v1, JSON, UTF-8. Errors: {"code":"STRING_CODE","message":"..."} with HTTP 4xx/5xx.
- Auth header: Authorization: Bearer <accessToken>. Access JWT lives 15 min (HS256; claims sub=userId, cid=coupleId|null, did=deviceId, role=user|admin). Refresh token is opaque and lives 30 days, rotated on every use, stored hashed. Reusing a refresh token revokes the whole family.
- Times are epoch milliseconds (Long). IDs are ULID strings.
- Health: GET /api/v1/health -> {"status":"ok","version":"x.y.z","protocolVersion":1}

## Auth
- POST /auth/register {username,password,displayName,deviceId,deviceName} -> TokenPair. Requires a registration code (server env REGISTRATION_CODE) in field inviteCode; this is a private server.
- POST /auth/login {username,password,deviceId,deviceName} -> TokenPair
- POST /auth/refresh {refreshToken,deviceId} -> TokenPair
- POST /auth/logout {refreshToken} -> 204
- TokenPair = {accessToken, refreshToken, accessExpiresAt, user:User}
- User = {id, username, displayName, avatarUrl|null, coupleId|null, createdAt}
- Passwords use Argon2id (or BCrypt cost 12 as fallback).

## Pairing (one couple = exactly 2 users)
- POST /pair/code -> {code:"6 digits", expiresAt} (lives 10 min, single use; issuing a new one replaces the old one)
- POST /pair/join {code} -> Couple (fails with ALREADY_PAIRED, CODE_INVALID, CODE_EXPIRED, SELF_PAIR)
- GET /couple -> Couple | 404 NOT_PAIRED
- Couple = {id, members:[User,User], pairedAt, anniversary|null}
- POST /couple/unpair/request -> {requestId}; POST /couple/unpair/confirm {requestId} (must be the *other* member) -> 204. Unpairing soft-deletes the couple.

## Bootstrap config (QR)
- The admin generates a signed bootstrap payload. The QR contains: couple://bootstrap?d=<base64url(json)>&s=<base64url(ed25519 sig)>
- json = {v:1, baseUrl, mqttUrl (wss://...), configVersion, issuedAt}
- The server has an Ed25519 keypair (env BOOTSTRAP_PRIVATE_KEY, or generated and persisted on first run). Public key: GET /api/v1/bootstrap/pubkey -> {alg:"Ed25519", publicKey: base64}. The app pins the pubkey on first scan (TOFU) and rejects configs signed with any other key.
- App rollback: after applying a new config, if /health is unreachable within 30 s, revert to the previous config.
- Admin endpoint: POST /admin/bootstrap -> {uri, json} (role=admin)

## Admin
- The first registered user (or env ADMIN_USERNAME) gets role=admin.
- GET /admin/config, PUT /admin/config {baseUrl, mqttUrl, turn:{provider:"cloudflare"|"coturn", ...}, storage:{...}} — secrets are write-only and come back masked.
- GET /admin/audit?limit&before -> [{id, actorId, action, target, meta, ts}]

## MQTT (P0: connect-only, prove it works)
- EMQX over WSS. Clients authenticate with username=userId, password=accessToken (EMQX HTTP auth hook -> POST /internal/mqtt/auth; ACL hook -> /internal/mqtt/acl).
- Topic root: couple/{coupleId}/... ; a client can only pub/sub under its own couple. P0 topic: couple/{cid}/presence (retained, {userId, online, ts}).

## Env (backend)
DB_URL, DB_USER, DB_PASSWORD, JWT_SECRET, REGISTRATION_CODE, BOOTSTRAP_PRIVATE_KEY(optional), ADMIN_USERNAME(optional), PORT=8080. Never commit secrets; ship .env.example only.

## P0 implementation notes (reconciled 2026-10-22)
- publicKey = standard base64 of raw 32-byte Ed25519 key. Signature covers the decoded bytes of d; d and s are base64url without padding. baseUrl excludes /api/v1, and every route, including /internal/mqtt/*, lives under /api/v1.
- /couple/unpair/confirm requestId is optional; when omitted, it uses the pending request.
- Extra env vars: MQTT_HOOK_SECRET (X-Internal-Token header), AUTH_RATE_LIMIT_PER_MINUTE=30. No REGISTRATION_CODE means registration is disabled.
- /admin/config also returns configVersion and updatedAt. A secret comes back as ****last4; on PUT, omitted/null/masked means keep, and "" means clear.
- The access token's cid goes stale after pair/unpair, so clients must call /auth/refresh; the server always checks the DB.
- Extra error codes: NOT_OTHER_MEMBER, UNPAIR_REQUEST_*, REFRESH_REUSED, REGISTRATION_DISABLED.
- TODO (P1): add an id tie-breaker to audit paging; add an admin endpoint that lists couples.

## Auto-update (P0.5)
The home server sits behind NAT (outbound only); clients reach it through Cloudflare. The backend mirrors APKs from GitHub Releases (outbound) and the app downloads only from the backend.

### Release assets (produced by CI, fixed)
- GitHub Release on repo `slss15/couple-`, tag `vX.Y.Z`, assets `couple-X.Y.Z.apk` and `release.json`. Drafts and prereleases are ignored.
- versionCode = X*10000 + Y*100 + Z (Y, Z <= 99).
- release.json = {versionName:"X.Y.Z", versionCode, sha256 (hex of the APK), size (bytes), signingCertSha256 (hex SHA-256 of the DER signing certificate), minSupportedVersionCode, notes, publishedAt (epoch ms)}. versionName must equal the tag without "v"; 0 <= minSupportedVersionCode <= versionCode.

### Mirror (backend)
- Every release_poll_minutes (default 30; 0 = off) and on POST /admin/releases/sync: GET https://api.github.com/repos/{release_repo}/releases?per_page=5 (Bearer release_github_token if set; then the API asset URL is used, so private repos work). Honors HTTPS_PROXY/NO_PROXY; retries 5xx/429/network errors with exponential backoff; follows redirects and drops Authorization when the host changes.
- For each release whose versionCode is not in app_releases yet: download release.json and the APK into RELEASES_DIR (default /data/releases), and require size == release.json size, sha256 == release.json sha256, and the signing certificate read from the APK's v3/v2 signature block == release.json signingCertSha256 == the pin (release_expected_signing_cert_sha256). If no pin is set, the first mirrored release's certificate becomes the pin (TOFU, audit `release.cert_pinned`). Rejections: INVALID_METADATA, SIZE_MISMATCH, SHA256_MISMATCH, APK_UNSIGNED, CERT_MISMATCH (audit `release.rejected`). Accepted releases are stored as MIRRORED, or PUBLISHED when release_auto_publish is true (audit `release.mirrored`).
- Table app_releases (id ULID, version_code unique, version_name, sha256, size, signing_cert_sha256, min_supported_version_code, notes, published_at, file_path, status MIRRORED|PUBLISHED|WITHDRAWN, rollout_percent 0..100 default 100, created_at).

### App endpoints (any signed-in user)
- GET /app/update?versionCode=N&deviceId=X (deviceId defaults to the token's did) -> {latest: {versionName, versionCode, sha256, size, signingCertSha256, notes, downloadUrl, publishedAt} | null, force: bool}.
  - Only PUBLISHED releases whose APK file exists count. newest = the published release with the highest versionCode.
  - force = N < newest.minSupportedVersionCode. When force is true, latest = newest regardless of rollout.
  - Otherwise latest = the highest published release with versionCode > N whose rollout includes the device, or null.
  - Rollout: bucket = (first 4 bytes of SHA-256(UTF-8(deviceId + ":" + versionCode)) as an unsigned big-endian int) mod 100, where versionCode is the release's own. The device is included when bucket < rolloutPercent (0 = nobody, 100 = everybody; raising the percentage only adds devices).
  - downloadUrl is the path "/api/v1/app/releases/{versionCode}/apk". Clients resolve it against baseUrl and refuse any other origin, because the request carries the access token.
- GET /app/releases/{versionCode}/apk (PUBLISHED only, otherwise 404 RELEASE_NOT_FOUND / RELEASE_FILE_MISSING): streams the file with Content-Length, `ETag: "<sha256>"`, Accept-Ranges: bytes, Content-Type application/vnd.android.package-archive. Supports a single `Range: bytes=a-b | a- | -n` (206 + Content-Range, 416 + `Content-Range: bytes */size` when unsatisfiable), If-Range (a stale validator gets the full 200), and If-None-Match (304). Multi-range requests get the full body.

### App rules
- Checks on start/login at most once per 6 h, and manually from 我的 → 检查更新. The last response is cached so a forced update keeps blocking after a restart.
- Downloads through WorkManager (progress notification, Range resume using If-Range on the ETag), then verifies size and sha256, archive packageName == own package, archive versionCode == latest.versionCode, archive signing certificate set == the installed app's certificate set, and signingCertSha256 ∈ that set. Anything else is refused and deleted.
- Install: if Shizuku is running and permission is granted, run a silent `pm install -r -S <size>` (APK over stdin) and then `am start`. Otherwise use a system PackageInstaller session, which needs REQUEST_INSTALL_PACKAGES / "install unknown apps" and shows the user confirmation.
- force = true shows a blocking dialog (no dismiss, back is ignored). Otherwise the dialog can be dismissed and shows the notes.

### Admin endpoints (role=admin, all audit-logged)
- GET /admin/releases -> [{id, versionCode, versionName, sha256, size, signingCertSha256, minSupportedVersionCode, notes, publishedAt, status, rolloutPercent, createdAt, fileExists}] (newest first).
- POST /admin/releases/sync -> 202 SyncState and the pass runs in the background, because downloads can exceed Cloudflare's 100 s limit. `?wait=true` runs it synchronously and returns 200 (used by tests). GET /admin/releases/sync -> SyncState = {running, lastRunAt, lastFinishedAt, lastResult:"ok"|"error"|null, lastError, mirrored:[versionCode], rejected:["versionCode: REASON"]}. A manual sync also retries releases that were rejected permanently.
- PATCH /admin/releases/{versionCode} {status?, rolloutPercent? (0..100), minSupportedVersionCode? (0..versionCode), notes?}. PUBLISHED requires the file to exist.
- GET/PUT /admin/releases/settings {repo, githubToken, autoPublish, pollMinutes (0 or 5..1440), expectedSigningCertSha256}. The settings are stored in server_settings key `release.settings`, separate from /admin/config so they do not bump configVersion. githubToken is write-only and masked like other secrets (null/omitted/masked = keep, "" = clear). For expectedSigningCertSha256, "" clears the pin; colons and case are normalized.
- GET /admin/server/update-status -> {backend:{version (APP_VERSION env, else the build version), buildVersion, revision (APP_REVISION)}, updater: <last JSON posted by the server-updater, verbatim> | null, updaterReportedAt}.

### Internal
- POST /internal/updater/status (header X-Internal-Token == MQTT_HOOK_SECRET; 404 when the secret is unset or wrong, or when CF-Connecting-IP is present) with a JSON object of at most 16 KB, e.g. {channel, enabled, current:{backend, admin}, lastCheck, lastResult, lastError} -> 204. It is stored in server_settings key `updater.status`. An audit entry (`updater.status`) is written only when current/lastResult/lastError change.

### Migration rule (expand-only)
- Flyway migrations must be expand-only: add tables, nullable columns, or columns with defaults, plus indexes. Never drop, rename, or narrow anything in the same release that stops using it. The server-updater may roll the backend back to the previous image after a failed health check, and that image must still run against the newer schema. Destructive cleanups ship at least one release later, in their own migration.
- Backend env additions: RELEASES_DIR (default /data/releases), HTTPS_PROXY / NO_PROXY (outbound mirror), APP_VERSION / APP_REVISION (set by the image).

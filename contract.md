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
- Preferred: the first admin is created through first-run setup (below). Legacy, still supported: the first registered user (or env ADMIN_USERNAME) gets role=admin.
- GET /admin/config, PUT /admin/config {baseUrl, mqttUrl, turn:{provider:"cloudflare"|"coturn", ...}, storage:{...}} — secrets are write-only and come back masked.
- Admin config also carries `notify`: `{"wxpusher": {"appToken": "<secret>", "uids": ["UID_..."]}}` (appToken is write-only/masked like other secrets; uids are plain). When configured, the backend sends best-effort operator alerts via WxPusher (`POST https://wxpusher.zjiecode.com/api/send/message`): deploy failure/rollback reported by the server-updater, a release auto-paused by a failed health report, and a canary release with no OK health report within 2 h (auto-paused by the periodic sweep / POST /admin/releases/sweep). Sends are debounced per alert key (10 min); unconfigured = disabled; failures never affect the API path. Content is operator-level only — never message bodies.
- GET /admin/audit?limit&before -> [{id, actorId, action, target, meta, ts}]

## First-run setup
- Public, rate-limited like /auth. Available only while no user with role=admin exists ("needed").
- GET /setup/status -> {needed:Boolean, suggested:{publicBaseUrl, mqttUrl}|null}. Once an admin exists: {needed:false, suggested:null} (200).
- POST /setup {token, username, password, deviceId?, deviceName?} -> TokenPair (same shape as /auth/login; role=admin). Same username/password policy as register (username 3-32 of [A-Za-z0-9_.-], password 8-128); displayName = username; deviceId defaults to "setup-web". Errors: 403 SETUP_TOKEN_INVALID (missing/wrong token, constant-time compare), 400 VALIDATION_ERROR, 409 USERNAME_TAKEN, 410 SETUP_COMPLETED (after success, or once an admin exists). Audit: setup.completed / setup.failed (never the token).
- Token: env SETUP_TOKEN. If unset and setup is needed, the server generates a random token at startup, logs one WARN line `SETUP TOKEN: <token>  → open https://<host>/setup?token=<token>` and, when a data dir is configured (DATA_DIR, else the parent of RELEASES_DIR), writes it to <dataDir>/setup-token.txt (deleted after setup). The token changes on every restart until setup is done. The admin SPA opens /setup?token=<token> with the token prefilled.
- On success, empty /admin/config baseUrl / mqttUrl are filled with the derived values (configVersion +1), so POST /admin/bootstrap works immediately. Existing values are never overwritten.
- Derivation (a suggestion only; forwarded headers are never trusted for security decisions): scheme = first X-Forwarded-Proto value if http|https, else the request scheme; host = first X-Forwarded-Host value, else Host (lowercased, must be a plain hostname/IP with optional port, otherwise no suggestion). publicBaseUrl = scheme://host; mqttUrl = wss://host/mqtt (path = MQTT_WS path, default /mqtt).
- GET/PUT /admin/config also return `suggested` (same derivation for the current request, may be null).
- Env addition: SETUP_TOKEN (optional), DATA_DIR (optional). REGISTRATION_CODE / ADMIN_USERNAME become optional.

## MQTT (P0: connect-only, prove it works)
- EMQX over WSS. Clients authenticate with username=userId, password=accessToken (EMQX HTTP auth hook -> POST /internal/mqtt/auth; ACL hook -> /internal/mqtt/acl).
- Topic root: couple/{coupleId}/... ; a client can only access its own couple. Clients may **subscribe** to any topic under their couple; client **publishes** are limited to `couple/{cid}/presence` (retained, {userId, online, ts}) and `couple/{cid}/typing` (QoS 0, not retained, see Chat §Typing). Server-push topics (`feed`, `chat`, `events`) are read-only for clients; the ACL hook receives `action` and enforces this per direction. P0 topic: couple/{cid}/presence (retained, {userId, online, ts}).
- Per-user root: `user/{userId}/events` — a client may **subscribe** only to its own (exact topic, no wildcards; allowed even before pairing, when it has no coupleId); clients never publish under `user/`.

### Pairing events (server → clients, P1)
- `user/{userId}/events`, QoS 1, not retained: `{kind:"paired", coupleId}` — sent to the code owner when the partner joins with their code (there is no cid before pairing; the joiner already has the POST /pair/join response). Also `{kind:"unpaired", coupleId}` to both members after an unpair is confirmed (a client that reconnected after unpairing no longer holds its couple subscriptions).
- `couple/{cid}/events`, QoS 1, not retained, subscribe-only: `{kind:"unpair_requested", requestId, by}` after POST /couple/unpair/request (both members receive it; the app shows a confirm prompt only when `by` is the partner) and `{kind:"unpaired", coupleId}` after the confirm.
- Published after the DB commit through the same server-push path as chat/feed. MQTT is a hint: on `paired`/`unpaired` the app refreshes the access token (cid claim) and GET /couple; the code owner keeps a slow fallback poll (every 15 s) of GET /couple while the code is shown.

## Env (backend)
DB_URL, DB_USER, DB_PASSWORD, JWT_SECRET, REGISTRATION_CODE(optional), BOOTSTRAP_PRIVATE_KEY(optional), ADMIN_USERNAME(optional), SETUP_TOKEN(optional), DATA_DIR(optional), PORT=8080. Never commit secrets; ship .env.example only.

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
- OTA v2 (schema 2, all new fields optional for older manifests): + {schema:2, packageName, protocolVersion (app<->app partner protocol, default 1; NOT the /health client<->server protocolVersion), minPeerProtocol (oldest partner protocol it understands, default 1; 1 <= minPeerProtocol <= protocolVersion), minSdk (default 0), rollout:"staged"|"all" (null = all)}. CI reads protocolVersion/minPeerProtocol from app/gradle.properties (couple.protocolVersion / couple.minPeerProtocol) and rollout from a tag-annotation line `rollout: all` (default staged).
- Asset `release.json.sig` = standard base64 of the Ed25519 signature over the **exact bytes** of release.json, made in CI with the OTA release key (secret OTA_SIGNING_KEY = base64 raw 32-byte seed; deploy/scripts/ota-sign.sh). This key is distinct from BOOTSTRAP_PRIVATE_KEY. Its public half (repo variable OTA_PUBLIC_KEY, base64 raw 32 bytes) is pinned into the app at build time (BuildConfig.OTA_PUBLIC_KEY); release builds fail without it. Keypair: deploy/scripts/ota-keygen.sh.

### Mirror (backend)
- Every release_poll_minutes (default 30; 0 = off) and on POST /admin/releases/sync: GET https://api.github.com/repos/{release_repo}/releases?per_page=5 (Bearer release_github_token if set; then the API asset URL is used, so private repos work). Honors HTTPS_PROXY/NO_PROXY; retries 5xx/429/network errors with exponential backoff; follows redirects and drops Authorization when the host changes.
- For each release whose versionCode is not in app_releases yet: download release.json and the APK into RELEASES_DIR (default /data/releases), and require size == release.json size, sha256 == release.json sha256, and the signing certificate read from the APK's v3/v2 signature block == release.json signingCertSha256 == the pin (release_expected_signing_cert_sha256). If no pin is set, the first mirrored release's certificate becomes the pin (TOFU, audit `release.cert_pinned`). Rejections: INVALID_METADATA, SIZE_MISMATCH, SHA256_MISMATCH, APK_UNSIGNED, CERT_MISMATCH (audit `release.rejected`). Accepted releases are stored as MIRRORED, or PUBLISHED when release_auto_publish is true (audit `release.mirrored`).
- Table app_releases (id ULID, version_code unique, version_name, sha256, size, signing_cert_sha256, min_supported_version_code, notes, published_at, file_path, status MIRRORED|PUBLISHED|WITHDRAWN, rollout_percent 0..100 default 100, created_at).
- OTA v2: the mirror also downloads `release.json.sig` when present and stores the manifest **byte-exact** (manifest_b64) plus the signature verbatim (manifest_sig); RELEASES_DIR keeps couple-X.Y.Z.release.json (raw bytes) and .release.json.sig. When backend env OTA_PUBLIC_KEY is set, a missing or non-verifying signature is rejected (SIGNATURE_MISSING / SIGNATURE_INVALID, permanent). Invalid schema-2 fields are INVALID_METADATA. Columns added (V3): manifest_b64, manifest_sig, protocol_version, min_peer_protocol, min_sdk; (V4): stage CANARY|ALL default ALL, paused_at, pause_reason, plus tables device_reports and release_health. A manifest with rollout "staged" is stored with stage CANARY when release settings have at least one canaryDeviceId, otherwise ALL.
- Anti-downgrade: server_settings `release.highWater` = highest versionCode ever PUBLISHED (also at least the highest currently PUBLISHED). Publishing a lower versionCode (PATCH or auto-publish) is refused: PATCH -> 409 DOWNGRADE_REFUSED; auto-publish stores it as MIRRORED (audit `release.downgrade_refused`). Equal is allowed (re-publish after withdraw).

### App endpoints (any signed-in user)
- GET /app/update?versionCode=N&deviceId=X[&protocolVersion=P&minPeerProtocol=M&sdkInt=S] (deviceId defaults to the token's did; it only selects the rollout bucket) -> {latest: {versionName, versionCode, sha256, size, signingCertSha256, notes, downloadUrl, publishedAt, manifest (base64 of the exact release.json bytes) | null, manifestSig | null, protocolVersion, minPeerProtocol} | null, force: bool, reason: null|"MIN_SUPPORTED"|"PEER_PROTOCOL"|"PEER_NEWER", peer: {versionCode, protocolVersion, minPeerProtocol, reportedAt, compatible} | null}.
  - Device report: every call upserts device_reports for the **token's** did (never the query deviceId): {userId, versionCode=N, protocolVersion=P (default 1), minPeerProtocol=M (default 1), sdkInt=S|null, reportedAt}. POST /app/health-report updates it too.
  - peer = the most recent report of any device of the other couple member (null if unpaired or never reported). compatible = self.P >= peer.minPeerProtocol && peer.protocolVersion >= self.M.
  - Paused releases (paused_at set) are invisible everywhere below, including forced updates. A CANARY-stage release is visible only to devices whose token did is in release settings canaryDeviceIds (not for force).
  - Only PUBLISHED releases whose APK file exists count. newest = the published release with the highest versionCode.
  - newest = the highest published, not-paused release visible to the device (stage ALL, or CANARY for a canary device). force = N < newest.minSupportedVersionCode (reason MIN_SUPPORTED). When force is true, latest = newest regardless of rollout.
  - Else if peer != null and P < peer.minPeerProtocol (the partner moved to a protocol this build cannot speak): force = true, reason PEER_PROTOCOL, latest = the highest published release with versionCode > N and protocolVersion >= peer.minPeerProtocol, ignoring rollout/stage (if none exists: no force, normal selection).
  - Otherwise latest = the highest published release with versionCode > N whose rollout includes the device, or null. reason = PEER_NEWER when latest != null and peer.versionCode > N.
  - Rollout: bucket = (first 4 bytes of SHA-256(UTF-8(deviceId + ":" + versionCode)) as an unsigned big-endian int) mod 100, where versionCode is the release's own. The device is included when bucket < rolloutPercent (0 = nobody, 100 = everybody; raising the percentage only adds devices).
  - downloadUrl is the path "/api/v1/app/releases/{versionCode}/apk". Clients resolve it against baseUrl and refuse any other origin, because the request carries the access token.
- POST /app/health-report {versionCode, fromVersionCode?, status:"OK"|"CRASH"|"FAILED", mqttConnected?, detail? (<= 500 chars), protocolVersion?, minPeerProtocol?, sdkInt?} -> 204 (400 VALIDATION_ERROR for a bad status). Stored in release_health (id, version_code, device_id = token did, user_id, status, from_version_code, mqtt_connected, detail, created_at) and refreshes the device report.
  - CRASH/FAILED for a PUBLISHED, not-paused release -> auto-pause: paused_at = now, pause_reason = "<status> from device <did>: <detail>", audit `release.auto_paused`.
  - OK from a canary device for a CANARY-stage, not-paused release that has no CRASH/FAILED report -> stage = ALL, audit `release.promoted`.
  - Unknown versionCode: stored, no effect.
- GET /app/releases/{versionCode}/apk (PUBLISHED only, otherwise 404 RELEASE_NOT_FOUND / RELEASE_FILE_MISSING): streams the file with Content-Length, `ETag: "<sha256>"`, Accept-Ranges: bytes, Content-Type application/vnd.android.package-archive. Supports a single `Range: bytes=a-b | a- | -n` (206 + Content-Range, 416 + `Content-Range: bytes */size` when unsatisfiable), If-Range (a stale validator gets the full 200), and If-None-Match (304). Multi-range requests get the full body.

### App rules
- Checks on start/login at most once per 6 h, and manually from 我的 → 检查更新. The last response is cached so a forced update keeps blocking after a restart.
- Downloads through WorkManager (progress notification, Range resume using If-Range on the ETag), then verifies size and sha256, archive packageName == own package, archive versionCode == latest.versionCode, archive signing certificate set == the installed app's certificate set, and signingCertSha256 ∈ that set. Anything else is refused and deleted.
- OTA v2 before offering an update: if BuildConfig.OTA_PUBLIC_KEY is set, latest.manifest/manifestSig must verify (Ed25519) and the signed manifest's packageName (if present; the app's package without ".debug"), versionCode, versionName, sha256, size and signingCertSha256 must equal `latest`; otherwise the offer is ignored. With no pinned key (debug) verification is skipped and logged. Anti-downgrade: highestSeen = max(installed versionCodes, versionCodes of verified manifests); an offer is accepted only if versionCode > installed and >= highestSeen. Cached responses are re-checked on start.
- Install: if Shizuku is running and permission is granted, run a silent `pm install -r -S <size>` (APK over stdin) and then `am start`. Otherwise use a system PackageInstaller session, which needs REQUEST_INSTALL_PACKAGES / "install unknown apps" and shows the user confirmation. On API 34+ the session requests update ownership (ENFORCE_UPDATE_OWNERSHIP).
- Health: right before installing, the app persists {from, to}. On the first start of version `to` (configured + signed in) it posts OK on the first MQTT connect, FAILED if none within 10 min, or CRASH if an uncaught exception hit version `to` before that (detail = exception class names only). The record is cleared once the server answers 2xx/4xx, when a newer version is installed, or after 7 days.
- force = true shows a blocking dialog (no dismiss, back is ignored). Otherwise the dialog can be dismissed and shows the notes. reason is shown as a banner (PEER_PROTOCOL: "对方已升级到新的通信协议…"; PEER_NEWER: "对方已经更新到新版本…").

### Admin endpoints (role=admin, all audit-logged)
- GET /admin/releases -> [{id, versionCode, versionName, sha256, size, signingCertSha256, minSupportedVersionCode, notes, publishedAt, status, rolloutPercent, createdAt, fileExists, signed, protocolVersion, minPeerProtocol, minSdk, stage, pausedAt, pauseReason}] (newest first).
- GET /admin/releases/{versionCode}/health -> [{versionCode, deviceId, userId, status, fromVersionCode, mqttConnected, detail, createdAt}] (newest first, max 100).
- GET /admin/devices -> [{deviceId, userId, versionCode, protocolVersion, minPeerProtocol, sdkInt, reportedAt}] (newest first).
- POST /admin/releases/sync -> 202 SyncState and the pass runs in the background, because downloads can exceed Cloudflare's 100 s limit. `?wait=true` runs it synchronously and returns 200 (used by tests). GET /admin/releases/sync -> SyncState = {running, lastRunAt, lastFinishedAt, lastResult:"ok"|"error"|null, lastError, mirrored:[versionCode], rejected:["versionCode: REASON"]}. A manual sync also retries releases that were rejected permanently.
- PATCH /admin/releases/{versionCode} {status?, rolloutPercent? (0..100), minSupportedVersionCode? (0..versionCode), notes?, stage? ("CANARY"|"ALL"), paused? (true = pause now with reason "manual", false = resume), overridePeerCheck? (default false)}. PUBLISHED requires the file to exist.
  - 409 DOWNGRADE_REFUSED when the result is PUBLISHED and versionCode < release.highWater.
  - 409 PEER_CANNOT_FOLLOW when the result is PUBLISHED, minSdk > 0, and some device reported within 30 days with sdkInt < minSdk would be forced by it (its versionCode < minSupportedVersionCode, or its protocolVersion < the release's minPeerProtocol). The message lists the devices. overridePeerCheck: true skips this (audited).
- GET/PUT /admin/releases/settings {repo, githubToken, autoPublish, pollMinutes (0 or 5..1440), expectedSigningCertSha256, canaryDeviceIds ([string], max 10, each 1..128 chars; omitted = keep)}. The settings are stored in server_settings key `release.settings`, separate from /admin/config so they do not bump configVersion. githubToken is write-only and masked like other secrets (null/omitted/masked = keep, "" = clear). For expectedSigningCertSha256, "" clears the pin; colons and case are normalized.
- GET /admin/server/update-status -> {backend:{version (APP_VERSION env, else the build version), buildVersion, revision (APP_REVISION)}, updater: <last JSON posted by the server-updater, verbatim> | null, updaterReportedAt}.

### Internal
- POST /internal/updater/status (header X-Internal-Token == MQTT_HOOK_SECRET; 404 when the secret is unset or wrong, or when CF-Connecting-IP is present) with a JSON object of at most 16 KB, e.g. {channel, enabled, current:{backend, admin}, lastCheck, lastResult, lastError} -> 204. It is stored in server_settings key `updater.status`. An audit entry (`updater.status`) is written only when current/lastResult/lastError change.

### Migration rule (expand-only)
- Flyway migrations must be expand-only: add tables, nullable columns, or columns with defaults, plus indexes. Never drop, rename, or narrow anything in the same release that stops using it. The server-updater may roll the backend back to the previous image after a failed health check, and that image must still run against the newer schema. Destructive cleanups ship at least one release later, in their own migration.
- Backend env additions: RELEASES_DIR (default /data/releases), HTTPS_PROXY / NO_PROXY (outbound mirror), APP_VERSION / APP_REVISION (set by the image), OTA_PUBLIC_KEY (optional; base64 raw Ed25519 OTA release public key, enables server-side manifest signature checks).
- OTA v2 design rationale and threat model: docs/ota-design.md.

## Chat (P1a: text messages; P1c: images + stickers) — authoritative for backend + app
Scope: text, images, stickers (表情包), reply/quote, recall, read receipts, offline outbox. Voice and calls come later.

### Model
- Message = {id, coupleId, senderId, type:"text"|"image"|"sticker", text, replyTo:String|null, clientTs:Long, createdAt:Long, recalledAt:Long|null, mediaId:String|null, width:Int|null, height:Int|null}
- image / sticker messages carry `mediaId` (a media object in the shared Feed media storage, read through GET /media/{mediaId} and /media/{mediaId}/thumb with the same couple ACL) plus its pixel width/height; their text is "". Recalling one clears mediaId/width/height and deletes the media object.
- id is a ULID generated by the **client**; the server is idempotent on id (re-sending the same id returns the stored message, 200). A different sender or couple reusing an id → 409 ID_CONFLICT.
- text: 1..4000 chars after trim (VALIDATION_ERROR otherwise). A recalled message keeps its row; text comes back as "" and recalledAt is set.
- createdAt is server time and the sort key (ties broken by id). clientTs is informational.
- Requires a paired user (403 NOT_PAIRED otherwise). Members only ever see their own couple's messages.

### REST (under /api/v1, Bearer auth)
- POST /chat/messages {id, type?="text", text, replyTo?, clientTs, mediaId?, width?, height?, stickerUrl?} -> Message. replyTo must be a message of the same couple (else VALIDATION_ERROR).
  - type "image": the client first uploads through POST /media (same compression, EXIF strip and thumbnail as Feed), then sends `mediaId` (+ width/height as a hint; the server's stored dimensions win). The media must be an upload of the same couple not yet used by any post/message (409 MEDIA_ALREADY_USED); unknown → VALIDATION_ERROR.
  - type "sticker": the client sends `stickerUrl` taken from a sticker search result. The server downloads it — only from hosts that appeared in provider search results, http(s) only, image/* content-type only, ≤ 5 MB, at most 3 redirects (each hop re-checked), never to loopback/private/link-local/CGNAT addresses — and stores it as media (GIF kept as-is); the message carries the new `mediaId`. Rejections → 400 STICKER_REJECTED; upstream failure → 502 STICKER_FETCH_FAILED. Idempotent replays of the same id never download again.
- GET /chat/stickers/search?q=<1..50 chars>&page=<1..50> -> [{url, width?, height?}] (paired users only). Proxies https://oiapi.net/api/EmoticonPack (keyword, page, limit=30; no key); when it fails or returns nothing and admin config `stickers.apihz` {id, apiKey} is set, falls back to https://cn.apihz.cn/api/img/apihzbqb.php (type=2, words, page, limit=20). `apiKey` is a write-only secret (masked on read like other admin secrets); the section is optional.
- GET /chat/messages?after=<id>&limit=N -> {messages:[Message...], hasMore} ascending, strictly newer than `after` (omit for the oldest page). Used for catch-up after (re)connect.
- GET /chat/messages?before=<id>&limit=N -> same shape, the N messages just older than `before`, ascending. Omit both for the newest N. limit default 50, max 200.
- POST /chat/messages/{id}/recall -> Message. Only the sender, within 120 s of createdAt (403 RECALL_FORBIDDEN / 409 RECALL_EXPIRED). Idempotent.
- POST /chat/read {upToId} -> 204. Monotonic per user (an older id is ignored).
- GET /chat/state -> {myReadUpTo:String|null, partnerReadUpTo:String|null}

### MQTT push (server → clients)
- Topic couple/{cid}/chat, QoS 1, not retained. Payload {kind, ...}:
  - {kind:"message", message:Message}
  - {kind:"recall", id, recalledAt}
  - {kind:"read", userId, upToId, ts}
- The server publishes after the DB commit (embedded broker: internal publish; EMQX: its HTTP publish API, env EMQX_API_URL/EMQX_API_KEY/EMQX_API_SECRET — missing config just disables push, catch-up still works). Clients may only **subscribe** to couple/{cid}/chat; client publishes to it are denied by ACL.
- MQTT is a hint, not the source of truth: on every (re)connect and on app foreground the client calls GET /chat/messages?after=<last known id> until hasMore=false, and GET /chat/state.

### Typing (client ↔ client)
- Topic couple/{cid}/typing, QoS 0, not retained. Clients may publish **and** subscribe (ACL); the server does not store or relay anything itself. Payload {userId, typing:Boolean, ts}.
- Sender: typing=true at most once per 3 s while the input text changes; typing=false on send, when the input is cleared, or after 5 s without changes (only if a true was sent).
- Receiver: ignore its own echo (userId = self); show "TA 正在输入…" while the partner's last event is typing=true and under 6 s old (measured from local receive time, so clock skew does not matter); typing=false hides it at once.

### App rules
- Room is the source the UI renders. Sending = insert locally with state=PENDING → outbox worker POSTs (retry with backoff while offline, survives process death) → SENT with server createdAt. 4xx other than 409/429 → FAILED (tap to retry). Duplicate events (MQTT + catch-up) are merged by id.
- Read: when the chat screen is visible, POST /chat/read with the newest partner message id (debounced). Every one of my confirmed messages whose (createdAt, id) ≤ that of the partnerReadUpTo message shows "已读", newer ones "未读"; unconfirmed ones show "发送中" / "发送失败，点按重试" (if the cursor message is not loaded locally, compare ULID ids).
- Images / stickers: the outbox uploads a picked image (compressed like Feed: longest side 2048, JPEG 85, kept under filesDir/outbox until sent) before POSTing the message; stickers are sent by URL. Bubbles show the media (thumb for images, original for stickers so GIFs animate); tap opens a full-screen viewer.
- Debug demo account: DemoBackend implements these endpoints in memory (image messages via its in-memory /media; sticker search returns built-in sample URLs, or proxies oiapi directly when online) and the partner "TA（演示）" auto-replies ~1.5 s later, showing "正在输入" for ~1 s first; events (chat and typing) reach the app through an in-process flow instead of MQTT.

## Feed (P1: 动态) — authoritative for backend + app
Scope of P1 feed: posts with text + images, likes, comments, deletion. Video/voice posts come later. Uses the shared media storage below.

### Media storage (shared by feed / avatar / later chat images)
- Providers come from admin config `storage` (local | minio | r2). Buckets are **always private**: every download goes through the backend.
- POST /api/v1/media (Bearer, multipart/form-data, single `file` field) -> {storageKey, mimeType, size, width, height, thumbKey|null}. Only images in P1: image/jpeg, image/png, image/webp; ≤ 10 MB; EXIF/GPS metadata is stripped server-side on ingest; thumbnails (≤ 512 px JPEG) are generated server-side when width or height > 512.
- GET /api/v1/media/{storageKey} and GET /api/v1/media/{storageKey}/thumb (Bearer): streams the bytes, `ETag: "<sha256>"`, If-None-Match → 304, Cache-Control private. **403 MEDIA_FORBIDDEN unless the media belongs to the caller's couple** (or is an avatar of the couple's members). Unknown key → 404 MEDIA_NOT_FOUND.
- Uploads are couple-scoped by the uploader's coupleId. Uploads not referenced by any post/avatar within 24 h are deleted by a periodic orphan sweep; deleting a post deletes its media files immediately.
- POST /api/v1/users/me/avatar {storageKey} -> 204. Sets the caller's avatarUrl to "/api/v1/media/{storageKey}" (relative; clients resolve against baseUrl). The previous avatar file is deleted.

### Model
- Post = {id (client ULID, server idempotent on id: re-POSTing the same id returns the stored post 200; a different couple reusing an id → 409 ID_CONFLICT), coupleId, authorId, text (0..4000 chars after trim; at least one of text/media must be non-empty, else VALIDATION_ERROR), media: [PostMedia] (0..9), createdAt, likes: {count, likedByMe}, comments: [Comment] (full list — the couple has 2 people), commentCount}
- PostMedia = {storageKey, thumbKey|null, mimeType, width, height, size}. Every referenced storageKey must be an upload of the same couple, else VALIDATION_ERROR; a key may be used by only one post ever (409 MEDIA_ALREADY_USED otherwise).
- Comment = {id (client ULID, same idempotency rule as posts), postId, authorId, text (1..1000 chars after trim), createdAt}
- Likes: PK (postId, userId); liking twice is a no-op (200).

### REST (under /api/v1, Bearer, requires a paired user 403 NOT_PAIRED)
- POST /feed/posts {id, text, mediaKeys?: [storageKey...]} -> Post
- GET /feed/posts?before=<id>&limit=N -> {posts: [Post...] (desc by createdAt, ties by id), hasMore}. Omit before for the newest page. limit default 20, max 50.
- DELETE /feed/posts/{id} -> 204. Only the author (403 NOT_AUTHOR).
- POST /feed/posts/{id}/like -> 200 {count}; DELETE /feed/posts/{id}/like -> 200 {count}
- POST /feed/posts/{id}/comments {id, text} -> Comment; DELETE /feed/comments/{id} -> 204 (comment author only)

### MQTT push (server → clients)
- Topic couple/{cid}/feed, QoS 1, not retained. Clients subscribe; client publishes denied by ACL. Payloads:
  - {kind:"post", post:Post} — sent after the DB commit, post includes likes/comments as of send time
  - {kind:"delete", id}
  - {kind:"like", postId, userId, count}
  - {kind:"comment", postId, comment:Comment}
  - {kind:"comment_delete", postId, id}
- MQTT is a hint: clients merge by id and may refetch. Catch-up on (re)connect/foreground = GET /feed/posts (newest page) + merge.
- Server→client publish infra: embedded broker uses internalPublish; external EMQX uses its HTTP publish API (env EMQX_API_URL/EMQX_API_KEY/EMQX_API_SECRET — missing config just disables push, catch-up still works).

### App rules
- Room caches posts/comments/likes; UI renders from Room. Publish = insert locally (author view) → upload media (already done via the publish flow) → POST /feed/posts → replace local row with the server Post. Offline publish queues like the chat outbox.
- 空状态：未发过任何动态时显示引导卡（「发第一条动态」）。


## TA (P1b: TA 页) — authoritative for backend + app
Scope: partner card (profile + presence), shared important days (anniversary/birthdays/custom dates), and a minimal privacy toggle. TA 页数据量小、改动低频：**没有 MQTT 推送**，App 每次进入 Tab 时拉取一次（下拉刷新同路径）。

### Model
- Profile = {bio: String|null (≤200 chars), birthday: "MM-DD"|null, birthYear: Int|null (1900..2100; null = 不展示年龄), showBirthday: Boolean (default true)}
- ImportantDay = {id (server ULID), coupleId, name (1..50 chars), month (1..12), day (1..31), year: Int|null (null = 不计年龄/周年数), createdBy, createdAt}
- 两人共享同一份 days 列表；任一成员可增改删。
- 生日存在用户档案上（自己的 month/day/year），TA 卡展示的是**对方**的生日；对方 `showBirthday=false` 时，服务器对生日字段返回 null（`birthdayVisible:false` 提示前端显示「已隐藏」）。

### REST (under /api/v1, Bearer, requires a paired user 403 NOT_PAIRED)
- GET /ta/summary -> {me: Profile & {displayName}, partner: {id, displayName, username, avatarUrl, bio, birthday, birthYear, birthdayVisible}, anniversary: Long|null, pairedAt: Long, days: [ImportantDay]}
- PUT /ta/me {bio?, birthday?, birthYear?, showBirthday?} -> 204（只改自己的档案；字段缺省 = 保持；显式 null = 清除）
- POST /ta/days {name, month, day, year?} -> ImportantDay（服务端生成 id）
- PUT /ta/days/{id} {name?, month?, day?, year?} -> ImportantDay
- DELETE /ta/days/{id} -> 204
- Validation: month 1..12, day 1..31（不校验月份天数——2 月 30 日允许，回忆类日子没有严格日历语义）; bio ≤ 200; name 1..50. Errors: VALIDATION_ERROR (400), NOT_PAIRED (403), DAY_NOT_FOUND (404).

### App rules
- TA Tab = 对方资料卡（头像/昵称/bio/生日/在线状态圆点——在线状态复用 MQTT presence，不进 /ta/summary）+ 在一起天数 + 重要日子列表（距下次周年/生日的天数升序）+ 「我的档案」编辑入口（bio/生日/showBirthday 开关）。
- days 不进 Room（低频、实时拉取）；进入 Tab 时 GET /ta/summary，下拉刷新同路径。
- Demo 后端在内存里实现同一组端点（TA 卡 + 两条示例日子）。

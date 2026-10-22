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

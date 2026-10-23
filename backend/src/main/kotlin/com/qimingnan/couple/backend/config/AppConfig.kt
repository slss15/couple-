package com.qimingnan.couple.backend.config

import java.io.File

/** Where MQTT clients connect: the in-process Moquette broker, or an external EMQX that calls the HTTP hooks. */
enum class MqttBrokerMode {
    EMBEDDED, EXTERNAL;

    companion object {
        fun parse(v: String?): MqttBrokerMode = when (v?.trim()?.lowercase()) {
            null, "", "embedded" -> EMBEDDED
            "external", "emqx" -> EXTERNAL
            else -> error("MQTT_BROKER_MODE must be embedded or external (got '$v')")
        }
    }
}

/**
 * Runtime configuration. Production reads it from environment variables (see contract "Env (backend)");
 * tests construct it directly.
 */
data class AppConfig(
    val port: Int = 8080,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val jwtSecret: String,
    /** Null or blank = registration disabled. */
    val registrationCode: String?,
    /** Optional base64 Ed25519 private key (32-byte raw seed or PKCS#8 DER). Null = generate & persist in DB. */
    val bootstrapPrivateKey: String? = null,
    /** Optional: this username becomes admin. When unset, the first registered user becomes admin. */
    val adminUsername: String? = null,
    /**
     * Optional shared secret EMQX must send in header X-Internal-Token when calling /internal/mqtt/ hooks.
     * Not in the contract's env list (addition); strongly recommended in deployments.
     */
    val mqttHookSecret: String? = null,
    /** Max auth requests (register/login/refresh) per client IP per minute. */
    val authRateLimitPerMinute: Int = 30,
    val accessTokenTtlMs: Long = 15 * 60 * 1000L,
    val refreshTokenTtlMs: Long = 30L * 24 * 60 * 60 * 1000L,
    val pairCodeTtlMs: Long = 10 * 60 * 1000L,
    val unpairRequestTtlMs: Long = 7L * 24 * 60 * 60 * 1000L,
    val dbMaxPoolSize: Int = 10,
    /** Directory where mirrored release APKs live (env RELEASES_DIR). */
    val releasesDir: String = "/data/releases",
    /** GitHub REST API root; overridable so tests can point the mirror at a fake server. */
    val githubApiBase: String = "https://api.github.com",
    /** Outbound proxy for the release mirror, from HTTPS_PROXY / https_proxy (null = direct). */
    val outboundProxy: String? = null,
    /** Hosts that bypass [outboundProxy] (NO_PROXY, comma separated). */
    val noProxy: String? = null,
    /** Start the periodic release mirror job. Tests turn it off and trigger syncs explicitly. */
    val releasePollEnabled: Boolean = true,
    /** Delay before the first scheduled mirror run after startup. */
    val releasePollInitialDelayMs: Long = 30_000L,
    /** Retry backoff base for outbound GitHub requests (doubles per attempt). */
    val releaseRetryBaseMs: Long = 2_000L,
    val releaseRetryAttempts: Int = 4,
    /** Backend version reported to the admin (APP_VERSION env, falls back to the build version). */
    val appVersion: String? = null,
    /** Optional VCS revision of the running image (APP_REVISION env). */
    val appRevision: String? = null,
    /**
     * embedded (default): Moquette runs inside this process and the EMQX hooks under /internal/mqtt are disabled.
     * external: EMQX authenticates through POST /internal/mqtt/auth|acl (requires MQTT_HOOK_SECRET, enforced in [fromEnv]).
     */
    val mqttBrokerMode: MqttBrokerMode = MqttBrokerMode.EMBEDDED,
    /** Interface the embedded broker binds to. Loopback by default: cloudflared on the same host is the only ingress. */
    val mqttBindHost: String = "127.0.0.1",
    /** Embedded broker MQTT-over-WebSocket port (path [mqttWsPath]). */
    val mqttWsPort: Int = 8083,
    val mqttWsPath: String = "/mqtt",
    /** Optional plain MQTT/TCP port for the embedded broker (null = disabled). Always bound to [mqttBindHost]. */
    val mqttTcpPort: Int? = null,
    /**
     * Directory for the embedded broker's persistent store (sessions, subscriptions, queued QoS1/2 messages,
     * retained messages). Null = in-memory only (everything is lost on restart).
     */
    val mqttDataDir: String? = null,
    /** Persistent (cleanSession=false) sessions not seen for this long are purged, bounding queued messages. */
    val mqttSessionExpiry: String = "7d",
    /** Max MQTT packet size accepted by the embedded broker (bytes). */
    val mqttMaxMessageBytes: Int = 64 * 1024,
    /** When set, the backend also serves the admin SPA from this directory (Windows-native: no nginx/Caddy). */
    val adminStaticDir: String? = null,
    /** apiBase written into /config.js for the admin SPA (COUPLE_API_BASE / ADMIN_API_BASE); empty = same origin. */
    val adminApiBase: String = "",
    /** Injectable clock (epoch ms) so tests can move time. */
    val clock: () -> Long = { System.currentTimeMillis() },
) {
    init {
        require(jwtSecret.length >= 32) { "JWT_SECRET must be at least 32 characters" }
    }

    companion object {
        /**
         * Process environment overlaid with the optional KEY=VALUE file named by COUPLE_ENV_FILE (the Windows service
         * points it at config\couple.env). A non-blank value in the file wins; blank or missing falls back to the
         * process environment.
         */
        fun systemEnv(): (String) -> String? {
            val file = System.getenv("COUPLE_ENV_FILE")?.takeIf { it.isNotBlank() }?.let(::File)
            val fromFile = if (file != null) EnvFile.parse(file.readText(Charsets.UTF_8)) else emptyMap()
            return { name -> fromFile[name]?.takeIf { it.isNotBlank() } ?: System.getenv(name) }
        }

        fun fromEnv(env: (String) -> String? = systemEnv()): AppConfig {
            fun req(name: String): String =
                env(name)?.takeIf { it.isNotBlank() } ?: error("Missing required environment variable $name")
            fun opt(name: String): String? = env(name)?.takeIf { it.isNotBlank() }
            val brokerMode = MqttBrokerMode.parse(opt("MQTT_BROKER_MODE"))
            if (brokerMode == MqttBrokerMode.EXTERNAL) {
                // The EMQX hooks decide who may connect: never run them unauthenticated.
                req("MQTT_HOOK_SECRET")
            }
            return AppConfig(
                port = opt("PORT")?.toInt() ?: 8080,
                dbUrl = req("DB_URL"),
                dbUser = req("DB_USER"),
                dbPassword = env("DB_PASSWORD") ?: "",
                jwtSecret = req("JWT_SECRET"),
                registrationCode = opt("REGISTRATION_CODE"),
                bootstrapPrivateKey = opt("BOOTSTRAP_PRIVATE_KEY"),
                adminUsername = opt("ADMIN_USERNAME"),
                mqttHookSecret = opt("MQTT_HOOK_SECRET"),
                authRateLimitPerMinute = opt("AUTH_RATE_LIMIT_PER_MINUTE")?.toInt() ?: 30,
                releasesDir = opt("RELEASES_DIR") ?: "/data/releases",
                outboundProxy = opt("HTTPS_PROXY") ?: opt("https_proxy") ?: opt("ALL_PROXY") ?: opt("all_proxy"),
                noProxy = opt("NO_PROXY") ?: opt("no_proxy"),
                appVersion = opt("APP_VERSION") ?: opt("OCI_IMAGE_VERSION"),
                appRevision = opt("APP_REVISION") ?: opt("OCI_IMAGE_REVISION"),
                mqttBrokerMode = brokerMode,
                mqttBindHost = opt("MQTT_BIND_HOST") ?: "127.0.0.1",
                mqttWsPort = opt("MQTT_WS_PORT")?.toInt() ?: 8083,
                mqttTcpPort = opt("MQTT_TCP_PORT")?.toInt(),
                mqttDataDir = opt("MQTT_DATA_DIR"),
                mqttSessionExpiry = opt("MQTT_SESSION_EXPIRY") ?: "7d",
                adminStaticDir = opt("ADMIN_STATIC_DIR"),
                adminApiBase = opt("COUPLE_API_BASE") ?: opt("ADMIN_API_BASE") ?: "",
            )
        }
    }
}

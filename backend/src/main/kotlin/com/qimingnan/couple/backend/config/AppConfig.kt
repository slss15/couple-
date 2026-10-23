package com.qimingnan.couple.backend.config

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
    /** Injectable clock (epoch ms) so tests can move time. */
    val clock: () -> Long = { System.currentTimeMillis() },
) {
    init {
        require(jwtSecret.length >= 32) { "JWT_SECRET must be at least 32 characters" }
    }

    companion object {
        fun fromEnv(env: (String) -> String? = System::getenv): AppConfig {
            fun req(name: String): String =
                env(name)?.takeIf { it.isNotBlank() } ?: error("Missing required environment variable $name")
            fun opt(name: String): String? = env(name)?.takeIf { it.isNotBlank() }
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
            )
        }
    }
}

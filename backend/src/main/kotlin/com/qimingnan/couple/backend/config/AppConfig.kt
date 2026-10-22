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
            )
        }
    }
}

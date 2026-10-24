package com.qimingnan.couple.updater

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/** dotenv parser, same rules as the backend's EnvFile (KEY=VALUE, # comments, optional quotes, no interpolation). */
object EnvFile {
    private val KEY = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("export ")) line = line.removePrefix("export ").trimStart()
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            if (!KEY.matches(key)) continue
            var value = line.substring(eq + 1).trim()
            if (value.length >= 2 && (value[0] == '"' || value[0] == '\'') && value.last() == value[0]) {
                value = value.substring(1, value.length - 1)
            }
            out[key] = value
        }
        return out
    }

    fun read(file: File?): Map<String, String> =
        if (file != null && file.isFile) parse(file.readText(Charsets.UTF_8)) else emptyMap()
}

/**
 * Updater configuration. Values come from config\couple.env (COUPLE_ENV_FILE), falling back to the process
 * environment; see deploy/windows/config/couple.env.example for every key.
 */
data class Config(
    val installDir: Path,
    val envFile: File?,
    val enabled: Boolean = true,
    val intervalSeconds: Long = 300,
    /** edge | stable | X.Y.Z (pinned). */
    val channel: String = "edge",
    val window: String = "",
    val healthTimeoutSeconds: Long = 90,
    val healthPollMillis: Long = 3_000,
    val healthUrl: String = "http://127.0.0.1:8080/api/v1/health",
    /** GitHub repo "owner/name" release assets may come from (allow-list). */
    val repo: String = "slss15/couple-",
    val githubApi: String = "https://api.github.com",
    val githubToken: String? = null,
    val hookSecret: String = "",
    val hookBind: String = "127.0.0.1",
    val hookPort: Int = 9000,
    val hookMaxSkew: Long = 300,
    val statusPostUrl: String = "http://127.0.0.1:8080/api/v1/internal/updater/status",
    val internalToken: String = "",
    val keepReleases: Int = 3,
    val proxy: String? = null,
    val noProxy: String? = null,
    /** WinSW wrapper of the backend service (stop/start). */
    val serviceExe: Path = installDir.resolve("services").resolve("couple-backend.exe"),
) {
    val releasesDir: Path get() = installDir.resolve("releases")
    val stateDir: Path get() = installDir.resolve("data").resolve("updater")
    val servicesDir: Path get() = installDir.resolve("services")
    val assetPrefix: String get() = "https://github.com/$repo/releases/download/"

    companion object {
        fun load(processEnv: Map<String, String> = System.getenv()): Config {
            val install = processEnv["COUPLE_HOME"]?.takeIf { it.isNotBlank() } ?: "C:\\couple"
            val installDir = Paths.get(install).toAbsolutePath()
            val envFile = File(processEnv["COUPLE_ENV_FILE"]?.takeIf { it.isNotBlank() }
                ?: installDir.resolve("config").resolve("couple.env").toString())
            return fromMap(installDir, envFile, merged(EnvFile.read(envFile), processEnv))
        }

        /** A non-blank value in the env file wins; otherwise the process environment. */
        fun merged(file: Map<String, String>, process: Map<String, String>): (String) -> String? =
            { k -> file[k]?.takeIf { it.isNotBlank() } ?: process[k]?.takeIf { it.isNotBlank() } }

        fun fromMap(installDir: Path, envFile: File?, g: (String) -> String?): Config {
            fun bool(k: String, d: Boolean) = g(k)?.trim()?.lowercase()?.let { it in setOf("1", "true", "yes", "on") } ?: d
            return Config(
                installDir = installDir,
                envFile = envFile,
                enabled = bool("UPDATE_ENABLED", true),
                intervalSeconds = maxOf(30L, g("UPDATE_INTERVAL")?.toLongOrNull() ?: 300L),
                channel = g("UPDATE_CHANNEL") ?: "edge",
                window = g("UPDATE_WINDOW") ?: "",
                healthTimeoutSeconds = g("HEALTH_TIMEOUT")?.toLongOrNull() ?: 90L,
                healthUrl = g("HEALTH_URL") ?: "http://127.0.0.1:${g("PORT") ?: "8080"}/api/v1/health",
                repo = g("UPDATE_REPO") ?: "slss15/couple-",
                githubToken = g("UPDATE_GITHUB_TOKEN"),
                hookSecret = g("DEPLOY_HOOK_SECRET") ?: "",
                hookBind = g("HOOK_BIND") ?: "127.0.0.1",
                hookPort = g("HOOK_PORT")?.toIntOrNull() ?: 9000,
                hookMaxSkew = g("HOOK_MAX_SKEW")?.toLongOrNull() ?: 300L,
                statusPostUrl = g("STATUS_POST_URL") ?: "http://127.0.0.1:${g("PORT") ?: "8080"}/api/v1/internal/updater/status",
                internalToken = g("MQTT_HOOK_SECRET") ?: "",
                keepReleases = maxOf(2, g("KEEP_RELEASES")?.toIntOrNull() ?: 3),
                proxy = g("HTTPS_PROXY") ?: g("https_proxy"),
                noProxy = g("NO_PROXY") ?: g("no_proxy"),
            )
        }
    }
}

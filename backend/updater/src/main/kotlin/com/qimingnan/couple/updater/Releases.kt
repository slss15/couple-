package com.qimingnan.couple.updater

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * On-disk layout under the install dir (default C:\couple):
 *
 *   releases\<version>\        extracted server packages (jre\, app\, admin\, service\couple-backend.xml.in, ...)
 *   releases\CURRENT           the version the backend service runs (config pointer, one line)
 *   services\couple-backend.xml  WinSW config rendered from the current release's template (read at every start)
 *
 * Switching versions = stop service, render the new release's template, write CURRENT, start. No junctions.
 */
class Releases(private val cfg: Config) {
    private val currentFile: Path get() = cfg.releasesDir.resolve("CURRENT")

    fun dir(version: String): Path = cfg.releasesDir.resolve(version)

    fun current(): String? = runCatching { Files.readString(currentFile).trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun setCurrent(version: String) {
        Files.createDirectories(cfg.releasesDir)
        val tmp = currentFile.resolveSibling("CURRENT.tmp")
        Files.writeString(tmp, version + "\n")
        Files.move(tmp, currentFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Throws when [dir] is not a complete server package. */
    fun validate(dir: Path) {
        val required = listOf("jre/bin/java.exe", "app/lib", "service/couple-backend.xml.in", "VERSION")
        val missing = required.filter { !Files.exists(dir.resolve(it)) }
        if (missing.isNotEmpty()) throw IOException("package incomplete, missing: ${missing.joinToString()}")
    }

    /**
     * Extracts [zip] as releases\<version> (replacing a stale copy that is neither current nor [keep]). [check] runs
     * on the extracted, validated staging dir before anything is replaced; throwing aborts the install.
     */
    fun install(zip: Path, version: String, keep: String?, check: (Path) -> Unit = {}) {
        val target = dir(version)
        val staging = cfg.releasesDir.resolve(".staging-$version-${System.nanoTime()}")
        try {
            Files2.extract(zip, staging)
            validate(staging)
            check(staging)
            if (Files.exists(target)) {
                if (version == current() || version == keep) throw IOException("release $version is in use; refusing to overwrite")
                Files2.deleteTree(target)
            }
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            runCatching { Files2.deleteTree(staging) }
        }
    }

    /** Renders services\couple-backend.xml from [version]'s template. */
    fun render(version: String) {
        ServiceTemplate.renderTo(cfg, dir(version), version)
    }

    /** Keeps the newest [Config.keepReleases] release dirs (always keeping current and [alsoKeep]). */
    fun prune(alsoKeep: String?) {
        val dirs = Files.list(cfg.releasesDir).use { s ->
            s.filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }.toList()
        }.sortedByDescending { Files.getLastModifiedTime(it).toMillis() }
        val keep = mutableSetOf<String>()
        current()?.let(keep::add)
        alsoKeep?.let(keep::add)
        for (d in dirs) if (keep.size < cfg.keepReleases) keep.add(d.fileName.toString())
        for (d in dirs) {
            if (d.fileName.toString() in keep) continue
            runCatching { Files2.deleteTree(d) }.onFailure { log("could not delete old release ${d.fileName}: ${it.message}") }
        }
    }
}

/**
 * The backend's WinSW config ships in every release as service\couple-backend.xml.in, so a release can change its
 * own JVM flags. Placeholders: @INSTALL_DIR@, @RELEASE_DIR@, @VERSION@, @JAVA_OPTS@ (JAVA_OPTS from couple.env).
 */
object ServiceTemplate {
    const val DEFAULT_JAVA_OPTS = "-Xms64m -Xmx512m -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"

    fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun render(template: String, installDir: Path, releaseDir: Path, version: String, javaOpts: String): String =
        template
            .replace("@INSTALL_DIR@", xmlEscape(installDir.toString()))
            .replace("@RELEASE_DIR@", xmlEscape(releaseDir.toString()))
            .replace("@VERSION@", xmlEscape(version))
            .replace("@JAVA_OPTS@", xmlEscape(javaOpts))

    fun renderTo(cfg: Config, releaseDir: Path, version: String) {
        val env = EnvFile.read(cfg.envFile)
        val javaOpts = env["JAVA_OPTS"]?.takeIf { it.isNotBlank() } ?: DEFAULT_JAVA_OPTS
        val template = Files.readString(releaseDir.resolve("service").resolve("couple-backend.xml.in"))
        val xml = render(template, cfg.installDir, releaseDir.toAbsolutePath(), version, javaOpts)
        Files.createDirectories(cfg.servicesDir)
        val out = cfg.servicesDir.resolve("couple-backend.xml")
        val tmp = out.resolveSibling("couple-backend.xml.tmp")
        Files.writeString(tmp, xml)
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

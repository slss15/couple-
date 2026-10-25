package com.qimingnan.couple.updater

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Paths
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * couple-updater
 *   serve                         (default) webhook listener + poller, run by the couple-updater service
 *   render-service <releaseDir>   render services\couple-backend.xml for <releaseDir> and set it as CURRENT (install.ps1)
 *   version
 */
fun main(args: Array<String>) {
    when (args.firstOrNull() ?: "serve") {
        "serve" -> serve(Config.load())
        "render-service" -> {
            val dir = Paths.get(args.getOrNull(1) ?: usage()).toAbsolutePath().normalize()
            val cfg = Config.load()
            val releases = Releases(cfg)
            releases.validate(dir)
            if (dir.parent != cfg.releasesDir.toAbsolutePath().normalize()) {
                System.err.println("release dir must be inside ${cfg.releasesDir}"); exitProcess(2)
            }
            val version = dir.fileName.toString()
            releases.render(version)
            releases.setCurrent(version)
            println("rendered ${cfg.servicesDir.resolve("couple-backend.xml")} for $version")
        }
        "version" -> println(Main::class.java.`package`?.implementationVersion ?: "dev")
        else -> usage()
    }
}

private object Main

private fun usage(): Nothing {
    System.err.println("usage: couple-updater [serve | render-service <releaseDir> | version]")
    exitProcess(2)
}

fun serve(cfg: Config) {
    val http = Http(cfg.proxy, cfg.noProxy)
    val api = GitHubApi(cfg, http)
    val up = Updater(
        cfg,
        service = WinswServiceControl(cfg.serviceExe),
        health = http.health(),
        downloader = GitHubDownloader(api, http.downloader()),
        source = GitHubReleaseSource(cfg, http, api),
        postStatus = { body -> http.postStatus(cfg.statusPostUrl, cfg.internalToken, body) },
    )
    log(
        "start channel=${cfg.channel} enabled=${cfg.enabled} interval=${cfg.intervalSeconds}s current=${up.currentVersion()} " +
            "hook=${if (cfg.hookSecret.isNotEmpty()) "on" else "off"} github=${if (api.authenticated) "token" else "anonymous"} listen=${cfg.hookBind}:${cfg.hookPort} window=${cfg.window.ifEmpty { "-" }}",
    )
    http.postStatus(cfg.statusPostUrl, cfg.internalToken, up.publicStatusJson())
    Thread(up::workerLoop, "updater-worker").apply { isDaemon = true }.start()
    Thread({
        while (true) {
            up.enqueuePoll()
            Thread.sleep(cfg.intervalSeconds * 1000)
        }
    }, "updater-poller").apply { isDaemon = true }.start()

    val server = startHttp(up, cfg.hookBind, cfg.hookPort)
    Runtime.getRuntime().addShutdownHook(Thread { server.stop(1); log("stopped") })
    Thread.currentThread().join()
}

fun startHttp(up: Updater, bind: String, port: Int): HttpServer {
    val server = HttpServer.create(InetSocketAddress(bind, port), 16)
    server.executor = Executors.newFixedThreadPool(4)
    server.createContext("/") { ex -> runCatching { handle(up, ex) }.onFailure { log("http error: $it"); runCatching { ex.close() } } }
    server.start()
    return server
}

private fun HttpExchange.json(code: Int, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    responseHeaders.add("Content-Type", "application/json")
    responseHeaders.add("Cache-Control", "no-store")
    sendResponseHeaders(code, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

private fun err(msg: String) = JsonObject(mapOf("error" to JsonPrimitive(msg))).toString()

/** Reads at most [MAX_BODY] + 1 bytes (works with and without Content-Length). */
private fun readCapped(ex: HttpExchange): ByteArray? {
    val declared = ex.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
    if (declared != null && declared > MAX_BODY) return null
    val out = ByteArrayOutputStream()
    val buf = ByteArray(1024)
    ex.requestBody.use { input ->
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BODY) return null
        }
    }
    return out.toByteArray()
}

internal fun handle(up: Updater, ex: HttpExchange) {
    val path = ex.requestURI.rawPath
    when {
        ex.requestMethod == "GET" && path == "/_deploy/status" -> ex.json(200, up.publicStatusJson())
        ex.requestMethod == "GET" && path == "/_deploy/healthz" -> ex.json(200, """{"ok":true}""")
        ex.requestMethod == "POST" && path == "/_deploy/hook" -> {
            val body = readCapped(ex) ?: return ex.json(413, err("bad body size"))
            if (body.isEmpty()) return ex.json(400, err("bad body size"))
            val r = verifyHook(
                up.cfg.hookSecret, ex.requestHeaders.getFirst("X-Deploy-Timestamp"), ex.requestHeaders.getFirst("X-Deploy-Signature"),
                body, System.currentTimeMillis() / 1000.0, up.cfg.hookMaxSkew, up.replay,
            )
            if (r.status != 200) {
                log("hook rejected: ${r.reason}")
                return ex.json(r.status, err(r.reason))
            }
            val (e, job) = validatePayload(up.cfg, r.body!!)
            if (e != null || job == null) {
                log("hook rejected: $e")
                return ex.json(400, err(e ?: "invalid"))
            }
            if (!up.cfg.enabled) return ex.json(503, err("updates disabled (UPDATE_ENABLED=false)"))
            up.enqueueHook(job)
            log("hook accepted: server ${job.version} sha256=${job.sha256.take(12)} sha=${job.sha}")
            ex.json(202, JsonObject(mapOf("accepted" to JsonPrimitive(true), "service" to JsonPrimitive("server"), "version" to JsonPrimitive(job.version))).toString())
        }
        else -> ex.json(404, err("not found"))
    }
}

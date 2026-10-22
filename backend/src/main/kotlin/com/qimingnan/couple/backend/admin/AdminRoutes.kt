package com.qimingnan.couple.backend.admin

import com.qimingnan.couple.backend.audit.AuditActions
import com.qimingnan.couple.backend.audit.AuditService
import com.qimingnan.couple.backend.auth.UserRepo
import com.qimingnan.couple.backend.auth.authUser
import com.qimingnan.couple.backend.bootstrap.BootstrapService
import com.qimingnan.couple.backend.common.Roles
import com.qimingnan.couple.backend.common.badRequest
import com.qimingnan.couple.backend.common.forbidden
import com.qimingnan.couple.backend.db.Db
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** Must be mounted inside authenticate(JWT_AUTH). Role is re-checked against the DB, not just the JWT claim. */
fun Route.adminRoutes(db: Db, audit: AuditService, configService: AdminConfigService, bootstrap: BootstrapService) {
    suspend fun ApplicationCall.requireAdmin(): String {
        val userId = authUser().userId
        val role = db.tx { UserRepo.byId(userId)?.role }
        if (role != Roles.ADMIN) {
            audit.log(userId, AuditActions.ADMIN_DENIED, "${request.httpMethod.value} ${request.path()}")
            throw forbidden("FORBIDDEN", "Admin role required")
        }
        return userId
    }

    route("/admin") {
        get("/config") {
            val admin = call.requireAdmin()
            val view = configService.view()
            audit.log(admin, AuditActions.ADMIN_CONFIG_VIEW, "config", mapOf("configVersion" to view.configVersion))
            call.respond(view)
        }
        put("/config") {
            val admin = call.requireAdmin()
            val (view, changedSecrets) = configService.update(call.receive())
            audit.log(
                admin, AuditActions.ADMIN_CONFIG_UPDATE, "config",
                // Only the *names* of changed secrets are logged, never values.
                mapOf("configVersion" to view.configVersion, "changedSecrets" to changedSecrets.joinToString(",")),
            )
            call.respond(view)
        }
        post("/bootstrap") {
            val admin = call.requireAdmin()
            val res = bootstrap.issue()
            audit.log(admin, AuditActions.ADMIN_BOOTSTRAP, "bootstrap", mapOf("configVersion" to res.json["configVersion"]))
            call.respond(res)
        }
        get("/audit") {
            val admin = call.requireAdmin()
            val limit = call.request.queryParameters["limit"]?.let { it.toIntOrNull() ?: throw badRequest("VALIDATION_ERROR", "limit must be an integer") } ?: 50
            val before = call.request.queryParameters["before"]?.let { it.toLongOrNull() ?: throw badRequest("VALIDATION_ERROR", "before must be epoch ms") }
            val entries = audit.list(limit.coerceIn(1, 200), before)
            audit.log(admin, AuditActions.ADMIN_AUDIT_VIEW, "audit", mapOf("limit" to limit, "before" to before))
            call.respond(entries)
        }
    }
}

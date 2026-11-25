package com.qimingnan.couple.backend.layout

import com.qimingnan.couple.backend.admin.requireAdmin
import com.qimingnan.couple.backend.audit.AuditActions
import com.qimingnan.couple.backend.audit.AuditService
import com.qimingnan.couple.backend.db.Db
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** Any signed-in user: current layout and theme defaults. */
fun Route.appearanceRoutes(service: AppearanceService) {
    get("/app/appearance") {
        call.respond(service.view())
    }
}

/** Admin-only: view and replace the shared appearance config. */
fun Route.adminAppearanceRoutes(
    db: Db,
    audit: AuditService,
    service: AppearanceService,
) {
    route("/admin") {
        get("/appearance") {
            val admin = call.requireAdmin(db, audit)
            val view = service.view()
            audit.log(admin, AuditActions.ADMIN_APPEARANCE_VIEW, "appearance", mapOf("appearanceVersion" to view.appearanceVersion))
            call.respond(view)
        }
        put("/appearance") {
            val admin = call.requireAdmin(db, audit)
            val updated = service.update(call.receive())
            audit.log(
                admin, AuditActions.ADMIN_APPEARANCE_UPDATE, "appearance",
                mapOf("appearanceVersion" to updated.appearanceVersion, "zones" to updated.layout.zones.size),
            )
            call.respond(updated)
        }
    }
}

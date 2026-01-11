package com.peik.cornernas.server

import android.content.Context
import com.peik.cornernas.storage.SafFileManager
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.application.install
import io.ktor.server.routing.Routing

class KtorServer(
    private val context: Context,
    private val safFileManager: SafFileManager
) {
    private var engine: ApplicationEngine? = null

    fun start() {
        if (engine != null) return
        engine = embeddedServer(Netty, host = "0.0.0.0", port = 8080) {
            install(Routing) {
                configureRoutes(context, safFileManager)
            }
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(1000, 2000)
        engine = null
    }
}

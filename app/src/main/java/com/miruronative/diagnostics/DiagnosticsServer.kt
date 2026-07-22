package com.miruronative.diagnostics

import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * One-file HTTP server for Android TV, where ACTION_SEND has no targets: serves the diagnostics
 * snapshot on the local network so the user can download it from a phone or computer. Started
 * when the TV share dialog opens and stopped when it closes; only reachable from the same LAN
 * and only while the dialog is up.
 */
object DiagnosticsServer {
    /** Friendly fixed port so the address is typable; falls back to an ephemeral one if taken. */
    private const val PREFERRED_PORT = 38500

    @Volatile private var server: ServerSocket? = null

    /** Starts serving [file] and returns the URL to reach it, e.g. `http://192.168.1.23:38500/`. */
    fun start(file: File): Result<String> = runCatching {
        stop()
        val host = localIpv4()
            ?: error("This TV has no local network address. Connect it to Wi-Fi or Ethernet and try again.")
        val socket = runCatching { ServerSocket(PREFERRED_PORT) }.getOrElse { ServerSocket(0) }
        server = socket
        Thread { serveLoop(socket, file) }.apply {
            name = "anilili-diagnostics-http"
            isDaemon = true
            start()
        }
        DiagnosticsLog.event("diagnostics server started on ${host.hostAddress}:${socket.localPort}")
        "http://${host.hostAddress}:${socket.localPort}/"
    }.onFailure { DiagnosticsLog.throwable("diagnostics server start failed", it) }

    fun stop() {
        server?.let { active ->
            runCatching { active.close() }
            DiagnosticsLog.event("diagnostics server stopped")
        }
        server = null
    }

    private fun serveLoop(socket: ServerSocket, file: File) {
        while (!socket.isClosed) {
            // accept() throws once stop() closes the socket — that ends the loop.
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            runCatching { client.use { respond(it, file) } }
                .onFailure { DiagnosticsLog.throwable("diagnostics server request failed", it) }
        }
    }

    private fun respond(client: Socket, file: File) {
        client.soTimeout = 10_000
        val reader = client.getInputStream().bufferedReader()
        // The request line and headers are irrelevant — every path serves the snapshot.
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) break
        }
        val bytes = file.readBytes()
        val out = client.getOutputStream()
        out.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Disposition: attachment; filename=\"Anilili-diagnostics.txt\"\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
                ).toByteArray(),
        )
        out.write(bytes)
        out.flush()
    }

    private fun localIpv4(): InetAddress? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
    }.getOrNull()
}

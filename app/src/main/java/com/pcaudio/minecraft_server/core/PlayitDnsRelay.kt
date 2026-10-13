package com.pcaudio.minecraft_server.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

class PlayitDnsRelay(
    private val upstreams: List<InetAddress>,
    private val log: (String) -> Unit
) {

    companion object {
        const val LISTEN_PORT = 5354
        const val UPSTREAM_PORT = 53
        const val UPSTREAM_TIMEOUT_MS = 3000
        const val BUFFER_SIZE = 4096
    }

    private var socket: DatagramSocket? = null

    @Volatile
    private var running = false

    fun start(): Boolean {
        if (running) {
            return true
        }
        if (upstreams.isEmpty()) {
            log("Nessun server DNS disponibile per il relay")
            return false
        }
        return try {
            val boundSocket = DatagramSocket(null)
            boundSocket.reuseAddress = true
            boundSocket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), LISTEN_PORT))
            socket = boundSocket
            running = true
            thread(isDaemon = true, name = "playit-dns-relay") {
                val buffer = ByteArray(BUFFER_SIZE)
                while (running) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        boundSocket.receive(packet)
                        val query = packet.data.copyOf(packet.length)
                        val sender = packet.socketAddress
                        thread(isDaemon = true, name = "playit-dns-query") {
                            forward(query, sender, boundSocket)
                        }
                    } catch (e: Exception) {
                        if (running) {
                            log("Errore sul relay DNS: " + e.message)
                        }
                    }
                }
            }
            log("Relay DNS attivo su 127.0.0.1:" + LISTEN_PORT + " verso " + upstreams.joinToString { it.hostAddress ?: "?" })
            true
        } catch (e: Exception) {
            log("Impossibile aprire 127.0.0.1:" + LISTEN_PORT + ": " + e.message)
            false
        }
    }

    private fun forward(query: ByteArray, sender: SocketAddress, clientSocket: DatagramSocket) {
        if (!running) {
            return
        }
        for (upstream in upstreams) {
            try {
                val upstreamSocket = DatagramSocket()
                try {
                    upstreamSocket.soTimeout = UPSTREAM_TIMEOUT_MS
                    upstreamSocket.send(DatagramPacket(query, query.size, upstream, UPSTREAM_PORT))
                    val buffer = ByteArray(BUFFER_SIZE)
                    val answer = DatagramPacket(buffer, buffer.size)
                    upstreamSocket.receive(answer)
                    if (!running) {
                        return
                    }
                    clientSocket.send(DatagramPacket(buffer, answer.length, sender))
                    return
                } finally {
                    upstreamSocket.close()
                }
            } catch (e: SocketTimeoutException) {
                log("Nessuna risposta dal DNS " + (upstream.hostAddress ?: "?"))
            } catch (e: Exception) {
                log("Errore verso il DNS " + (upstream.hostAddress ?: "?") + ": " + e.message)
            }
        }
        log("Nessun server DNS ha risposto alla query")
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (e: Exception) {
            log("Chiusura del relay DNS: " + e.message)
        }
        socket = null
    }
}
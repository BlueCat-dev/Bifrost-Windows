package com.bifrost.twp.core

import com.bifrost.twp.model.ProxyConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ultra-lightweight RFC 1928 SOCKS5 Server implemented for Android.
 * Runs on 127.0.0.1 and implements Wake-on-Demand / Zero-Idle architecture.
 *
 * During idle periods, the accept loop sleeps in OS kernel wait with 0.0% CPU overhead.
 * As soon as Telegram connects, traffic is piped via [WebSocketBridge] to Cloudflare Worker.
 */
class Socks5Server(
    private val port: Int,
    private val proxyConfigProvider: () -> ProxyConfig?,
    private val onConnectionCountChanged: (Int) -> Unit
) {
    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val activeConnections = AtomicInteger(0)

    fun start(scope: CoroutineScope) {
        if (!isRunning.compareAndSet(false, true)) return

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                // Bind exclusively to localhost for complete security
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                }

                while (isActive && isRunning.get()) {
                    // Kernel-level wait (Zero CPU usage when Telegram is idle)
                    val clientSocket = serverSocket?.accept() ?: break

                    scope.launch(Dispatchers.IO) {
                        handleClientConnection(clientSocket, scope)
                    }
                }
            } catch (_: Exception) {
                // Expected when socket is closed on stop()
            } finally {
                stop()
            }
        }
    }

    private fun handleClientConnection(socket: Socket, scope: CoroutineScope) {
        var bridge: WebSocketBridge? = null
        try {
            socket.tcpNoDelay = true
            val inStream = DataInputStream(socket.getInputStream())
            val outStream = DataOutputStream(socket.getOutputStream())

            // 1. SOCKS5 Method Negotiation
            val version = inStream.readByte().toInt() and 0xFF
            if (version != 0x05) {
                socket.close()
                return
            }

            val nMethods = inStream.readByte().toInt() and 0xFF
            val methods = ByteArray(nMethods)
            inStream.readFully(methods)

            // Reply: 0x05 (SOCKS5), 0x00 (NO AUTHENTICATION REQUIRED)
            outStream.write(byteArrayOf(0x05, 0x00))
            outStream.flush()

            // 2. SOCKS5 Connection Request
            val reqVersion = inStream.readByte().toInt() and 0xFF
            val cmd = inStream.readByte().toInt() and 0xFF
            val rsv = inStream.readByte().toInt() and 0xFF
            val atyp = inStream.readByte().toInt() and 0xFF

            if (reqVersion != 0x05 || cmd != 0x01) {
                // Command not supported or not CONNECT
                outStream.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                outStream.flush()
                socket.close()
                return
            }

            // Resolve target address (Telegram DC)
            val targetHost: String = when (atyp) {
                0x01 -> {
                    // IPv4
                    val ipBytes = ByteArray(4)
                    inStream.readFully(ipBytes)
                    InetAddress.getByAddress(ipBytes).hostAddress ?: "149.154.167.50"
                }
                0x03 -> {
                    // Domain name
                    val len = inStream.readByte().toInt() and 0xFF
                    val domainBytes = ByteArray(len)
                    inStream.readFully(domainBytes)
                    String(domainBytes)
                }
                0x04 -> {
                    // IPv6
                    val ip6Bytes = ByteArray(16)
                    inStream.readFully(ip6Bytes)
                    InetAddress.getByAddress(ip6Bytes).hostAddress ?: "::1"
                }
                else -> {
                    socket.close()
                    return
                }
            }

            val targetPort = inStream.readUnsignedShort()

            val activeConfig = proxyConfigProvider()
            if (activeConfig == null) {
                // No active proxy configured
                outStream.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                outStream.flush()
                socket.close()
                return
            }

            // 3. SOCKS5 Reply: Success
            // 0x05: SOCKS5, 0x00: Success, 0x00: Reserved, 0x01: IPv4 (127.0.0.1:port)
            val portHigh = (port ushr 8).toByte()
            val portLow = (port and 0xFF).toByte()
            outStream.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, portHigh, portLow))
            outStream.flush()

            // 4. Increment active connection count & notify
            val count = activeConnections.incrementAndGet()
            onConnectionCountChanged(count)

            // 5. Hand over to WebSocketBridge for data streaming
            bridge = WebSocketBridge(
                clientSocket = socket,
                targetIp = targetHost,
                targetPort = targetPort,
                proxyConfig = activeConfig,
                scope = scope,
                onCloseCallback = {
                    if (isRunning.get()) {
                        val remaining = activeConnections.decrementAndGet()
                        onConnectionCountChanged(maxOf(0, remaining))
                    }
                }
            )
            bridge.start()

        } catch (e: Exception) {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            try {
                serverSocket?.close()
            } catch (_: Exception) {}
            serverSocket = null
            serverJob?.cancel()
            activeConnections.set(0)
        }
    }

    fun getActiveConnections(): Int = activeConnections.get()
}

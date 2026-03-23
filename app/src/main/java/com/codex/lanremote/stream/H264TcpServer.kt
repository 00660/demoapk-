package com.codex.lanremote.stream

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class H264TcpServer(
    private val port: Int,
) {
    private val clients = CopyOnWriteArrayList<Socket>()
    private val acceptExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (serverSocket != null) {
            return
        }
        val socket = ServerSocket(port)
        socket.reuseAddress = true
        serverSocket = socket
        acceptExecutor.execute {
            try {
                while (!socket.isClosed) {
                    val client = socket.accept()
                    client.tcpNoDelay = true
                    clients += client
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        val socket = serverSocket
        serverSocket = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }

        clients.forEach { client ->
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
        clients.clear()
    }

    fun publish(bytes: ByteArray) {
        clients.forEach { client ->
            try {
                val output = client.getOutputStream()
                output.write(bytes)
                output.flush()
            } catch (_: Exception) {
                try {
                    client.close()
                } catch (_: Exception) {
                }
                clients.remove(client)
            }
        }
    }

    fun clientCount(): Int = clients.size
}

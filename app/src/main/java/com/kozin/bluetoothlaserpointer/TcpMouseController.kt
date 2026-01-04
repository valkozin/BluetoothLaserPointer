package com.kozin.bluetoothlaserpointer

import android.util.Log
import java.io.PrintWriter
import java.net.Socket
import kotlin.concurrent.thread

class TcpMouseController(
    private val host: String = "localhost",
    private val port: Int = 8080
) {
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var isConnected = false
    
    var onConnectionStateChanged: ((Boolean) -> Unit)? = null

    fun connect() {
        thread {
            try {
                socket = Socket(host, port)
                writer = PrintWriter(socket!!.getOutputStream(), true)
                isConnected = true
                onConnectionStateChanged?.invoke(true)
                Log.d("TcpMouseController", "Connected to $host:$port")
            } catch (e: Exception) {
                Log.e("TcpMouseController", "Connection failed: ${e.message}")
                isConnected = false
                onConnectionStateChanged?.invoke(false)
            }
        }
    }

    fun disconnect() {
        thread {
            try {
                writer?.close()
                socket?.close()
                isConnected = false
                onConnectionStateChanged?.invoke(false)
                Log.d("TcpMouseController", "Disconnected")
            } catch (e: Exception) {
                Log.e("TcpMouseController", "Error disconnecting: ${e.message}")
            }
        }
    }

    fun sendMovement(dx: Int, dy: Int) {
        if (!isConnected) return
        thread {
            try {
                writer?.println("$dx,$dy")
            } catch (e: Exception) {
                Log.e("TcpMouseController", "Failed to send data: ${e.message}")
                isConnected = false
                onConnectionStateChanged?.invoke(false)
            }
        }
    }

    fun sendCenterCommand() {
        if (!isConnected) return
        thread {
            try {
                writer?.println("CENTER")
            } catch (e: Exception) {
                Log.e("TcpMouseController", "Failed to send center command: ${e.message}")
            }
        }
    }

    fun sendClick(left: Boolean) {
        if (!isConnected) return
        thread {
            try {
                writer?.println(if (left) "LCLICK" else "RCLICK")
            } catch (e: Exception) {
                Log.e("TcpMouseController", "Failed to send click: ${e.message}")
            }
        }
    }
}

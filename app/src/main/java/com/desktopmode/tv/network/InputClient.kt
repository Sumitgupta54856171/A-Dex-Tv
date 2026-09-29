package com.desktopmode.tv.network

import com.desktopmode.tv.model.InputEvent
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.flow.MutableSharedFlow

class InputClient {
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    @Volatile var isRunning = false
    private val gson = Gson()

    private val _eventFlow = MutableSharedFlow<InputEvent>(extraBufferCapacity = 100)

    suspend fun startClient(ip: String) = withContext(Dispatchers.IO) {
        isRunning = true
        while (isRunning) {
            try {
                Timber.d("Connecting to input server at $ip:8766")
                val newSocket = Socket()
                newSocket.connect(InetSocketAddress(ip, 8766), 3000)
                socket = newSocket
                writer = PrintWriter(newSocket.getOutputStream(), true)
                Timber.d("Connected for input!")

                _eventFlow.collect { event ->
                    val json = gson.toJson(event)
                    writer?.println(json)
                }
            } catch (e: Exception) {
                Timber.e("Input connection error to $ip:8766 (${e.message}). Retrying in 2s...")
                try { socket?.close() } catch (e: Exception) {}
                socket = null
                writer = null
                delay(2000)
            }
        }
    }

    fun sendEvent(event: InputEvent) {
        _eventFlow.tryEmit(event)
    }

    fun stopClient() {
        isRunning = false
        try { socket?.close() } catch (e: Exception) {}
        socket = null
        writer = null
    }
}

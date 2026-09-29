package com.desktopmode.tv.network

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

class VideoClient {
    private var socket: Socket? = null
    private var inputStream: DataInputStream? = null
    private var mediaCodec: MediaCodec? = null
    private val codecLock = Any()
    @Volatile var isRunning = false

    private var configureFailureCount = 0
    private var backoffDelayMs = 2000L

    suspend fun startClient(ip: String, surface: Surface) = withContext(Dispatchers.IO) {
        isRunning = true
        cleanup()
        while (isRunning) {
            try {
                Timber.d("Connecting to video server at $ip:8765...")
                val newSocket = Socket()
                newSocket.connect(InetSocketAddress(ip, 8765), 3000)
                socket = newSocket
                inputStream = DataInputStream(newSocket.getInputStream())
                Timber.d("Connected! Starting decoder...")
                
                startDecoding(surface)
            } catch (e: Exception) {
                Timber.e("Connection or decoding error to $ip:8765: ${e.toString()}\n${e.stackTraceToString()}")
                cleanup()
                
                Timber.d("Retrying in ${backoffDelayMs / 1000}s (backoff delay)...")
                delay(backoffDelayMs)
                backoffDelayMs = (backoffDelayMs * 2).coerceAtMost(10000L)
            }
        }
    }

    private fun startDecoding(surface: Surface) {
        val stream = inputStream ?: return

        // Confirm Surface is valid before doing anything
        if (!surface.isValid) {
            Timber.e("Surface is NOT valid! Aborting decoder setup.")
            return
        }

        try {
            cleanupMediaCodec()

            // Read the initial NAL unit (SPS/PPS header sent by phone on connection)
            val firstSize = stream.readInt()
            if (firstSize <= 0 || firstSize > 5 * 1024 * 1024) {
                Timber.e("Invalid initial NAL size: $firstSize")
                return
            }

            val firstData = ByteArray(firstSize)
            stream.readFully(firstData)

            // Build MediaFormat with ONLY mime type "video/avc", width 1920, height 1080
            val format = MediaFormat.createVideoFormat("video/avc", 1920, 1080)
            
            if (isSpsPps(firstData)) {
                val csd = extractCsdFromHeader(firstData)
                if (csd != null) {
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(csd.first))
                    format.setByteBuffer("csd-1", ByteBuffer.wrap(csd.second))
                    Timber.d("Configured decoder MediaFormat with csd-0 (${csd.first.size}B) and csd-1 (${csd.second.size}B)")
                }
            }

            // Software decoder fallback logic if HW decoder fails twice in a row
            val codec: MediaCodec
            if (configureFailureCount >= 2) {
                val swName = findSoftwareDecoderName()
                if (swName != null) {
                    Timber.w("Hardware decoder failed $configureFailureCount times. Falling back to Software Decoder: $swName")
                    codec = MediaCodec.createByCodecName(swName)
                } else {
                    Timber.w("Hardware decoder failed $configureFailureCount times. Software decoder not found, trying default.")
                    codec = MediaCodec.createDecoderByType("video/avc")
                }
            } else {
                codec = MediaCodec.createDecoderByType("video/avc")
            }

            synchronized(codecLock) {
                mediaCodec = codec
            }

            try {
                if (!surface.isValid) {
                    Timber.e("Surface became invalid right before configure/start! Aborting.")
                    return
                }
                codec.configure(format, surface, null, 0)
                codec.start()
                // Reset failure count and backoff on successful configure & start
                configureFailureCount = 0
                backoffDelayMs = 2000L
                Timber.d("MediaCodec configured and started successfully!")
            } catch (e: Exception) {
                configureFailureCount++
                Timber.e("MediaCodec.configure() or start() failed (Failure #$configureFailureCount): ${e.toString()}\n${e.stackTraceToString()}")
                throw e
            }

            // If the first frame wasn't SPS/PPS, queue it into the codec now
            if (!isSpsPps(firstData)) {
                val inputIndex = codec.dequeueInputBuffer(10000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(firstData)
                    codec.queueInputBuffer(inputIndex, 0, firstSize, System.nanoTime() / 1000, 0)
                }
            }

            val info = MediaCodec.BufferInfo()

            while (isRunning) {
                val size = try {
                    stream.readInt()
                } catch (e: Exception) {
                    Timber.d("End of video stream or socket closed: ${e.toString()}")
                    break
                }

                if (size <= 0 || size > 5 * 1024 * 1024) {
                    Timber.e("Invalid NAL frame size received: $size bytes. Stream out of sync! Reconnecting...")
                    break
                }

                val data = ByteArray(size)
                stream.readFully(data)

                val currentCodec = synchronized(codecLock) { mediaCodec } ?: break

                val inputIndex = currentCodec.dequeueInputBuffer(10000)
                if (inputIndex >= 0) {
                    val inputBuffer = currentCodec.getInputBuffer(inputIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(data)
                    val flags = if (isSpsPps(data)) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                    currentCodec.queueInputBuffer(inputIndex, 0, size, System.nanoTime() / 1000, flags)
                }

                var outputIndex = currentCodec.dequeueOutputBuffer(info, 10000)
                while (outputIndex >= 0) {
                    currentCodec.releaseOutputBuffer(outputIndex, true)
                    outputIndex = currentCodec.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (e: Exception) {
            Timber.e("Decoder exception: ${e.toString()}\n${e.stackTraceToString()}")
        } finally {
            cleanupMediaCodec()
        }
    }

    private fun findSoftwareDecoderName(): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (info.isEncoder) continue
            for (type in info.supportedTypes) {
                if (type.equals("video/avc", ignoreCase = true)) {
                    val name = info.name.lowercase()
                    if (name.contains("google") || name.contains("sw")) {
                        return info.name
                    }
                }
            }
        }
        return null
    }

    private fun extractCsdFromHeader(header: ByteArray): Pair<ByteArray, ByteArray>? {
        val startCodeIndices = mutableListOf<Int>()
        var i = 0
        while (i <= header.size - 4) {
            if (header[i] == 0.toByte() && header[i + 1] == 0.toByte() && header[i + 2] == 0.toByte() && header[i + 3] == 1.toByte()) {
                startCodeIndices.add(i)
                i += 4
            } else if (header[i] == 0.toByte() && header[i + 1] == 0.toByte() && header[i + 2] == 1.toByte()) {
                startCodeIndices.add(i)
                i += 3
            } else {
                i++
            }
        }

        if (startCodeIndices.size < 2) return null

        val spsStart = startCodeIndices[0]
        val ppsStart = startCodeIndices[1]

        val sps = header.copyOfRange(spsStart, ppsStart)
        val pps = header.copyOfRange(ppsStart, header.size)

        return Pair(sps, pps)
    }

    private fun isSpsPps(data: ByteArray): Boolean {
        var i = 0
        while (i < data.size - 4) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                val type = (data[i + 4].toInt() and 0x1F)
                if (type == 7 || type == 8) return true
            } else if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                val type = (data[i + 3].toInt() and 0x1F)
                if (type == 7 || type == 8) return true
            }
            i++
        }
        return false
    }

    private fun cleanupMediaCodec() {
        synchronized(codecLock) {
            val codec = mediaCodec ?: return
            mediaCodec = null
            try {
                codec.stop()
            } catch (e: Exception) {
                Timber.w("Codec stop exception: ${e.toString()}")
            }
            try {
                codec.release()
            } catch (e: Exception) {
                Timber.w("Codec release exception: ${e.toString()}")
            }
        }
    }

    private fun cleanup() {
        cleanupMediaCodec()
        try { socket?.close() } catch (e: Exception) {}
        socket = null
        inputStream = null
    }

    fun stopClient() {
        isRunning = false
        cleanup()
    }
}

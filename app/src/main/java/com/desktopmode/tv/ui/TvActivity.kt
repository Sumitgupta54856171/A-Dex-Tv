package com.desktopmode.tv.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.desktopmode.tv.databinding.ActivityTvBinding
import com.desktopmode.tv.model.InputEvent
import com.desktopmode.tv.network.InputClient
import com.desktopmode.tv.network.NsdScanner
import com.desktopmode.tv.network.VideoClient
import com.desktopmode.tv.service.TvConnectionService
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import timber.log.Timber

class TvActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTvBinding
    private val nsdScanner: NsdScanner by inject()
    private val videoClient: VideoClient by inject()
    private val inputClient: InputClient by inject()

    private var isSurfaceReady = false
    private var clientJob: Job? = null
    private var currentConnectedIp: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Hide system UI for full screen
        hideSystemUI()

        val serviceIntent = Intent(this, TvConnectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        binding.videoSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                isSurfaceReady = true
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                isSurfaceReady = false
                videoClient.stopClient()
                inputClient.stopClient()
            }
        })

        lifecycleScope.launch {
            nsdScanner.foundHostIp.collect { ip ->
                if (ip != null && ip == currentConnectedIp && clientJob?.isActive == true) {
                    return@collect
                }
                currentConnectedIp = ip

                clientJob?.cancel()
                videoClient.stopClient()
                inputClient.stopClient()

                if (ip != null && isSurfaceReady) {
                    binding.loadingSpinner.visibility = View.GONE
                    binding.statusText.visibility = View.GONE

                    // Single dedicated job to prevent duplicate client coroutines
                    clientJob = launch {
                        launch { videoClient.startClient(ip, binding.videoSurface.holder.surface) }
                        launch { inputClient.startClient(ip) }
                    }
                } else {
                    binding.loadingSpinner.visibility = View.VISIBLE
                    binding.statusText.visibility = View.VISIBLE
                    binding.statusText.text = "Searching for A-DeX Host..."
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        nsdScanner.startScanning()
    }

    override fun onPause() {
        super.onPause()
        nsdScanner.stopScanning()
        clientJob?.cancel()
        videoClient.stopClient()
        inputClient.stopClient()
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    // Capture Keyboard Events (Single entry point on TV side)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        Timber.d("TV sending key event: keyCode=${event.keyCode}, action=${event.action}")
        inputClient.sendEvent(InputEvent(
            type = "key",
            action = event.action,
            keyCode = event.keyCode,
            metaState = event.metaState
        ))
        return true // Consume all keys to prevent local IME echo
    }

    // Capture Mouse / Trackpad Events
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_HOVER_MOVE || event.action == MotionEvent.ACTION_MOVE) {
            val scaleX = 1920f / binding.videoSurface.width
            val scaleY = 1080f / binding.videoSurface.height
            val x = event.x * scaleX
            val y = event.y * scaleY

            inputClient.sendEvent(InputEvent(
                type = "mouse_move",
                x = x,
                y = y
            ))
        } else if (event.action == MotionEvent.ACTION_SCROLL) {
            inputClient.sendEvent(InputEvent(
                type = "mouse_scroll",
                scrollX = event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            ))
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scaleX = 1920f / binding.videoSurface.width
        val scaleY = 1080f / binding.videoSurface.height
        val x = event.x * scaleX
        val y = event.y * scaleY

        inputClient.sendEvent(InputEvent(
            type = "mouse_click",
            action = event.action,
            x = x,
            y = y,
            button = event.buttonState
        ))
        return true
    }
}

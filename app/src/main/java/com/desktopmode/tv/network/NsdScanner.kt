package com.desktopmode.tv.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

class NsdScanner(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _foundHostIp = MutableStateFlow<String?>(null)
    val foundHostIp: StateFlow<String?> = _foundHostIp

    fun startScanning() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Timber.d("NSD Discovery started")
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                Timber.d("Service found: ${service.serviceName}")
                if (service.serviceName == "DesktopModePhone") {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Timber.e("Resolve failed: $errorCode")
                        }
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val ip = serviceInfo.host?.hostAddress
                            Timber.d("Resolve Succeeded. ${serviceInfo.serviceName} IP: $ip")
                            // Prefer standard LAN IP over Wi-Fi Direct (192.168.49.x)
                            if (ip != null) {
                                if (!ip.startsWith("192.168.49.") || _foundHostIp.value == null) {
                                    _foundHostIp.value = ip
                                }
                            }
                        }
                    })
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                Timber.e("Service lost: ${service.serviceName}")
                if (service.serviceName == "DesktopModePhone") {
                    _foundHostIp.value = null
                }
            }
            override fun onDiscoveryStopped(serviceType: String) {
                Timber.i("Discovery stopped: $serviceType")
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.e("Discovery failed: Error code:$errorCode")
                nsdManager.stopServiceDiscovery(this)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.e("Discovery failed: Error code:$errorCode")
                nsdManager.stopServiceDiscovery(this)
            }
        }
        nsdManager.discoverServices("_desktopmode._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stopScanning() {
        discoveryListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (e: Exception) { Timber.e(e) }
        }
    }
}

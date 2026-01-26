package com.peik.cornernas.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.peik.cornernas.util.AppLog
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque

object DeviceDiscoveryManager {
    data class DiscoveredDevice(val name: String, val host: String, val port: Int)

    private const val SERVICE_TYPE = "_cornernas._tcp."
    private const val MAX_RESOLVE_RETRIES = 3
    private const val RESOLVE_RETRY_DELAY_MS = 500L
    private const val PING_TIMEOUT_MS = 1500
    private val logTag = "CornerNASDiscovery"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<(List<DiscoveredDevice>) -> Unit>()
    private val discovered = linkedMapOf<String, DiscoveredDevice>()
    private val serviceNameToKey = linkedMapOf<String, String>()
    private val resolveRetries = linkedMapOf<String, Int>()
    private val pendingQueue = ArrayDeque<ResolveRequest>()
    private val pendingNames = linkedMapOf<String, Long>()
    private var resolving = false
    private var localHost: String? = null
    private var localName: String? = null
    private var localPort: Int? = null
    private var localEnabled = false

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun addListener(context: Context, listener: (List<DiscoveredDevice>) -> Unit) {
        val appContext = context.applicationContext
        if (listeners.add(listener) && listeners.size == 1) {
            startDiscovery(appContext)
        }
        mainHandler.post { listener(currentList()) }
    }

    fun removeListener(listener: (List<DiscoveredDevice>) -> Unit) {
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            stopDiscovery()
        }
    }

    fun setLocalDevice(enabled: Boolean, name: String?, host: String?, port: Int?) {
        localEnabled = enabled
        localName = name
        localHost = host
        localPort = port
        if (!enabled || host.isNullOrBlank() || port == null || port <= 0 || name.isNullOrBlank()) {
            host?.let { removeDevice(name ?: "", it) }
            return
        }
        upsertDevice(name, host, port)
    }

    private fun startDiscovery(context: Context) {
        if (discoveryListener != null) return
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("cornernas_discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                AppLog.i(logTag, "Discovery started type=$regType")
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType == SERVICE_TYPE) {
                    AppLog.i(logTag, "Service found name=${serviceInfo.serviceName}")
                    mainHandler.post { enqueueResolve(serviceInfo) }
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                AppLog.w(logTag, "Service lost name=${serviceInfo.serviceName}")
                mainHandler.post {
                    val key = serviceNameToKey.remove(serviceInfo.serviceName)
                    if (key != null && discovered[key]?.name == serviceInfo.serviceName) {
                        discovered.remove(key)
                    }
                    pendingNames.remove(serviceInfo.serviceName)
                    notifyListeners()
                }
            }
            override fun onDiscoveryStopped(serviceType: String) {
                AppLog.i(logTag, "Discovery stopped type=$serviceType")
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.w(logTag, "Discovery start failed type=$serviceType code=$errorCode")
                stopDiscovery()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.w(logTag, "Discovery stop failed type=$serviceType code=$errorCode")
                stopDiscovery()
            }
        }
        discoveryListener = listener
        nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun stopDiscovery() {
        val manager = nsdManager
        val listener = discoveryListener
        discoveryListener = null
        if (manager != null && listener != null) {
            try {
                manager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                AppLog.w(logTag, "Stop discovery failed", e)
            }
        }
        multicastLock?.release()
        multicastLock = null
        nsdManager = null
        pendingQueue.clear()
        pendingNames.clear()
        resolveRetries.clear()
        serviceNameToKey.clear()
        discovered.clear()
        resolving = false
        notifyListeners()
        AppLog.i(logTag, "Discovery stopped and lock released")
    }

    private fun enqueueResolve(serviceInfo: NsdServiceInfo) {
        val name = serviceInfo.serviceName
        val token = SystemClock.elapsedRealtime()
        pendingNames[name] = token
        pendingQueue.add(ResolveRequest(serviceInfo, token))
        resolveNext()
    }

    private fun resolveNext() {
        if (resolving) return
        val next = if (pendingQueue.isEmpty()) null else pendingQueue.removeFirst()
        if (next == null) return
        resolving = true
        resolveService(next)
    }

    private fun resolveService(request: ResolveRequest) {
        val manager = nsdManager ?: return
        val resolveInfo = NsdServiceInfo().apply {
            serviceName = request.info.serviceName
            serviceType = request.info.serviceType
        }
        manager.resolveService(resolveInfo, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                val latestToken = pendingNames[info.serviceName]
                if (latestToken != null && latestToken != request.token) {
                    resolving = false
                    resolveNext()
                    return
                }
                val host = info.host?.hostAddress
                if (host.isNullOrBlank()) {
                    AppLog.w(logTag, "Resolve returned empty host name=${info.serviceName}")
                    pendingNames.remove(info.serviceName)
                    resolving = false
                    resolveNext()
                    return
                }
                pendingNames.remove(info.serviceName)
                resolveRetries.remove(info.serviceName)
                resolving = false
                resolveNext()
                pingDevice(host, info.port) { alive ->
                    if (!alive) {
                        AppLog.w(logTag, "Ping failed name=${info.serviceName} host=$host port=${info.port}")
                        mainHandler.post { removeDevice(info.serviceName, host) }
                        return@pingDevice
                    }
                    AppLog.i(logTag, "Service resolved name=${info.serviceName} host=$host port=${info.port}")
                    mainHandler.post { upsertDevice(info.serviceName, host, info.port) }
                }
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                AppLog.w(logTag, "Resolve failed name=${info.serviceName} code=$errorCode")
                val attempts = (resolveRetries[info.serviceName] ?: 0) + 1
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    resolving = false
                    pendingQueue.addFirst(request)
                    mainHandler.postDelayed({ resolveNext() }, RESOLVE_RETRY_DELAY_MS)
                    return
                }
                if (attempts <= MAX_RESOLVE_RETRIES) {
                    resolveRetries[info.serviceName] = attempts
                    val delay = RESOLVE_RETRY_DELAY_MS * attempts
                    mainHandler.postDelayed({
                        resolving = false
                        resolveService(request)
                    }, delay)
                    AppLog.w(logTag, "Retry resolve name=${info.serviceName} attempt=$attempts")
                    return
                }
                pendingNames.remove(info.serviceName)
                resolving = false
                resolveNext()
            }
        })
    }

    private fun pingDevice(host: String, port: Int, callback: (Boolean) -> Unit) {
        Thread {
            val url = "http://$host:$port/api/v1/ping"
            val ok = try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = PING_TIMEOUT_MS
                conn.readTimeout = PING_TIMEOUT_MS
                conn.responseCode in 200..399
            } catch (_: Exception) {
                false
            }
            callback(ok)
        }.start()
    }

    private fun upsertDevice(name: String, host: String, port: Int) {
        val key = host
        val device = DiscoveredDevice(name, host, port)
        val previousKey = serviceNameToKey[name]
        if (previousKey != null && previousKey != key) {
            discovered.remove(previousKey)
        }
        val existingName = serviceNameToKey.entries.firstOrNull {
            it.value == key && it.key != name
        }?.key
        if (existingName != null) {
            serviceNameToKey.remove(existingName)
        }
        discovered[key] = device
        serviceNameToKey[name] = key
        notifyListeners()
    }

    private fun removeDevice(name: String, host: String) {
        if (localEnabled && localHost == host) {
            return
        }
        val key = serviceNameToKey[name]
        if (key != null) {
            discovered.remove(key)
            serviceNameToKey.remove(name)
        } else if (discovered[host]?.name == name) {
            discovered.remove(host)
        }
        notifyListeners()
    }

    private fun notifyListeners() {
        val snapshot = currentList()
        mainHandler.post {
            listeners.forEach { it(snapshot) }
        }
    }

    private fun currentList(): List<DiscoveredDevice> {
        return discovered.values.sortedBy { it.name.lowercase() }
    }

    private data class ResolveRequest(
        val info: NsdServiceInfo,
        val token: Long
    )
}

package com.peik.cornernas.util

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object NetworkUtils {
    fun getLocalIpAddress(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            interfaces.toList().asSequence().flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { addr ->
                    !addr.isLoopbackAddress && addr.isSiteLocalAddress
                }?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    fun isLocalLanHost(host: String): Boolean {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.equals("localhost", ignoreCase = true)) return true
        return try {
            val address = InetAddress.getByName(trimmed)
            address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                address.isSiteLocalAddress
        } catch (e: Exception) {
            false
        }
    }
}

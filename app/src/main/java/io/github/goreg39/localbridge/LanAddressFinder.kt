package io.github.goreg39.localbridge

import java.net.Inet4Address
import java.net.NetworkInterface

object LanAddressFinder {
    fun findBestIpv4(): String? {
        val candidates = mutableListOf<Candidate>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null

        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!runCatching { networkInterface.isUp }.getOrDefault(false)) continue
            if (runCatching { networkInterface.isLoopback }.getOrDefault(false)) continue

            val name = networkInterface.name.lowercase()
            val addresses = networkInterface.inetAddresses

            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address !is Inet4Address) continue
                if (address.isLoopbackAddress || address.isLinkLocalAddress) continue

                val score = score(name, address)
                if (score >= 0) {
                    candidates += Candidate(
                        address = address.hostAddress ?: continue,
                        score = score,
                    )
                }
            }
        }

        return candidates.maxByOrNull { it.score }?.address
    }

    private fun score(interfaceName: String, address: Inet4Address): Int {
        var score = 0

        if (
            interfaceName.startsWith("wlan") ||
            interfaceName.startsWith("swlan") ||
            interfaceName.startsWith("ap") ||
            interfaceName.startsWith("wifi")
        ) {
            score += 100
        }

        if (address.isSiteLocalAddress) {
            score += 50
        }

        if (
            interfaceName.startsWith("tun") ||
            interfaceName.startsWith("wg") ||
            interfaceName.startsWith("ppp") ||
            interfaceName.startsWith("rmnet") ||
            interfaceName.startsWith("ccmni") ||
            interfaceName.startsWith("dummy")
        ) {
            score -= 200
        }

        return score
    }

    private data class Candidate(
        val address: String,
        val score: Int,
    )
}

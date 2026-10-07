package com.bifrost.twp.model

import android.net.Uri

/**
 * Parser and generator for the standard RFC 3986 twp:// proxy link standard.
 *
 * Standard Format:
 *   twp://[secret@]workerHost[:port]?clean_ip=cleanIp#ConfigName
 *
 * Examples:
 *   twp://my-worker.example.workers.dev?clean_ip=104.16.1.1#Worker1
 *   twp://mysecret@my-worker.example.workers.dev?clean_ip=104.16.1.1#Worker1
 *   twp://mysecret@my-worker.example.workers.dev:8443?clean_ip=104.16.1.1#Worker1
 */
object TwpLinkParser {

    /**
     * Generates a standard RFC 3986 twp:// link matching industry proxy standards (like VLESS, Trojan, SS).
     */
    fun generateLink(config: ProxyConfig): String {
        val host = config.workerHost.trim()
        val builder = StringBuilder("twp://")

        // 1. Userinfo authentication (secret) if present
        if (!config.secret.isNullOrBlank()) {
            builder.append(Uri.encode(config.secret.trim())).append("@")
        }

        // 2. Server authority / host
        builder.append(host)

        // 3. Port if not standard HTTPS 443
        if (config.port != 443 && config.port > 0) {
            builder.append(":").append(config.port)
        }

        // 4. Query parameters (e.g. clean_ip)
        val queryParts = mutableListOf<String>()

        if (!config.cleanIp.isNullOrBlank()) {
            queryParts.add("clean_ip=${Uri.encode(config.cleanIp.trim())}")
        }

        if (queryParts.isNotEmpty()) {
            builder.append("?").append(queryParts.joinToString("&"))
        }

        // 5. Fragment label (Proxy Name)
        if (config.name.isNotBlank() && config.name != host) {
            builder.append("#").append(Uri.encode(config.name.trim()))
        }

        return builder.toString()
    }

    /**
     * Universal, resilient parser that extracts configuration from standard twp:// links,
     * as well as query-based and legacy link formats.
     */
    fun parseLink(rawInput: String?): ProxyConfig? {
        if (rawInput.isNullOrBlank()) return null
        val trimmed = rawInput.trim()

        try {
            val uri = Uri.parse(trimmed)
            val scheme = uri.scheme?.lowercase() ?: ""

            // Accept twp, tg, https, or scheme-less hostnames
            if (scheme.isNotEmpty() && scheme != "twp" && scheme != "tg" && scheme != "https" && scheme != "http") {
                return null
            }

            // Safe query parameter extractor (works on both hierarchical and opaque URIs)
            val getParam: (String) -> String? = { key ->
                try {
                    if (uri.isHierarchical) uri.getQueryParameter(key) else null
                } catch (_: Exception) {
                    null
                } ?: run {
                    val queryIndex = trimmed.indexOf('?')
                    if (queryIndex != -1) {
                        val queryString = trimmed.substring(queryIndex + 1).split('#')[0]
                        queryString.split('&')
                            .map { it.split('=') }
                            .firstOrNull { it.size == 2 && it[0].equals(key, ignoreCase = true) }
                            ?.get(1)?.let { Uri.decode(it) }
                    } else null
                }
            }

            var workerHost: String? = null
            var secret: String? = null
            var port: Int = 443

            // 1. Check UserInfo for secret (twp://secret@host:port)
            val rawUserInfo = try { uri.userInfo } catch (_: Exception) { null }
            if (!rawUserInfo.isNullOrBlank()) {
                secret = Uri.decode(rawUserInfo)
            }

            // Also check query for secret if not in userinfo
            if (secret.isNullOrBlank()) {
                secret = getParam("secret") ?: getParam("token")
            }

            // 2. Resolve Worker Host from URI authority/host
            val uriHost = try { uri.host } catch (_: Exception) { null }
            if (!uriHost.isNullOrBlank() && uriHost != "proxy" && uriHost != "worker" && uriHost != "twp" && !uriHost.contains("bifrost")) {
                workerHost = uriHost
            } else {
                // Query parameter fallback (for legacy twp://proxy?server=... format)
                workerHost = getParam("server")
                    ?: getParam("host")
                    ?: getParam("worker")
            }

            // 3. Fallback for custom schemes where android.net.Uri doesn't populate host correctly
            if (workerHost.isNullOrBlank() && (scheme == "twp" || scheme.isEmpty())) {
                val ssp = if (scheme.isNotEmpty()) uri.schemeSpecificPart.trimStart('/') else trimmed
                val atIdx = ssp.indexOf('@')
                val candidatePart = if (atIdx != -1) {
                    if (secret.isNullOrBlank()) {
                        secret = Uri.decode(ssp.substring(0, atIdx))
                    }
                    ssp.substring(atIdx + 1)
                } else ssp

                val qIdx = candidatePart.indexOf('?')
                val fIdx = candidatePart.indexOf('#')
                val endIdx = when {
                    qIdx >= 0 && fIdx >= 0 -> minOf(qIdx, fIdx)
                    qIdx >= 0 -> qIdx
                    fIdx >= 0 -> fIdx
                    else -> candidatePart.length
                }
                val candidate = candidatePart.substring(0, endIdx).trim()
                if (candidate.isNotBlank() && candidate != "proxy") {
                    val colonIdx = candidate.indexOf(':')
                    if (colonIdx != -1) {
                        workerHost = candidate.substring(0, colonIdx)
                        candidate.substring(colonIdx + 1).toIntOrNull()?.let { port = it }
                    } else {
                        workerHost = candidate
                    }
                }
            }

            if (workerHost.isNullOrBlank()) return null

            // 4. Resolve Port
            if (uri.port > 0) {
                port = uri.port
            } else {
                getParam("port")?.toIntOrNull()?.let { port = it }
            }

            // 5. Resolve Clean IP
            val cleanIp = getParam("clean_ip")
                ?: getParam("cleanip")
                ?: getParam("clean-ip")
                ?: getParam("ip")
                ?: getParam("cdn_ip")

            // 6. Resolve Name from Fragment (#Name) or query (?name=Name)
            val rawFragment = try { uri.fragment } catch (_: Exception) { null }
                ?: if (trimmed.contains('#')) trimmed.substringAfter('#') else null
            val rawName = rawFragment?.takeIf { it.isNotBlank() }
                ?: getParam("name")
                ?: workerHost

            val name = Uri.decode(rawName)

            return ProxyConfig(
                name = name,
                workerHost = workerHost.trim(),
                cleanIp = cleanIp?.trim()?.takeIf { it.isNotBlank() },
                secret = secret?.trim()?.takeIf { it.isNotBlank() },
                port = if (port in 1..65535) port else 443
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}

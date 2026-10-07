package com.bifrost.twp.model

import org.json.JSONObject
import java.util.UUID

/**
 * Represents a Cloudflare Worker Proxy configuration.
 *
 * @property id Unique identifier.
 * @property name Human-readable label.
 * @property workerHost Mandatory Cloudflare Worker domain/hostname (e.g. my-proxy.workers.dev).
 * @property cleanIp Optional Cloudflare Clean IP for bypassing domain SNI blocks.
 * @property secret Optional authentication token for the worker.
 * @property port Worker HTTPS/WSS port (default 443).
 * @property isActive Flag indicating if this proxy is currently selected for the local SOCKS5 bridge.
 * @property createdAt Timestamp when this config was created.
 */
data class ProxyConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val workerHost: String,
    val cleanIp: String? = null,
    val secret: String? = null,
    val port: Int = 443,
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("workerHost", workerHost)
            put("cleanIp", cleanIp ?: "")
            put("secret", secret ?: "")
            put("port", port)
            put("isActive", isActive)
            put("createdAt", createdAt)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): ProxyConfig {
            val cleanIpRaw = json.optString("cleanIp", "")
            val secretRaw = json.optString("secret", "")
            return ProxyConfig(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", "Worker Proxy"),
                workerHost = json.optString("workerHost", ""),
                cleanIp = if (cleanIpRaw.isNotBlank()) cleanIpRaw else null,
                secret = if (secretRaw.isNotBlank()) secretRaw else null,
                port = json.optInt("port", 443),
                isActive = json.optBoolean("isActive", false),
                createdAt = json.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }
}

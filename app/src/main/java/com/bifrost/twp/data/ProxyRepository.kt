package com.bifrost.twp.data

import android.content.Context
import android.content.SharedPreferences
import com.bifrost.twp.model.ProxyConfig
import com.bifrost.twp.model.TwpLinkParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Repository for managing proxy configurations and bridge settings.
 * Persists data locally using SharedPreferences with immediate StateFlow updates.
 */
class ProxyRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _proxiesFlow = MutableStateFlow<List<ProxyConfig>>(emptyList())
    val proxiesFlow: StateFlow<List<ProxyConfig>> = _proxiesFlow.asStateFlow()

    private val _activeProxyFlow = MutableStateFlow<ProxyConfig?>(null)
    val activeProxyFlow: StateFlow<ProxyConfig?> = _activeProxyFlow.asStateFlow()

    private val _localPortFlow = MutableStateFlow(DEFAULT_LOCAL_PORT)
    val localPortFlow: StateFlow<Int> = _localPortFlow.asStateFlow()

    private val _isBridgeEnabledFlow = MutableStateFlow(false)
    val isBridgeEnabledFlow: StateFlow<Boolean> = _isBridgeEnabledFlow.asStateFlow()

    private val _runOnStartupFlow = MutableStateFlow(false)
    val runOnStartupFlow: StateFlow<Boolean> = _runOnStartupFlow.asStateFlow()

    private val _appLanguageFlow = MutableStateFlow("en")
    val appLanguageFlow: StateFlow<String> = _appLanguageFlow.asStateFlow()

    private val _hasSelectedLanguageFlow = MutableStateFlow(false)
    val hasSelectedLanguageFlow: StateFlow<Boolean> = _hasSelectedLanguageFlow.asStateFlow()

    init {
        loadData()
    }

    @Synchronized
    private fun loadData() {
        val port = prefs.getInt(KEY_LOCAL_PORT, DEFAULT_LOCAL_PORT)
        _localPortFlow.value = port

        val bridgeEnabled = prefs.getBoolean(KEY_BRIDGE_ENABLED, false)
        _isBridgeEnabledFlow.value = bridgeEnabled

        val runOnStartup = prefs.getBoolean(KEY_RUN_ON_STARTUP, false)
        _runOnStartupFlow.value = runOnStartup

        val lang = prefs.getString(KEY_APP_LANGUAGE, "en") ?: "en"
        _appLanguageFlow.value = lang

        val hasSelected = prefs.getBoolean(KEY_HAS_SELECTED_LANGUAGE, false)
        _hasSelectedLanguageFlow.value = hasSelected

        val rawJson = prefs.getString(KEY_PROXIES, null)
        val list = mutableListOf<ProxyConfig>()

        if (!rawJson.isNullOrBlank()) {
            try {
                val jsonArray = JSONArray(rawJson)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    list.add(ProxyConfig.fromJson(obj))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Determine active proxy
        val activeId = prefs.getString(KEY_ACTIVE_ID, null)
        val active = list.firstOrNull { it.id == activeId } ?: list.firstOrNull { it.isActive } ?: list.firstOrNull()

        // Update list with accurate isActive flags
        val updatedList = list.map { it.copy(isActive = it.id == active?.id) }
        _proxiesFlow.value = updatedList
        _activeProxyFlow.value = active
    }

    @Synchronized
    private fun persistList(list: List<ProxyConfig>) {
        val jsonArray = JSONArray()
        list.forEach { jsonArray.put(it.toJson()) }
        prefs.edit().putString(KEY_PROXIES, jsonArray.toString()).apply()
        _proxiesFlow.value = list
    }

    @Synchronized
    fun addOrUpdateProxy(config: ProxyConfig, makeActive: Boolean = false) {
        val current = _proxiesFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == config.id }

        val shouldBeActive = makeActive || config.isActive || current.isEmpty()

        if (index >= 0) {
            current[index] = config
        } else {
            current.add(0, config) // Add new ones to top
        }

        if (shouldBeActive) {
            setActiveProxyInternal(current, config.id)
        } else {
            persistList(current)
        }
    }

    @Synchronized
    fun setActiveProxy(proxyId: String) {
        val current = _proxiesFlow.value.toMutableList()
        setActiveProxyInternal(current, proxyId)
    }

    private fun setActiveProxyInternal(list: MutableList<ProxyConfig>, proxyId: String) {
        val updated = list.map { it.copy(isActive = (it.id == proxyId)) }
        val active = updated.firstOrNull { it.id == proxyId }

        prefs.edit()
            .putString(KEY_ACTIVE_ID, active?.id)
            .apply()

        _activeProxyFlow.value = active
        persistList(updated)
    }

    @Synchronized
    fun deleteProxy(proxyId: String) {
        val current = _proxiesFlow.value.toMutableList()
        val wasActive = current.firstOrNull { it.id == proxyId }?.isActive == true
        current.removeAll { it.id == proxyId }

        if (wasActive) {
            val newActive = current.firstOrNull()
            if (newActive != null) {
                setActiveProxyInternal(current, newActive.id)
                return
            } else {
                prefs.edit().remove(KEY_ACTIVE_ID).apply()
                _activeProxyFlow.value = null
            }
        }

        persistList(current)
    }

    @Synchronized
    fun setLocalPort(port: Int) {
        val validPort = if (port in 1024..65535) port else DEFAULT_LOCAL_PORT
        prefs.edit().putInt(KEY_LOCAL_PORT, validPort).apply()
        _localPortFlow.value = validPort
    }

    @Synchronized
    fun setBridgeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BRIDGE_ENABLED, enabled).commit()
        _isBridgeEnabledFlow.value = enabled
    }

    @Synchronized
    fun setRunOnStartup(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RUN_ON_STARTUP, enabled).commit()
        _runOnStartupFlow.value = enabled
    }

    @Synchronized
    fun setAppLanguage(lang: String) {
        prefs.edit().putString(KEY_APP_LANGUAGE, lang).apply()
        _appLanguageFlow.value = lang
    }

    @Synchronized
    fun confirmInitialLanguage(lang: String) {
        prefs.edit()
            .putString(KEY_APP_LANGUAGE, lang)
            .putBoolean(KEY_HAS_SELECTED_LANGUAGE, true)
            .apply()
        _appLanguageFlow.value = lang
        _hasSelectedLanguageFlow.value = true
    }

    /**
     * Imports a proxy from a twp:// or tg:// link.
     */
    fun importFromLink(link: String, makeActive: Boolean = true): ProxyConfig? {
        val parsed = TwpLinkParser.parseLink(link) ?: return null
        addOrUpdateProxy(parsed, makeActive = makeActive)
        return parsed
    }

    companion object {
        private const val PREFS_NAME = "bifrost_preferences"
        private const val KEY_PROXIES = "key_proxies_json"
        private const val KEY_ACTIVE_ID = "key_active_proxy_id"
        private const val KEY_LOCAL_PORT = "key_local_socks5_port"
        private const val KEY_BRIDGE_ENABLED = "key_bridge_enabled"
        private const val KEY_RUN_ON_STARTUP = "key_run_on_startup"
        private const val KEY_APP_LANGUAGE = "key_app_language"
        private const val KEY_HAS_SELECTED_LANGUAGE = "key_has_selected_language"
        const val DEFAULT_LOCAL_PORT = 5050

        @Volatile
        private var INSTANCE: ProxyRepository? = null

        fun getInstance(context: Context): ProxyRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProxyRepository(context).also { INSTANCE = it }
            }
        }
    }
}

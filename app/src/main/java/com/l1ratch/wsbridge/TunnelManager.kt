package com.l1ratch.wsbridge

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.l1ratch.wsbridge.tunnel.EventLog
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/// Хранит конфиг (worker, DNS) и статус туннеля. Порт TunnelManager.swift.
///
/// ponytail: на Android сервис и UI в одном процессе — никакого IPC:
/// статус берётся из флага, который ставит TunnelService, журнал —
/// напрямую из EventLog. SharedPreferences вместо UserDefaults, JSON
/// для своих DNS-конфигов (stdlib org.json, без kotlinx-serialization).
object TunnelManager {

    data class DNSConfig(
        val id: String,
        val name: String,
        val description: String,
        val servers: List<String>,      // IP-адреса: цель DoT/DoH и plain-фолбэк
        val dohURL: String? = null,     // DNS-over-HTTPS endpoint
        val dotHostname: String? = null, // DNS-over-TLS SNI-хост
        val site: String? = null,       // офсайт провайдера (у пресетов)
        val isPreset: Boolean,
    ) {
        /// Какие транспорты конфиг умеет.
        val supportsDoT: Boolean get() = !dotHostname.isNullOrBlank()
        val supportsDoH: Boolean get() = !dohURL.isNullOrBlank()

        /// Короткая метка транспортов для списков.
        val transportsLabel: String
            get() = when {
                servers.isEmpty() -> "как в системе"
                supportsDoT && supportsDoH -> "DoT · DoH"
                supportsDoT -> "DoT"
                supportsDoH -> "DoH"
                else -> "без шифрования"
            }
    }

    /// Транспорт шифрованного DNS. По умолчанию DoT: коннект идёт напрямую
    /// к IP:853 с SNI и НЕ требует резолва имени сервера. У DoH этот резолв
    /// обязателен (имя хоста в URL), и он делается системой уже под туннелем —
    /// эта ступень и ломала DoH, когда резолвер недоступен.
    enum class DNSMode(val raw: String, val shortTitle: String, val hint: String) {
        DOT("dot", "DoT",
            "Порт 853. Прямой коннект к IP сервера — без резолва имени под туннелем."),
        DOH("doh", "DoH",
            "Порт 443. Маскируется под обычный HTTPS, но требует резолва имени сервера.");

        companion object {
            fun of(raw: String?): DNSMode = entries.firstOrNull { it.raw == raw } ?: DOT
        }
    }

    /// Пресеты: не удаляются и не редактируются. Подписи статичные и короткие:
    /// базовые резолверы — «без фильтрации», остальные — обход гео-блокировок.
    /// IP-адреса вычищены вручную: мёртвые из офсписков не тащим (см. комментарии).
    val dnsPresets = listOf(
        DNSConfig("system", "Системный", "DNS устройства, туннель его не трогает",
            emptyList(), null, null, null, true),
        DNSConfig("cloudflare", "Cloudflare", "Базовый, без фильтрации",
            listOf("1.1.1.1", "1.0.0.1"),
            "https://cloudflare-dns.com/dns-query", "one.one.one.one",
            "https://one.one.one.one/", true),
        DNSConfig("google", "Google", "Базовый, без фильтрации",
            listOf("8.8.8.8", "8.8.4.4"),
            "https://dns.google/dns-query", "dns.google",
            "https://developers.google.com/speed/public-dns", true),
        DNSConfig("comss", "Comss.one", "Для обхода гео-блокировок",
            listOf("83.220.169.155", "212.109.195.93"),
            "https://dns.comss.one/dns-query", "dns.comss.one",
            "https://www.comss.ru/page.php?id=7315", true),
        DNSConfig("malw", "Malw.link", "Для обхода гео-блокировок",
            listOf("179.254.166.171", "164.5.224.81"),
            "https://dns.malw.link/dns-query", "dns.malw.link",
            "https://info.dns.malw.link/", true),
        DNSConfig("geohide", "GeoHide", "Для обхода гео-блокировок",
            // 159.194.200.33 из офсписка мёртв (53/853/443 закрыты) — не тащим.
            listOf("193.233.112.68", "193.233.112.67", "193.233.112.88", "37.230.192.51"),
            "https://geohide.ru/dns-query", "geohide.ru",
            "https://geohide.ru/", true),
        DNSConfig("xbox", "XBox DNS", "Для обхода гео-блокировок",
            // .55/853 живой, .54 — нет; .56/.57 — то, куда резолвится офхост.
            listOf("111.88.96.55", "111.88.96.54"),
            "https://xbox-dns.ru/dns-query", "xbox-dns.ru",
            "https://xbox-dns.ru/#faq", true),
        DNSConfig("malw-gateway", "Alt Malw.link", "Для обхода гео-блокировок",
            listOf("162.159.36.5", "162.159.36.20"),
            "https://5u35p8m9i7.cloudflare-gateway.com/dns-query",
            "5u35p8m9i7.cloudflare-gateway.com",
            "https://info.dns.malw.link/", true),
    )

    /// Лимит описания у своих конфигов: подписи короткие, иначе разъезжаются.
    const val DESCRIPTION_LIMIT = 60

    private lateinit var prefs: SharedPreferences

    /// Статус туннеля: mutableStateOf — Compose перечитывает сам, без IPC и слушателей.
    var running by mutableStateOf(false)
        private set

    /// История переходов статуса — диагностика «мёртвой кнопки» (порт
    /// statusHistory из iOS build 85). На iOS терялись переходы
    /// NEVPNStatusDidChange и кнопка «выключить» мертвела; здесь тот же класс
    /// проблем виден в журнале: видно, сбрасывался ли флаг вообще.
    /// Ограничена 40 записями, чтобы не расти бесконечно за долгую сессию.
    private val transitions = ArrayDeque<String>()
    private val transitionFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun init(context: Context) {
        if (::prefs.isInitialized) return   // WP-2: идемпотентность — повторный вызов безопасен
        prefs = context.applicationContext.getSharedPreferences("wsbridge", Context.MODE_PRIVATE)
    }

    /// Единственная воронка смены статуса: все переходы идут через неё
    /// (сервис, сторож, отзыв VPN), поэтому здесь же пишется история.
    fun updateRunning(value: Boolean) {
        val changed = running != value
        running = value
        if (!changed) return
        // Синхронизация: зовётся из wsb-start, wsb-tun и main-потока.
        synchronized(transitions) {
            transitions.addLast("${transitionFmt.format(Date())} -> ${if (value) "on" else "off"}")
            if (transitions.size > 40) transitions.removeFirst()
        }
    }

    /// История переходов, свежие внизу. Пусто = переходов ещё не было.
    fun statusHistory(): List<String> = synchronized(transitions) { transitions.toList() }

    /// Домен собственного CF worker'а пользователя (pipe-режим). пустой = kws-каскад.
    /// «host:port» = прямое реле на VPS. Несколько доменов — через запятую
    /// (балансировка round-robin на каждое соединение, как в iOS build 90).
    var workerDomain: String
        get() = prefs.getString("workerDomain", "") ?: ""
        set(v) = prefs.edit().putString("workerDomain", v).apply()

    /// «DC → IP» маппинг (как в десктопном tg-ws-proxy и iOS build 90):
    /// строки «номер:IP» по одной на строку. Пусто = как прилетело от клиента.
    /// Работает в pipe-режиме: worker'у диалится указанный IP вместо
    /// канонического адреса DC.
    var dcMapText: String
        get() = prefs.getString("dcMap", "") ?: ""
        set(v) = prefs.edit().putString("dcMap", v).apply()

    /// Выбранный транспорт шифрованного DNS (по умолчанию DoT).
    var dnsModeRaw: String
        get() = prefs.getString("dnsMode", DNSMode.DOT.raw) ?: DNSMode.DOT.raw
        set(v) = prefs.edit().putString("dnsMode", v).apply()

    val dnsMode: DNSMode get() = DNSMode.of(dnsModeRaw)

    /// Фактический транспорт для выбранного конфига: если предпочитаемый
    /// недоступен — берём второй, если и его нет — null (plain UDP:53).
    val effectiveDNSMode: DNSMode?
        get() {
            val sel = selectedDNS
            return when (dnsMode) {
                DNSMode.DOT -> when {
                    sel.supportsDoT -> DNSMode.DOT
                    sel.supportsDoH -> DNSMode.DOH
                    else -> null
                }
                DNSMode.DOH -> when {
                    sel.supportsDoH -> DNSMode.DOH
                    sel.supportsDoT -> DNSMode.DOT
                    else -> null
                }
            }
        }

    var customDNS: List<DNSConfig>
        get() {
            val json = prefs.getString("customDNS", null) ?: return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    DNSConfig(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        description = o.optString("description"),
                        servers = o.getJSONArray("servers").let { s -> (0 until s.length()).map { s.getString(it) } },
                        dohURL = o.optString("dohURL").takeIf { it.isNotEmpty() },
                        dotHostname = o.optString("dotHostname").takeIf { it.isNotEmpty() },
                        site = null,
                        isPreset = false,
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        set(configs) {
            val arr = JSONArray()
            for (c in configs) {
                arr.put(JSONObject().apply {
                    put("id", c.id); put("name", c.name); put("description", c.description)
                    put("servers", JSONArray(c.servers))
                    if (c.dohURL != null) put("dohURL", c.dohURL)
                    if (c.dotHostname != null) put("dotHostname", c.dotHostname)
                })
            }
            prefs.edit().putString("customDNS", arr.toString()).apply()
        }

    var selectedDNSId: String
        get() = prefs.getString("selectedDNSId", "comss") ?: "comss"
        set(v) = prefs.edit().putString("selectedDNSId", v).apply()

    val allDNS: List<DNSConfig> get() = dnsPresets + customDNS

    val selectedDNS: DNSConfig get() = allDNS.firstOrNull { it.id == selectedDNSId } ?: dnsPresets[0]

    /// Журнал туннеля — напрямую из EventLog (один процесс).
    /// В конец дописывается история переходов статуса: в «мёртвой кнопке»
    /// первое, что нужно понять, — сбрасывался ли флаг вообще. Порт того,
    /// что iOS показывает в «Статистике» под журналом.
    fun journal(): String {
        val log = EventLog.journal().ifEmpty { "журнал пуст — туннель ещё не запускался" }
        val hist = statusHistory()
        if (hist.isEmpty()) return log
        return log + "\n\n— история статуса —\n" + hist.joinToString("\n")
    }
}
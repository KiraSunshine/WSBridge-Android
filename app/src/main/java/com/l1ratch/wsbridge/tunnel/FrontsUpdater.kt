package com.l1ratch.wsbridge.tunnel

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/// Hot-обновление списка CF-фронтов: качает свежий список апстрима,
/// валидирует и сохраняет в prefs. Применение — при следующем старте
/// туннеля (TunnelService читает prefs и вызывает CFDomains.update).
///
/// Три URL обязательны: GitHub raw в РФ плавает, jsDelivr — рабочие
/// зеркала того же файла.
///
/// ❌ Не качать из самого VPN-сервиса при старте — сетевой сбой
/// заблокирует старт туннеля. Только из UI, до/после сервиса.
object FrontsUpdater {

    private val urls = listOf(
        "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/master/.github/cfproxy-domains.txt",
        "https://cdn.jsdelivr.net/gh/Flowseal/tg-ws-proxy@master/.github/cfproxy-domains.txt",
        "https://fastly.jsdelivr.net/gh/Flowseal/tg-ws-proxy@master/.github/cfproxy-domains.txt",
    )

    /// Валидация строки-домена: буквы/цифры/дефисы/точки, без протокола
    /// и пути. Битый файл не должен попадать в каскад.
    private val domainRegex = Regex("^[a-z0-9][a-z0-9.-]*$")

    private const val MIN_DOMAINS = 3

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("fronts", Context.MODE_PRIVATE)
        // Скачанный список применяется сразу при загрузке — один процесс,
        // сервис и UI видят одни и те же данные.
        val saved = load()
        if (saved.isNotEmpty()) CFDomains.update(saved)
    }

    /// Скачивает и сохраняет. true = список обновлён.
    /// Резолвит домены ДО сохранения: файл апстрима бывает протухшим (все
    /// 20 свежих доменов — NXDOMAIN, проверено 03.10), и мёртвые фронты
    /// в каскаде жрут 5с таймаута каждый на первой же сессии. Мёртвые
    /// отбрасываются, живые — мерджатся.
    suspend fun update(): Boolean = withContext(Dispatchers.IO) {
        for (u in urls) {
            val text = try {
                fetch(u)
            } catch (_: Exception) {
                continue
            } ?: continue
            val domains = text.lines()
                .map { it.trim().lowercase() }
                .filter { it.matches(domainRegex) }
                .distinct()
            if (domains.size < MIN_DOMAINS) continue // битый файл
            val alive = domains.filter { resolves(it) }
            if (alive.size < MIN_DOMAINS) continue // все мертвы — файл протух, игнор
            prefs.edit().putString("fronts", alive.joinToString("\n")).apply()
            prefs.edit().putLong("frontsUpdatedAt", System.currentTimeMillis()).apply()
            CFDomains.update(alive)
            return@withContext true
        }
        false
    }

    /// Жив ли домен: DNS-резолв. NXDOMAIN = мёртв (бросает исключение).
    /// Мы уже на Dispatchers.IO — блокирующий вызов уместен. 20 доменов
    /// × мгновенный NXDOMAIN = пренебрежимо.
    private fun resolves(domain: String): Boolean = try {
        InetAddress.getByName(domain) != null
    } catch (_: Exception) {
        false
    }

    /// Rate-limit автообновления: не чаще раза в 30 минут.
    fun shouldAutoUpdate(): Boolean {
        val last = prefs.getLong("frontsUpdatedAt", 0L)
        return System.currentTimeMillis() - last > 30 * 60 * 1000
    }

    fun lastUpdated(): Long = prefs.getLong("frontsUpdatedAt", 0L)

    private fun load(): List<String> =
        (prefs.getString("fronts", null) ?: return emptyList())
            .lines().filter { it.isNotBlank() }

    /// Простой HTTP GET с таймаутами: без OkHttp-зависимости в UI-слое —
    /// HttpURLConnection из stdlib достаточно для одного текстового файла.
    private fun fetch(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

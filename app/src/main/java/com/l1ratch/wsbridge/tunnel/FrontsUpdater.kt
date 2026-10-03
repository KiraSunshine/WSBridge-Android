package com.l1ratch.wsbridge.tunnel

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/// Реестр CF-фронтов с памятью здоровья + hot-обновление апстрима.
///
/// Что помнит реестр (переживает рестарт приложения):
///  - каждый фронт: число подряд фейлов, время последнего фейла и успеха;
///  - успехи/фейлы пишет сам каскад (WSClient.markAlive/markDead) —
///    runtime-наблюдения становятся долгосрочной памятью.
///
/// Как обновляется список (обновление качает только при изменениях):
///  - новички апстрима проверяются резолвом и добавляются только живыми;
///  - известные ЖИВЫЕ фронты не перетестируются вовсе;
///  - известные мёртвые из апстрима перепроверяются (DNS ожил → шанс);
///  - фронт, мёртвый > 7 дней и пропавший из апстрима, удаляется;
///  - живой фронт, отсутствующий в апстриме, ОСТАЁТСЯ (мердж, не замена).
///
/// Вход в каскад: fails < 3 или успех за последние 24ч. Точечную фильтрацию
/// во время сессии по-прежнему делает runtime-кэш WSClient (10 мин).
///
/// Три URL обязательны: GitHub raw в РФ плавает, jsDelivr — зеркала.
/// ❌ Не качать из самого VPN-сервиса при старте — сетевой сбой заблокирует
/// старт туннеля. Только из UI.
object FrontsUpdater {

    private val urls = listOf(
        "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/master/.github/cfproxy-domains.txt",
        "https://cdn.jsdelivr.net/gh/Flowseal/tg-ws-proxy@master/.github/cfproxy-domains.txt",
        "https://fastly.jsdelivr.net/gh/Flowseal/tg-ws-proxy@master/.github/cfproxy-domains.txt",
    )

    /// Валидация строки-домена: без протокола и пути. Битый файл — игнор.
    private val domainRegex = Regex("^[a-z0-9][a-z0-9.-]*$")

    /// Первая метка фронта: kws{dc}.base или kws{dc}-1.base.
    private val frontLabel = Regex("kws\\d+(-1)?")

    private const val MIN_DOMAINS = 3

    /// После стольких подряд фейлов фронт исключается из каскада.
    private const val ENTRY_FAIL_LIMIT = 3

    /// Недавний успех возвращает право входа в каскад.
    private const val REVIVE_WINDOW_MS = 24 * 3600_000L

    /// Мёртвый и пропавший из апстрима фронт удаляется из реестра.
    private const val EVICT_AFTER_MS = 7 * 24 * 3600_000L

    /// Персист реестра на диск не чаще этого интервала (фейлы — сразу).
    private const val FLUSH_INTERVAL_MS = 15_000L

    private class Stat(var fails: Int, var lastFail: Long, var lastOk: Long)

    private lateinit var prefs: SharedPreferences
    private val db = HashMap<String, Stat>()
    private var dirty = false
    private var lastFlush = 0L

    /// Последний применённый к CFDomains набор экстра-фронтов:
    /// «меняем только при изменении» — тот же набор не трогает ни каскад, ни prefs.
    private var lastAppliedExtras: List<String>? = null

    fun init(context: Context) {
        val first = !::prefs.isInitialized
        if (first) {
            prefs = context.applicationContext.getSharedPreferences("fronts", Context.MODE_PRIVATE)
        } else {
            persist() // повторный вызов (рестарт туннеля): слить память на диск перед перечитыванием
        }
        loadDb()
        if (first) migrateOldList()
        applyAlive()
        if (first) persist()
    }

    /// Скачивает апстрим и мерджит в реестр. true = список скачан.
    /// DNS-проверки — только для новичков и мёртвых: живые не перетестируются.
    suspend fun update(): Boolean = withContext(Dispatchers.IO) {
        val text = download() ?: return@withContext false
        val upstream = text.lines()
            .map { it.trim().lowercase() }
            .filter { it.matches(domainRegex) }
            .distinct()
        if (upstream.size < MIN_DOMAINS) return@withContext false // битый файл

        val now = System.currentTimeMillis()
        // Кого проверять — считаем под блокировкой, сам DNS (медленный) — вне её.
        val unknown = synchronized(db) { upstream.filter { it !in db } }
        val fresh = unknown.filter { resolves(it) }
        val deadInUpstream = synchronized(db) {
            db.entries
                .filter { it.key in upstream && it.value.fails >= ENTRY_FAIL_LIMIT && it.value.lastOk < now - REVIVE_WINDOW_MS }
                .map { it.key }
        }
        val revived = deadInUpstream.filter { resolves(it) }

        synchronized(db) {
            // Новички: добавляются только резолвящиеся. Не-резолвящиеся не
            // добавляются вовсе — их перепроверит следующий апдейт.
            for (d in fresh) if (!db.containsKey(d)) db[d] = Stat(0, 0, now)
            // Ожившие: fails=1 — входит в каскад; если DNS соврал, runtime-кэш
            // и счётчик фейлов быстро выведут его обратно.
            for (d in revived) db[d]?.let { it.fails = 1 }
            // Чистка: мёртв > 7 дней И пропал из апстрима — вон из реестра.
            val evict = db.entries.filter { (d, st) ->
                d !in upstream && d !in CFDomains.builtinBases &&
                    (st.lastOk == 0L || st.lastOk < now - EVICT_AFTER_MS)
            }
            for (e in evict) db.remove(e.key)
        }
        dirty = true
        applyAlive()
        prefs.edit().putLong("frontsUpdatedAt", now).apply()
        persist()
        true
    }

    /// Успех фронта в каскаде (host вида kws2.pclead.co.uk). Пишет WSClient.
    /// Чужие ключи (прямые IP, kws*.web.telegram.org) игнорируются — их в реестре нет.
    fun recordSuccess(frontHost: String) {
        val base = baseOf(frontHost) ?: return
        synchronized(db) {
            val st = db[base] ?: return
            st.fails = 0
            st.lastOk = System.currentTimeMillis()
        }
        dirty = true
        flushThrottled()
    }

    /// Фейл фронта в каскаде. Пишется на диск сразу — это самая ценная память.
    fun recordFailure(frontHost: String) {
        val base = baseOf(frontHost) ?: return
        synchronized(db) {
            val st = db[base] ?: return
            st.fails += 1
            st.lastFail = System.currentTimeMillis()
        }
        dirty = true
        flushThrottled(force = true)
    }

    /// Слить реестр на диск (вызывается при остановке туннеля и после апдейта).
    fun persist() = flushThrottled(force = true)

    /// Rate-limit автообновления: не чаще раза в 30 минут.
    fun shouldAutoUpdate(): Boolean {
        val last = prefs.getLong("frontsUpdatedAt", 0L)
        return System.currentTimeMillis() - last > 30 * 60 * 1000
    }

    // --- внутреннее ---

    /// Жив ли домен: DNS-резолв. Вызывается только для новичков и мёртвых.
    private fun resolves(domain: String): Boolean = try {
        InetAddress.getByName(domain) != null
    } catch (_: Exception) {
        false
    }

    /// Экстра-фронт = реестр минус мёртвые. Применяется к CFDomains только
    /// при изменении набора; порядок — свежие успехи первыми.
    /// Синхронизирован целиком: вызывается из main (init) и IO (update).
    @Synchronized
    private fun applyAlive() {
        val now = System.currentTimeMillis()
        val extras = synchronized(db) {
            db.entries
                .filter { it.value.fails < ENTRY_FAIL_LIMIT || it.value.lastOk > now - REVIVE_WINDOW_MS }
                .sortedByDescending { it.value.lastOk }
                .map { it.key }
        }
        if (extras != lastAppliedExtras) {
            CFDomains.update(extras)
            lastAppliedExtras = extras
        }
    }

    private fun baseOf(front: String): String? {
        val i = front.indexOf('.')
        if (i <= 0) return null
        if (!front.substring(0, i).matches(frontLabel)) return null
        return front.substring(i + 1)
    }

    private fun loadDb() {
        synchronized(db) {
            db.clear()
            val json = prefs.getString("frontsDb", null) ?: return
            val arr = try { JSONArray(json) } catch (_: Exception) { return }
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.getJSONObject(i)
                    db[o.getString("d")] = Stat(o.getInt("f"), o.getLong("lf"), o.getLong("lo"))
                } catch (_: Exception) { /* битая запись — пропускаем */ }
            }
        }
    }

    /// Миграция со старого формата (простой список «fronts», все считаются живыми).
    private fun migrateOldList() {
        val old = prefs.getString("fronts", null) ?: return
        val now = System.currentTimeMillis()
        synchronized(db) {
            for (d in old.lines().map { it.trim() }.filter { it.isNotBlank() }) {
                if (!db.containsKey(d)) db[d] = Stat(0, 0, now)
            }
        }
        dirty = true
        prefs.edit().remove("fronts").apply()
    }

    @Synchronized
    private fun flushThrottled(force: Boolean = false) {
        if (!dirty) return
        if (!force && System.currentTimeMillis() - lastFlush < FLUSH_INTERVAL_MS) return
        lastFlush = System.currentTimeMillis()
        val arr = JSONArray()
        synchronized(db) {
            for ((d, st) in db) {
                arr.put(JSONObject().apply {
                    put("d", d); put("f", st.fails); put("lf", st.lastFail); put("lo", st.lastOk)
                })
            }
        }
        prefs.edit().putString("frontsDb", arr.toString()).apply()
        dirty = false
    }

    /// Простой HTTP GET с таймаутами: stdlib, без OkHttp-зависимости в UI-слое.
    private fun download(): String? {
        for (u in urls) {
            try {
                val text = fetch(u)
                if (text != null) return text
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

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

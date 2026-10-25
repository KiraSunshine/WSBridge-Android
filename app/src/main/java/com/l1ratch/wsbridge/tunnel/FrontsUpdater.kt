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

    /// WP-4: единая блокировка на всё изменяемое состояние реестра.
    /// Раньше `db` защищался `synchronized(db)`, а `dirty`/`lastFlush`/
    /// `lastAppliedExtras`/`prefs` — вообще ничем, при том что вход есть из
    /// двух мест сразу: UI (update из корутины) и старт сервиса
    /// (TunnelService.startTunnel → FrontsUpdater.init). Два независимых
    /// лока не давали атомарности между «пометил грязным» и «слил на диск»:
    /// `dirty = true`, пришедший между проверкой и сбросом флага в
    /// flushThrottled, терялся — реестр не сохранялся, и выученные фейлы
    /// фронтов пропадали при следующем рестарте.
    ///
    /// Монитор один и он же reentrant, поэтому вложенные захваты
    /// (init → persist → flushThrottled) безопасны.
    /// ВАЖНО: `resolves()` (DNS) и `fetch()` (HTTP) — только ВНЕ блокировки.
    /// Резолв идёт секундами; держа лок на нём, мы останавливали бы запись
    /// успехов/фейлов из каскада на всё время сетевых операций.
    private val lock = Any()

    private val db = HashMap<String, Stat>()
    private var dirty = false
    private var lastFlush = 0L

    /// Последний применённый к CFDomains набор экстра-фронтов:
    /// «меняем только при изменении» — тот же набор не трогает ни каскад, ни prefs.
    private var lastAppliedExtras: List<String>? = null

    fun init(context: Context) = synchronized(lock) {
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

    /// Итог обновления списка фронтов.
    /// Различаем «сеть жива, но файл негодный» и «сеть/источники недоступны»:
    /// это разные проблемы и чинятся они по-разному. Раньше на оба случая
    /// писалось одно «не удалось», и тестер не понимал, что происходит.
    sealed interface Result {
        /// Список скачан, провалидирован и смерджен в реестр.
        data object OK : Result

        /// Источник ОТВЕТИЛ, но данные негодные (битый/протухший файл).
        /// Прежний список не тронут — каскад продолжает работать на нём.
        data class Stale(val reason: String) : Result

        /// Ни один источник не ответил: нет сети или они недоступны.
        data object Offline : Result
    }

    /// Скачивает апстрим и мерджит в реестр.
    /// Перебираем ИСТОЧНИКИ, а не только URL: битый или протухший файл на
    /// одном зеркале не должен блокировать попытку на следующем. Иначе
    /// «протух» с GitHub raw означал бы отказ целиком, хотя jsDelivr мог
    /// отдать годный список — именно так и терялся рабочий путь.
    suspend fun update(): Result = withContext(Dispatchers.IO) {
        // Причина отказа ПРИ ОТВЕТЕ источника: сеть жива, но файл негодный.
        // Если причина не набралась ни на одном источнике — никто не ответил.
        var rejection: String? = null
        for (u in urls) {
            val raw = try {
                fetch(u)
            } catch (_: Exception) {
                continue // следующий источник
            } ?: continue
            val upstream = raw.lines()
                .map { it.trim().lowercase() }
                .filter { it.matches(domainRegex) }
                .distinct()
            if (upstream.size < MIN_DOMAINS) {
                rejection = "битый файл (${upstream.size} записей)"
                continue
            }
            val stale = mergeAlive(upstream)
            if (stale != null) {
                rejection = stale
                continue
            }
            return@withContext Result.OK
        }
        rejection?.let { Result.Stale(it) } ?: Result.Offline
    }

    /// Вливает живые домены апстрима в реестр и персистит.
    /// `null` = список принят, иначе причина «негодный файл».
    /// DNS-проверки идут вне блокировки реестра: резолв медленный, а держать
    /// на нём `db` — значит останавливать запись успехов/фейлов из каскада.
    private fun mergeAlive(upstream: List<String>): String? {
        val now = System.currentTimeMillis()
        val unknown = synchronized(lock) { upstream.filter { it !in db } }
        val fresh = unknown.filter { resolves(it) }
        val deadInUpstream = synchronized(lock) {
            db.entries
                .filter { it.key in upstream && it.value.fails >= ENTRY_FAIL_LIMIT && it.value.lastOk < now - REVIVE_WINDOW_MS }
                .map { it.key }
        }
        val revived = deadInUpstream.filter { resolves(it) }

        // Уже известное живо, а все новички не резолвятся — файл протух
        // целиком (NXDOMAIN-волны апстрима). Реестр не трогаем, пробуем
        // следующее зеркало.
        if (fresh.isEmpty() && revived.isEmpty() && unknown.isNotEmpty()) {
            return "протух: живых 0 из ${upstream.size}"
        }

        synchronized(lock) {
            // Новички: добавляются только резолвящиеся. Не-резолвящиеся не
            // попадают в реестр вовсе — их перепроверит следующий апдейт.
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
            dirty = true
        }
        applyAlive()
        prefs.edit().putLong("frontsUpdatedAt", now).apply()
        persist()
        return null
    }

    /// Успех фронта в каскаде (host вида kws2.pclead.co.uk). Пишет WSClient.
    /// Чужие ключи (прямые IP, kws*.web.telegram.org) игнорируются — их в реестре нет.
    fun recordSuccess(frontHost: String) {
        val base = baseOf(frontHost) ?: return
        synchronized(lock) {
            val st = db[base] ?: return
            st.fails = 0
            st.lastOk = System.currentTimeMillis()
            dirty = true
        }
        flushThrottled()
    }

    /// Фейл фронта в каскаде. Пишется на диск сразу — это самая ценная память.
    fun recordFailure(frontHost: String) {
        val base = baseOf(frontHost) ?: return
        synchronized(lock) {
            val st = db[base] ?: return
            st.fails += 1
            st.lastFail = System.currentTimeMillis()
            dirty = true
        }
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
    private fun applyAlive() {
        val now = System.currentTimeMillis()
        val extras = synchronized(lock) {
            db.entries
                .filter { it.value.fails < ENTRY_FAIL_LIMIT || it.value.lastOk > now - REVIVE_WINDOW_MS }
                .sortedByDescending { it.value.lastOk }
                .map { it.key }
        }
        synchronized(lock) {
            if (extras != lastAppliedExtras) {
                CFDomains.update(extras)
                lastAppliedExtras = extras
            }
        }
    }

    private fun baseOf(front: String): String? {
        val i = front.indexOf('.')
        if (i <= 0) return null
        if (!front.substring(0, i).matches(frontLabel)) return null
        return front.substring(i + 1)
    }

    private fun loadDb() {
        synchronized(lock) {
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
        synchronized(lock) {
            for (d in old.lines().map { it.trim() }.filter { it.isNotBlank() }) {
                if (!db.containsKey(d)) db[d] = Stat(0, 0, now)
            }
            dirty = true
        }
        prefs.edit().remove("fronts").apply()
    }

    private fun flushThrottled(force: Boolean = false) {
        synchronized(lock) {
            if (!dirty) return
            if (!force && System.currentTimeMillis() - lastFlush < FLUSH_INTERVAL_MS) return
            lastFlush = System.currentTimeMillis()
            val arr = JSONArray()
            for ((d, st) in db) {
                arr.put(JSONObject().apply {
                    put("d", d); put("f", st.fails); put("lf", st.lastFail); put("lo", st.lastOk)
                })
            }
            prefs.edit().putString("frontsDb", arr.toString()).apply()
            // Сброс флага — под тем же локом, что и запись: иначе dirty = true
            // из recordFailure между снимком и сбросом терялся, и свежие фейлы
            // не попадали на диск вовсе.
            dirty = false
        }
    }

    /// Простой HTTP GET с таймаутами: stdlib, без OkHttp-зависимости в UI-слое.
    /// Вызывается по каждому источнику по очереди — перебор в update().
    ///
    /// WP-4: заголовки обхода кэша. README обещает «качаем свежий список», а
    /// HttpURLConnection по умолчанию уважает кэш (и промежуточные прокси тоже):
    /// после правки апстрима прилетал бы старый файл, и обновление фронтов
    /// молча не давало бы эффекта. useCaches = false душит кэш самого
    /// соединения, Cache-Control/Pragma — кэши по пути.
    private fun fetch(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            conn.setRequestProperty("Cache-Control", "no-cache, no-store")
            conn.setRequestProperty("Pragma", "no-cache")
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

package com.l1ratch.wsbridge.tunnel

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/// Глобальная статистика по дням: трафик, аптайм, число включений.
/// Порт TunnelManager.usageHistory (iOS build 91). Ключевое отличие: там
/// расширение и приложение — разные процессы, и снапшот тянулся через IPC;
/// здесь один процесс, и сервис пишет в стор напрямую.
///
/// Событийная модель (как на iOS — таймеров ради статистики нет):
///  - noteSessionStart: включение — счётчик сессий, чистый лист для дельт;
///  - snapshot: капнуть дельту байтов и секунд (зовёт io-тикер, который и
///    так тикает каждые 15с, и остановка перед разборкой — финальная дельта
///    не теряется, это фикс iOS build 92);
///  - noteSessionEnd: выключение.
///
/// Персист — SharedPreferences, но ЛОГИКА от Android не зависит: attach()
/// вызывается снаружи (Application), в юнит-тестах стор живёт в памяти.
/// Потокобезопасно: зовётся с lwip-тикера, сервисных потоков и UI.
object StatsStore {

    /// Один день. up = ОТ клиента (загрузка), down = К клиенту (скачивание),
    /// seconds — время в подключённом состоянии, sessions — сколько раз
    /// включали. Сырые единицы; форматирование — на экране.
    data class DayUsage(
        var upBytes: Long = 0L,
        var downBytes: Long = 0L,
        var seconds: Long = 0L,
        var sessions: Int = 0,
    )

    private const val PREFS = "wsbridge_stats"
    private const val KEY_DAYS = "days"

    /// Храним 90 дней — как на iOS; «All» на графике покажет что есть.
    private const val KEEP_DAYS = 90

    private val lock = Any()

    /// День («yyyy-MM-dd») → данные.
    private val days = HashMap<String, DayUsage>()

    /// Счётчики момента последнего снапшота. -1 = базы нет (сессия ещё не
    /// началась или счётчики перезапустились) — первый snapshot только
    /// фиксирует базу, ничего не начисляя.
    private var lastFromClient = -1L
    private var lastToClient = -1L

    /// Когда туннель стал включён в этот раз (epoch ms); 0 = выключен.
    private var connectedSince = 0L

    private var prefs: SharedPreferences? = null
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /// Подключает персист. Вызывается один раз из Application — раньше любого
    /// читателя (UI) и писателя (сервис). Повторный вызов — no-op:
    /// повторная загрузка затёрла бы несохранённую дельту.
    fun attach(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            loadLocked()
        }
    }

    /// Включение: счётчик сессий, чистый лист для дельт.
    /// `now` — параметр ради тестируемости без часов.
    fun noteSessionStart(now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            connectedSince = now
            lastFromClient = -1L
            lastToClient = -1L
            mutateDayLocked(dayKey(now)) { it.sessions++ }
        }
    }

    /// Снапшот: дельта байтов с последнего снапшота + накопленный аптайм.
    /// fromClient = EventLog.rxBytes (клиент → гейтвей), toClient =
    /// EventLog.upBytes (гейтвей → клиент). Перезапуск счётчиков (меньше
    /// базы) = база заново, отрицательной дельты не бывает. Перевал через
    /// полночь делит секунды пропорционально (как на iOS).
    fun snapshot(
        fromClient: Long,
        toClient: Long,
        now: Long = System.currentTimeMillis(),
    ) {
        synchronized(lock) {
            if (connectedSince != 0L && now > connectedSince) {
                capUptimeLocked(connectedSince, now)
                connectedSince = now
            }
            if (lastFromClient >= 0 && fromClient >= lastFromClient && toClient >= lastToClient) {
                val dUp = fromClient - lastFromClient   // от клиента = загрузка
                val dDown = toClient - lastToClient     // к клиенту = скачивание
                if (dUp > 0 || dDown > 0) {
                    mutateDayLocked(dayKey(now)) { d ->
                        d.upBytes += dUp
                        d.downBytes += dDown
                    }
                }
            }
            lastFromClient = fromClient
            lastToClient = toClient
        }
    }

    /// Выключение: хвост аптайма (байты должен успеть снять snapshot ДО
    /// разборки — счётчики EventLog обнулятся вместе с журналом).
    fun noteSessionEnd(now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            if (connectedSince != 0L && now > connectedSince) {
                capUptimeLocked(connectedSince, now)
            }
            connectedSince = 0L
            lastFromClient = -1L
            lastToClient = -1L
        }
    }

    /// Снимок истории для UI: отсортировано по дате, свежий — последним.
    fun history(): List<Pair<String, DayUsage>> = synchronized(lock) {
        days.entries.sortedBy { it.key }.map { it.key to it.value.copy() }
    }

    /// Только для тестов: полный сброс состояния (в проде не звать — сотрёт
    /// накопленное). internal: видно из того же модуля, где живут тесты.
    internal fun resetForTest() {
        synchronized(lock) {
            days.clear()
            lastFromClient = -1L
            lastToClient = -1L
            connectedSince = 0L
        }
    }

    /// Суммарные значения за последние `daysBack` дней (0 = только сегодня).
    fun totals(daysBack: Int): DayUsage = synchronized(lock) {
        val cutoff = dayKey(System.currentTimeMillis() - daysBack * 86_400_000L)
        val acc = DayUsage()
        for ((k, d) in days) {
            if (k < cutoff) continue
            acc.upBytes += d.upBytes
            acc.downBytes += d.downBytes
            acc.seconds += d.seconds
            acc.sessions += d.sessions
        }
        acc
    }

    // ── внутреннее ─────────────────────────────────────────────────────

    /// Аптайм с разрезом по полуночи: секунды до полуночи — во вчерашний
    /// слот, остаток — в сегодняшний.
    private fun capUptimeLocked(since: Long, now: Long) {
        val seconds = (now - since) / 1000L
        if (seconds <= 0) return
        val key = dayKey(now)
        val startKey = dayKey(since)
        if (key == startKey) {
            mutateDayLocked(key) { it.seconds += seconds }
            return
        }
        val midnight = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val beforeMidnight = (midnight - since) / 1000L
        if (beforeMidnight > 0) {
            mutateDayLocked(startKey) { it.seconds += beforeMidnight }
            mutateDayLocked(key) { it.seconds += maxOf(seconds - beforeMidnight, 0L) }
        } else {
            mutateDayLocked(key) { it.seconds += seconds }
        }
    }

    private fun dayKey(at: Long): String = synchronized(dayFmt) { dayFmt.format(Date(at)) }

    private fun mutateDayLocked(key: String, body: (DayUsage) -> Unit) {
        val d = days.getOrPut(key) { DayUsage() }
        body(d)
        persistLocked()
    }

    private fun loadLocked() {
        days.clear()
        val p = prefs ?: return
        val json = p.getString(KEY_DAYS, null) ?: return
        try {
            val root = JSONObject(json)
            val it = root.keys()
            while (it.hasNext()) {
                val k = it.next()
                val o = root.getJSONObject(k)
                days[k] = DayUsage(
                    o.getLong("up"), o.getLong("down"),
                    o.getLong("sec"), o.getInt("ses"),
                )
            }
        } catch (_: Exception) {
            days.clear() // битый персист — начинаем с чистого листа
        }
        rotateLocked()
    }

    private fun persistLocked() {
        rotateLocked()
        val p = prefs ?: return // не подключён (тест) — только память
        val root = JSONObject()
        for ((k, d) in days) {
            root.put(k, JSONObject()
                .put("up", d.upBytes).put("down", d.downBytes)
                .put("sec", d.seconds).put("ses", d.sessions))
        }
        p.edit().putString(KEY_DAYS, root.toString()).apply()
    }

    /// Ротация: старше 90 дней — вон.
    private fun rotateLocked() {
        if (days.isEmpty()) return
        val cutoff = dayKey(System.currentTimeMillis() - KEEP_DAYS * 86_400_000L)
        days.entries.removeAll { it.key < cutoff }
    }
}
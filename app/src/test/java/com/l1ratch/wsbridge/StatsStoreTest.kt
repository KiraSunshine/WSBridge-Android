package com.l1ratch.wsbridge

import com.l1ratch.wsbridge.tunnel.StatsStore
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/// Статистика по дням — чистая логика без Android (attach не зовём, стор
/// живёт в памяти). Ошибка здесь молча искажает историю трафика: юзер видит
/// «вчера 0 байт» и не может понять, куда ушёл гигабайт. Поэтому каждое
/// правило начисления проверено явными числами.
class StatsStoreTest {

    @Before
    fun reset() = StatsStore.resetForTest()

    /// Фиксированная метка времени: тесты не должны зависеть от того,
    /// в какое время суток их запустили (полночь — часть поведения).
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long =
        Calendar.getInstance().apply {
            set(y, mo, d, h, mi, s)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun keyOf(t: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun usage(t: Long): StatsStore.DayUsage =
        StatsStore.history().first { it.first == keyOf(t) }.second

    // ── сессии ────────────────────────────────────────────────────────

    @Test
    fun `sessions counter increments per start`() {
        val t = at(2026, Calendar.OCTOBER, 24, 12, 0, 0)
        StatsStore.noteSessionStart(t)
        StatsStore.noteSessionEnd(t + 60_000)
        StatsStore.noteSessionStart(t + 120_000)

        assertEquals(2, usage(t).sessions)
    }

    // ── байты ─────────────────────────────────────────────────────────

    @Test
    fun `byte deltas accumulate between snapshots`() {
        val t = at(2026, Calendar.OCTOBER, 24, 12, 0, 0)
        StatsStore.noteSessionStart(t)
        // Первый снапшот только фиксирует базу (lastFrom = -1 до старта).
        StatsStore.snapshot(100, 50, t + 15_000)
        assertEquals(0L, usage(t).upBytes)
        assertEquals(0L, usage(t).downBytes)
        // Второй капает дельту: up = от клиента, down = к клиенту.
        StatsStore.snapshot(300, 200, t + 30_000)
        assertEquals(200L, usage(t).upBytes)
        assertEquals(150L, usage(t).downBytes)
        // Третий — ещё дельту поверх.
        StatsStore.snapshot(400, 260, t + 45_000)
        assertEquals(300L, usage(t).upBytes)
        assertEquals(210L, usage(t).downBytes)
    }

    @Test
    fun `counter restart does not go negative and rebases`() {
        val t = at(2026, Calendar.OCTOBER, 24, 12, 0, 0)
        StatsStore.noteSessionStart(t)
        StatsStore.snapshot(1000, 500, t + 15_000)
        // Счётчики перезапустились (меньше базы): отрицательной дельты нет.
        StatsStore.snapshot(10, 5, t + 30_000)
        assertEquals(0L, usage(t).upBytes)
        assertEquals(0L, usage(t).downBytes)
        // Новая база уже зафиксирована — дальше дельты считаются от неё.
        StatsStore.snapshot(70, 60, t + 45_000)
        assertEquals(60L, usage(t).upBytes)
        assertEquals(55L, usage(t).downBytes)
    }

    @Test
    fun `new session clears the baseline`() {
        val t = at(2026, Calendar.OCTOBER, 24, 12, 0, 0)
        StatsStore.noteSessionStart(t)
        StatsStore.snapshot(500, 300, t + 15_000)
        StatsStore.noteSessionEnd(t + 60_000)
        // Новая сессия — чистый лист: её первый снапшот ничего не начисляет,
        // даже если счётчики глобально «выросли» с прошлой сессии.
        StatsStore.noteSessionStart(t + 120_000)
        StatsStore.snapshot(900, 800, t + 135_000)
        assertEquals(0L, usage(t).upBytes)
        assertEquals(0L, usage(t).downBytes)
        StatsStore.snapshot(1000, 900, t + 150_000)
        assertEquals(100L, usage(t).upBytes)
        assertEquals(100L, usage(t).downBytes)
    }

    // ── аптайм ────────────────────────────────────────────────────────

    @Test
    fun `uptime accrues between snapshots`() {
        val t = at(2026, Calendar.OCTOBER, 24, 12, 0, 0)
        StatsStore.noteSessionStart(t)
        StatsStore.snapshot(0, 0, t + 30_000)
        assertEquals(30L, usage(t).seconds)
        StatsStore.snapshot(0, 0, t + 45_000)
        assertEquals(45L, usage(t).seconds)
    }

    @Test
    fun `midnight split divides seconds across days`() {
        // 23:58:00 → 00:00:30: 120 секунд до полуночи вчерашнему, 30 — сегодняшнему.
        val since = at(2026, Calendar.OCTOBER, 24, 23, 58, 0)
        StatsStore.noteSessionStart(since)
        StatsStore.snapshot(0, 0, since + 150_000)

        assertEquals(120L, usage(since).seconds)
        val nextDay = at(2026, Calendar.OCTOBER, 25, 0, 0, 0)
        assertEquals(30L, usage(nextDay).seconds)
    }

    // ── история и окно ────────────────────────────────────────────────

    @Test
    fun `history is sorted by date`() {
        val t1 = at(2026, Calendar.OCTOBER, 20, 10, 0, 0)
        val t2 = at(2026, Calendar.OCTOBER, 24, 10, 0, 0)
        StatsStore.noteSessionStart(t1)
        StatsStore.snapshot(0, 0, t1 + 1000)
        StatsStore.noteSessionEnd(t1 + 2000)
        StatsStore.noteSessionStart(t2)
        StatsStore.snapshot(0, 0, t2 + 1000)

        val keys = StatsStore.history().map { it.first }
        assertEquals(keys, keys.sorted())
    }

    @Test
    fun `totals sums only the requested window`() {
        val old = System.currentTimeMillis() - 40L * 86_400_000L
        val now = System.currentTimeMillis()
        StatsStore.noteSessionStart(old)
        StatsStore.snapshot(100, 100, old + 15_000)
        StatsStore.snapshot(200, 200, old + 30_000)
        StatsStore.noteSessionEnd(old + 60_000)
        StatsStore.noteSessionStart(now)
        StatsStore.snapshot(50, 50, now + 15_000)
        StatsStore.snapshot(150, 150, now + 30_000)
        StatsStore.noteSessionEnd(now + 60_000)

        val week = StatsStore.totals(30)
        assertEquals(100L, week.upBytes)  // только сегодня (день = его дельта)
        assertEquals(100L, week.downBytes)
        assertEquals(1, week.sessions)

        val all = StatsStore.totals(90)
        assertEquals(200L, all.upBytes)  // оба дня по своей дельте
        assertEquals(2, all.sessions)
    }

    @Test
    fun `rotation drops days older than 90`() {
        // День возрастом 89 дней — переживает ротацию.
        val old = System.currentTimeMillis() - 89L * 86_400_000L
        StatsStore.noteSessionStart(old)
        StatsStore.snapshot(100, 100, old + 15_000)
        StatsStore.snapshot(200, 200, old + 30_000)
        StatsStore.noteSessionEnd(old + 60_000)
        assertEquals(1, StatsStore.history().size)
        assertEquals(100L, StatsStore.totals(90).upBytes)

        // День возрастом 91 день — отбрасывается уже при записи: ротация
        // идёт на каждом персисте, данные старше 90 дней не задерживаются вовсе.
        StatsStore.resetForTest()
        val ancient = System.currentTimeMillis() - 91L * 86_400_000L
        StatsStore.noteSessionStart(ancient)
        StatsStore.snapshot(100, 100, ancient + 15_000)
        StatsStore.snapshot(200, 200, ancient + 30_000)
        StatsStore.noteSessionEnd(ancient + 60_000)
        assertEquals(0, StatsStore.history().size)
    }
}
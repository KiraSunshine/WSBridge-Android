package com.l1ratch.wsbridge.tunnel

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/// Журнал событий туннеля. На Android сервис и UI живут в ОДНОМ процессе,
/// поэтому весь IPC-слой iOS (Darwin notifications, JournalServer на
/// loopback TCP, JournalReader, персист в файл контейнера) не нужен:
/// приложение читает журнал напрямую.
///
/// Каждое событие зеркалится в logcat (tag WSBridge) — удалённый тестер
/// снимает всю диагностику через `adb logcat -s WSBridge` или bugreport,
/// даже если UI недоступен (краш, процесс убит).
/// ponytail: только память. Персист предыдущего прогона (iOS loadPrevious)
/// добавим, если понадобится post-mortem после убийства процесса системой.
object EventLog {
    private const val TAG = "WSBridge"
    private val entries = ArrayDeque<Pair<String, Long>>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    // WP-3: счётчики io-строки журнала — AtomicLong вместо @Volatile var.
    // Операция += не атомарна для volatile (read-modify-write из нескольких
    // потоков теряет инкременты); AtomicLong.addAndGet/incrementAndGet решают
    // это без явных блокировок. Чтение через .get(), сброс через .set(0).
    val outPkts   = AtomicLong(0L)
    val wsDown    = AtomicLong(0L)
    val writeFails = AtomicLong(0L)
    val upBytes   = AtomicLong(0L)
    val rxBytes   = AtomicLong(0L)
    val pendCur   = AtomicLong(0L)
    val sentCb    = AtomicLong(0L)
    val inV6      = AtomicLong(0L)
    val inOther   = AtomicLong(0L)

    @Synchronized
    fun append(name: String) {
        entries.addLast(name to System.currentTimeMillis())
        while (entries.size > 200) entries.removeFirst()
        Log.i(TAG, name)
    }

    @Synchronized
    fun reset() {
        entries.clear()
        outPkts.set(0); wsDown.set(0); writeFails.set(0); upBytes.set(0)
        rxBytes.set(0); pendCur.set(0); sentCb.set(0); inV6.set(0); inOther.set(0)
    }

    @Synchronized
    fun journal(): String =
        entries.joinToString("\n") { "${fmt.format(Date(it.second))} ${it.first}" }
}

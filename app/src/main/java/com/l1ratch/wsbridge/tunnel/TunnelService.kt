package com.l1ratch.wsbridge.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.l1ratch.wsbridge.BuildConfig
import com.l1ratch.wsbridge.MainActivity
import com.l1ratch.wsbridge.R
import com.l1ratch.wsbridge.TunnelManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/// Порт PacketTunnelProvider.swift: TUN fd вместо packetFlow, всё остальное —
/// lwIP + WS-сплайсинг — без изменений. Перехватывает TCP к DC Telegram,
/// восстанавливает поток через lwIP, парсит init, коннектится к kws-гейтвею.
///
/// ponytail: lwIP NO_SYS=1 однопоточный. ВСЕ операции (input, poll, write,
/// close) идут через один single-thread executor. TUN-read и WS-колбэки
/// приходят с других потоков.
///
/// Конфиг (worker, DNS) читается напрямую из TunnelManager — сервис и UI
/// в одном процессе, intent-extras не нужны (на iOS их заменял
/// providerConfiguration для межпроцессного расширения).
class TunnelService : VpnService() {

    companion object {
        private const val TAG = "WSBridge"
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        /// Перезапуск из уведомления (кнопка в шторке): стоп + старт.
        const val ACTION_RESTART = "restart"
        private const val CHANNEL_ID = "tunnel"
        private const val NOTIF_ID = 1
        /// Сторож старта: столько секунд даём establish(), как 20с в iOS build 77.
        private const val START_WATCHDOG_S = 20L
    }

    private val lwip = LwipNative
    private val sessions = HashMap<Long, TunnelSession>()
    private var packetCount = 0L
    private var byteCount = 0L

    // Работники из «домены через запятую» + счётчик round-robin (iOS build 90):
    // распределяем соединения по доменам, чтобы CF не троттлил один и было
    // разнесение нагрузки. Доступ — только с lwip-потока (onAccept).
    private var workerDomains: List<String> = emptyList()
    private var workerRRIndex = 0

    /// «DC → IP» маппинг из настроек (iOS build 90). Читается на старте,
    /// immutable на время сессии.
    private var dcOverride: Map<String, String> = emptyMap()

    // lwipQueue: единственный поток, которому разрешено трогать lwIP и sessions.
    @Volatile private var lwipExecutor: ScheduledExecutorService? = null
    private var tunIn: FileInputStream? = null
    private var tunOut: FileOutputStream? = null
    private var dnsFd: android.os.ParcelFileDescriptor? = null
    private var dnsForwarder: DNSForwarder? = null
    @Volatile private var stopped = false
    // starting: старт ЭТОГО поколения ещё в полёте. Ставит владелец попытки,
    // а снимает тот, кто её отменяет (stopTunnel/onRevoke) — сам отменённый
    // поток общий флаг не трогает, иначе он погасил бы старт уже НОВОЙ сессии.
    // Снять его на остановке обязательно: иначе guard `starting || tunIn !=
    // null` в onStartCommand навсегда отбивал бы ACTION_START, и туннель
    // нельзя было бы включить заново.
    @Volatile private var starting = false

    // Поколение старта. Каждая попытка захватывает свой номер, и всё, что
    // поток попытки делает после establish(), сверяется с ним. Номер
    // инкрементится и на старте, и на любой остановке, поэтому попытка, чьё
    // establish() вернулось уже после отмены, видит себя устаревшей: она
    // закрывает свой TUN fd и НЕ трогает статус, executor и сторож — они уже
    // принадлежат новой сессии. Так «два establish() одновременно» не может
    // дать два поднятых VPN даже ценой залипания: устаревшая попытка убирает
    // за собой сама.
    // AtomicLong, а не ++: инкрементят и main-поток (onStartCommand), и поток
    // сторожа (abortStalledStart).
    private val startGen = AtomicLong(0L)

    // Сторож старта (iOS build 77): establish() — binder-IPC в system_server,
    // и он может висеть вечно. Оптимистичный updateRunning(true) выше в этом
    // случае оставлял кнопку «включено» навсегда без единой ошибки в журнале.
    // Держим и executor, и задачу: отменённую задачу нужно ещё и погасить,
    // иначе поток останется висеть после снятия сторожа.
    // startWatchdogGen: чей сторож сейчас взведён. Гасим только своего —
    // без этого сторож прошлой сессии, чей cancel не успел/не сработал,
    // выстрелил бы в НОВОЙ сессии: ложный start_stall и снятый статус
    // (окно, в которое бьёт restartTunnel из UI).
    @Volatile private var startWatchdog: ScheduledFuture<*>? = null
    @Volatile private var startWatchdogGen = -1L
    private var watchdogExecutor: ScheduledExecutorService? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_NOT_STICKY: после убийства процесса VPN сам не воскрешаем
        // (consent-диалог показывается только по явному действию пользователя;
        // на iOS поведение то же — смерть расширения = туннель выключен).
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        if (intent.action == ACTION_STOP) {
            stopTunnel()
            return START_NOT_STICKY
        }
        // Кнопка «Перезапустить» в шторке: сначала честно останавливаемся
        // (TUN fd и контур lwIP освобождаются), потом поднимаем новую сессию.
        if (intent.action == ACTION_RESTART) {
            restartFromNotification()
            return START_NOT_STICKY
        }
        if (starting || tunIn != null) return START_NOT_STICKY // уже работает или стартует

        // Несколько worker-доменов через запятую (iOS build 90): каждый ходит
        // отдельной сессией, распределяются round-robin. Пустые элементы режем.
        val workerDomains = TunnelManager.workerDomain
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val workerDomain = workerDomains.firstOrNull()
        // «DC → IP» маппинг: строки «номер:IP».
        val dcOverride = TunnelSession.parseDCOverride(TunnelManager.dcMapText)
        this.workerDomains = workerDomains
        this.workerRRIndex = 0
        this.dcOverride = dcOverride
        val dns = TunnelManager.selectedDNS

        // startForeground обязан успеть в 5с после startForegroundService —
        // делаем сразу и на main-потоке (это быстро).
        startInForeground(dns.name)

        // Своя попытка старта: всё, что она сделает после establish(),
        // сверяется с этим номером, а любая остановка делает её устаревшей.
        val gen = startGen.incrementAndGet()

        // ВСЁ остальное — в фоновом потоке. Сервис и UI живут в одном процессе,
        // а establish() — это binder-IPC в system_server с перенастройкой
        // маршрутов: заблокировав main-поток, он морозит весь интерфейс, и
        // кнопка «выключить» умирает вместе с ним (так и было).
        starting = true
        stopped = false
        // Оптимистично: кнопка сразу переходит в «включено» и тап по ней шлёт
        // ACTION_STOP, даже если establish ещё висит. Без этого при зависшем
        // старте running оставался false и выключить туннель было нельзя.
        TunnelManager.updateRunning(true)
        // Сторож взводится ДО фонового старта: если establish() или что-то
        // после него не дойдёт до конца за 20с — снимаем оптимистичный статус,
        // чтобы кнопка отражала реальность (iOS build 77: «error shown and
        // button unlocked»). Плюс отдельная строка в журнале — тестер видит
        // причину, а не молчаливо «включённый» туннель без трафика.
        //
        // ponytail: отдельный одноразовый поток вместо lwipExecutor — сторож
        // нужен именно тогда, когда рабочий контур ещё не поднялся (executor
        // создаётся только после успешного establish).
        val watchdog = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "wsb-watchdog").apply { isDaemon = true }
        }
        watchdogExecutor = watchdog
        startWatchdog = watchdog.schedule({ abortStalledStart(gen) }, START_WATCHDOG_S, TimeUnit.SECONDS)
        startWatchdogGen = gen
        Thread({
            try {
                startTunnel(workerDomain, dns, gen)
            } catch (t: Throwable) {
                Log.e(TAG, "tunnel start failed", t)
                // Своя неудача: падение устаревшей попытки — не наша ошибка,
                // её start_fail только запутал бы журнал.
                if (startGen.get() == gen) {
                    EventLog.append("start_fail:${t.javaClass.simpleName}:${t.message}")
                    starting = false
                    TunnelManager.updateRunning(false)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            } finally {
                // Гасим только свой сторож: у новой сессии свой, и трогать его
                // отсюда нельзя.
                disarmWatchdog(gen)
            }
        }, "wsb-start").start()
        return START_NOT_STICKY
    }

    /// Сторож старта: попытка `gen` не уложилась в 20с. Задача сторожа — не
    /// только снять статус, но и сделать сервис пригодным к перезапуску.
    ///
    /// Здесь СОЗНАТЕЛЬНО сбрасывается starting. Прежний код не сбрасывал его
    /// намеренно — чтобы не открыть второй establish() поверх висящего
    /// первого, — но обратной стороной стало то, что после сторожа сервис
    /// было не остановить (guard `starting` отбивал ACTION_START, а ACTION_STOP
    /// из UI недостижим: toggle() смотрит только на running и после сторожа
    /// шлёт START). Второй establish() и так невозможен: остановка ниже делает
    /// попытку устаревшей по startGen, а устаревшая в свою очередь не трогает
    /// общее состояние и закрывает свой TUN fd.
    ///
    /// ponytail: stopSelf() НЕ отбирает Android-VPN у system_server. На своей
    /// остановке сервис сам закрывает свой TUN fd и туннель поднимает только
    /// система — так и делает stopTunnel(). Здесь же попытка висит ВНУТРИ
    /// establish(), своего fd у неё ещё нет, и в её потоке лежит VpnService
    /// (Builder().establish() синхронный): закрывать нечего. Если в logcat
    /// будет `start_stall` и при этом активный системный VPN — значит
    /// залипший establish() уже отдал fd в system_server; тогда следующий
    /// шаг — async `establish()` на отдельный поток, чтобы отмена могла его
    /// закрыть. Одиночный же VpnService.stopTunnel() дал бы только лишний
    /// `tunnel_stop:user` в журнале, ничего не развязав.
    private fun abortStalledStart(gen: Long) {
        // Сторож прошлой сессии: работа его отменена, новую трогать нельзя.
        if (startGen.get() != gen) return
        EventLog.append("start_stall:${START_WATCHDOG_S}s")
        // Настоящая остановка, а не только статус: сервис умирает, приложение
        // остаётся живым (сервис и UI в одном процессе), и следующий
        // ACTION_START из UI поднимает чистую сессию.
        stopTunnel()
    }

    private fun startTunnel(workerDomain: String?, dns: TunnelManager.DNSConfig, gen: Long) {
        val tun = Builder()
            .setSession("WSBridge")
            .setMtu(1500)
            .addAddress("198.18.0.2", 32)
            .apply {
                // Только диапазоны Telegram — весь остальной трафик не трогаем.
                for ((addr, prefix) in TelegramDCs.routes) addRoute(addr, prefix)
                // API 29+: системный резолвер пошлёт UDP:53 в TUN — перехватит
                // DNSForwarder (lwIP собран без UDP). На 24–28 DNS-запросы VPN-сети
                // тоже приходят в TUN — форвардер их обрабатывает и без этого.
                if (Build.VERSION.SDK_INT >= 29 && dns.servers.isNotEmpty()) {
                    addDnsServer(dns.servers[0])
                }
            }
            // Свои WS-соединения идут на 149.154.* — ВНУТРИ маршрутов туннеля.
            // Без исключения собственного пакета — бесконечная петля.
            .addDisallowedApplication(packageName)
            .establish()

        if (tun == null) {
            Log.e(TAG, "establish() failed (consent revoked?)")
            // Устаревшая попытка молчит: её отмена уже отчиталась в журнале,
            // а start_fail от неё читался бы как отказ текущего старта.
            if (startGen.get() == gen) {
                EventLog.append("start_fail:establish_null")
                starting = false
                TunnelManager.updateRunning(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        // Проверка поколения — единственное место, где попытка окончательно
        // «принимается», и идёт она сразу после establish(): между ней и
        // первым обращением к общим полям вклиниться уже нечему.
        if (startGen.get() != gen) {
            // Попытку отменили, пока establish() висел (сторож, STOP из UI,
            // onRevoke). Отдавать свой TUN fd общему состоянию нельзя: он
            // сейчас либо чужой рабочий TUN, либо null у остановленного
            // сервиса — запись затрёт рабочий контур или оставит вторую
            // поднятую VPN без владельца. Вместо этого убираем за собой.
            runCatching { tun.close() }
            return
        }
        if (stopped) { // выключили, пока длился establish
            runCatching { tun.close() }
            // starting чужой: сервис уже остановлен, а флаг снимет тот, кто
            // отменял попытку (stopTunnel/onRevoke). Своё `starting = false`
            // здесь погасило бы старт новой сессии, если он уже начался.
            if (startGen.get() == gen) starting = false
            stopSelf()
            return
        }

        // Для общих полей попытка уже «старшая». Но устареть она может и
        // посреди этого блока: пока она пишет fd и поднимает lwIP, сторож
        // сессии прошлой мог обнулить startGen. Тогда fd закрывается, а
        // общий executor в конце не трогается — он принадлежит новой сессии.
        // Гарантии закрытия достаточно: закрытый fd убьёт поток чтения, и
        // контур умрёт сам, без двух живых establ().
        dnsFd = tun
        tunIn = FileInputStream(tun.fileDescriptor)
        tunOut = FileOutputStream(tun.fileDescriptor)

        EventLog.reset()
        // Несколько доменов видим в журнале сразу: «cfg:worker=first(xN)».
        EventLog.append("cfg:worker=${workerDomain ?: "-"}(x${workerDomains.size})")
        if (dcOverride.isNotEmpty()) EventLog.append("cfg:dc_map=${dcOverride.size}")
        EventLog.append("tunnel_start v${BuildConfig.VERSION_NAME}")
        // Скачанные фронты применяются при каждом старте туннеля. FrontsUpdater
        // уже читает prefs при init — здесь страховка на случай, если сервис
        // поднялся раньше Activity (автозапуск, рестарт системы).
        FrontsUpdater.init(applicationContext)
        EventLog.append("fronts:${CFDomains.size}d")
        TunnelManager.updateRunning(true)
        // Сессия пошла: счётчик включений и база дельт для статистики.
        // EventLog только что обнулился — первый snapshot возьмёт чистую базу.
        StatsStore.noteSessionStart()

        lwipExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "wsb-lwip").apply { isDaemon = true }
        }
        // Транспорт выбирает пользователь; фактический может отличаться, если
        // у конфига нет предпочитаемого (см. effectiveDNSMode). Передаём РОВНО
        // один шифрованный транспорт — форвардер не додумывает за пользователя.
        val mode = TunnelManager.effectiveDNSMode
        val dotHost = if (mode == TunnelManager.DNSMode.DOT) dns.dotHostname else null
        val dohUrl = if (mode == TunnelManager.DNSMode.DOH) dns.dohURL else null
        EventLog.append("dns:${dns.id}:${mode?.shortTitle ?: "plain"}:${dns.servers.size}ip")
        dnsForwarder =
            if (dns.servers.isNotEmpty()) {
                DNSForwarder(dns.servers, dotHost, dohUrl) { pkt -> writePacket(pkt) }
            } else null

        if (startGen.get() != gen) {
            // Нас обнулили, пока поднимался контур: не оставляем вторую
            // поднятую VPN и не трогаем ни статус, ни чужой executor.
            closeTun()
            return
        }
        setupLWIP(workerDomain)
        startTimers()
        readLoop()
        if (startGen.get() != gen) return
        starting = false
        // Старт успел уложиться в 20с — сторож больше не нужен. Только свой:
        // у новой сессии свой сторож, и погасить его отсюда нельзя.
        disarmWatchdog(gen)

        // Нуджи и мониторинг сети (паритет с iOS startTunnel, где next to
        // startTimers() идут startPathMonitor() и scheduleLongSessionNudge()):
        // каналы обязаны существовать до первой публикации, монитор ловит
        // Wi-Fi↔соты, нудж напомнит перезапустить туннель через 6 часов.
        NotificationHelper.ensureChannels(this)
        NetworkMonitor.start(this)
        NotificationHelper.scheduleLongSession(this)
    }

    /// Гасит сторож старта независимо от исхода: при штатном старте задача не
    /// должна «выстрелить» позже, при падении — не остаться висеть в очереди.
    /// Вызывать разрешено только владельцу поколения: чужой сторож — это сторож
    /// уже стартовавшей новой сессии, и его отмена оставила бы её без присмотра
    /// (а её залипший establish() — без сторожа вообще). Общий сторож снимает
    /// та остановка, которая обнулила поколение.
    private fun disarmWatchdog(gen: Long) {
        if (startWatchdogGen != gen) return
        // ponytail: cancel(false) уже выполняющуюся задачу не гасит — её работу
        // отсекает проверка поколения внутри abortStalledStart. Именно на это
        // окно и бьёт restartTunnel из UI.
        startWatchdog?.cancel(false)
        startWatchdog = null
        startWatchdogGen = -1L
        // ponytail: shutdown() без awaitTermination — ждать нечего, а
        // блокировать main-поток ради служебного потока нельзя.
        watchdogExecutor?.shutdown()
        watchdogExecutor = null
    }

    /// Перезапуск по кнопке в шторке: честный стоп, затем новый старт.
    /// Старт ставим в очередь через 300мс — даём stopTunnel закрыть TUN fd и
    /// погасить сторож, иначе новый onStartCommand увидит `starting`/`tunIn`
    /// от прошлой сессии и молча откажется стартовать. startService из
    /// уведомления разрешён: это действие пользователя, сервис foreground.
    private fun restartFromNotification() {
        EventLog.append("restart:notif")
        stopTunnel()
        Handler(Looper.getMainLooper()).postDelayed({
            startService(Intent(this, TunnelService::class.java).setAction(ACTION_START))
        }, 300)
    }

    /// Остановка сервиса. Зовётся и из UI (ACTION_STOP), и сторожем старта.
    private fun stopTunnel() {
        if (stopped && tunIn == null) { stopSelf(); return }
        EventLog.append("tunnel_stop:user:pkts=$packetCount")
        // Финальный снапшот статистики ДО разборки (iOS build 92): после
        // остановки счётчики уже не растут, и последняя дельта байтов
        // (последний io-тик) без этого терялась бы навсегда.
        finalizeStats()
        // Старт мог ещё идти: гасим сторож, иначе он пальнёт start_stall
        // уже после осознанной остановки, которую сам же пользователь и сделал.
        // Обнуляем и поколение: попытка, чей establish() ещё висит, станет
        // устаревшей — при возврате она только закроет свой fd и не тронет
        // состояние новой сессии.
        startGen.incrementAndGet()
        disarmWatchdog(startWatchdogGen)
        // Снимаем флаг старта. Без этого guard `starting || tunIn != null` в
        // onStartCommand навсегда отбивал бы ACTION_START, и выключенный
        // туннель нельзя было бы включить заново — тот самый залипший сервис.
        starting = false
        // Память живости фронтов — на диск до смерти сервиса (процесс может
        // быть убит системой сразу после stopSelf).
        FrontsUpdater.persist()
        // Нудж 6ч и монитор сети снимаем ЗДЕСЬ (как cancelLongSessionNudge в
        // iOS stopTunnel, который исполняется всегда): напоминание «туннель
        // давно включён» не должно дожить до момента, когда туннель выключен.
        NetworkMonitor.stop()
        NotificationHelper.cancelLongSession(this)
        TunnelManager.updateRunning(false)
        stopped = true
        lwipExecutor?.let { ex ->
            runCatching {
                ex.execute {
                    for (s in sessions.values) s.handleClose(1)
                    sessions.clear()
                }
            }
            ex.shutdown()
        }
        lwipExecutor = null
        closeTun()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun setupLWIP(workerDomain: String?) {
        lwip.handler = object : LwipNative.Handler {
            override fun onOutput(packet: ByteArray) = writePacket(packet)
            override fun onAccept(connId: Long, dcIp: Long) {
                EventLog.append("accept:c$connId")
                // Несколько worker'ов: round-robin, чтобы CF не троттлил
                // один домен (iOS build 90). Один домен — как раньше.
                val domain: String? = if (workerDomains.size > 1) {
                    val d = workerDomains[workerRRIndex % workerDomains.size]
                    workerRRIndex += 1
                    d
                } else {
                    workerDomain
                }
                sessions[connId] = TunnelSession(connId, dcIp, domain, dcOverride, lwip) { r ->
                    if (!stopped) lwipExecutor?.execute(r)
                }
            }
            override fun onRecv(connId: Long, data: ByteArray) {
                sessions[connId]?.handleData(data)
            }
            override fun onClose(connId: Long, reason: Int) {
                sessions.remove(connId)?.handleClose(reason)
            }
            override fun onSent(connId: Long) {
                sessions[connId]?.handleSent()
            }
        }
        lwip.nativeInit()
    }

    /// lwIP → TUN. В основном с lwip-потока (колбэк netif_output), но DNS-ответы
    /// приходят с dns-fwd потока.
    // ponytail: глобальная synchronized-блокировка на запись в TUN; если станет
    // узким местом — вынести DNS-запись на lwipExecutor.
    @Synchronized
    private fun writePacket(data: ByteArray) {
        EventLog.outPkts += 1
        try {
            tunOut?.write(data)
        } catch (e: Exception) {
            Log.e(TAG, "tun write failed: ${e.message}")
        }
    }

    private fun startTimers() {
        val executor = lwipExecutor ?: return
        // lwIP NO_SYS=1 требует периодического sys_check_timeouts — иначе
        // ретрансмиты зависших сегментов не происходят никогда.
        executor.scheduleAtFixedRate({ if (!stopped) lwip.nativePoll() }, 250, 250, TimeUnit.MILLISECONDS)

        executor.scheduleAtFixedRate({
            if (stopped) return@scheduleAtFixedRate
            // Статистика по дням капается событийно — прямо с этого же тика
            // (15с), без отдельных таймеров и без будки батареи (iOS build 91).
            // rxBytes = от клиента, upBytes = к клиенту (см. StatsStore).
            StatsStore.snapshot(EventLog.rxBytes, EventLog.upBytes)
            EventLog.append(
                "io:in=$packetCount out=${EventLog.outPkts} v6=${EventLog.inV6} oth=${EventLog.inOther} " +
                    "wd=${EventLog.wsDown} wf=${EventLog.writeFails} up=${EventLog.upBytes} rx=${EventLog.rxBytes} " +
                    "pend=${EventLog.pendCur} sent=${EventLog.sentCb} inmem=${lwip.nativeInmemDrops()} conns=${sessions.size}"
            )
        }, 15, 15, TimeUnit.SECONDS)
    }

    private fun readLoop() {
        Thread({
            val buf = ByteArray(32767) // TUN-пакет ≤ MTU
            val input = tunIn ?: return@Thread
            while (!stopped) {
                val n = try {
                    input.read(buf)
                } catch (e: Exception) {
                    if (!stopped) {
                        // TUN умер без onRevoke: система закрыла fd или ядро
                        // дропнуло интерфейс. Журнал и сброс статуса — иначе
                        // кнопка вечно показывает «включено», хотя туннеля нет
                        // (журнал тестера 23:48 обрывался после fronts:40d
                        // без io: и без причины).
                        Log.e(TAG, "tun read failed: ${e.message}")
                        EventLog.append("tun_dead:read:${e.javaClass.simpleName}:${e.message}")
                        finalizeStats()
                        TunnelManager.updateRunning(false)
                    }
                    break
                }
                if (n <= 0) continue
                val packet = buf.copyOf(n)
                lwipExecutor?.execute {
                    if (stopped) return@execute
                    handlePacket(packet)
                }
            }
        }, "wsb-tun").apply { isDaemon = true }.start()
    }

    private fun handlePacket(packet: ByteArray) {
        if (packet.size <= 9) return
        val version = (packet[0].toInt() shr 4) and 0xF
        if (version != 4) {
            EventLog.inV6 += 1
            return
        }
        when (packet[9].toInt()) {
            6 -> { // TCP → lwIP
                packetCount += 1
                byteCount += packet.size
                lwip.nativeInput(packet, packet.size)
                lwip.nativePoll()
            }
            17 -> { // UDP: DNS форвардим, остальное (QUIC) роняем
                if (dnsForwarder?.tryHandle(packet) != true) EventLog.inOther += 1
            }
            else -> EventLog.inOther += 1
        }
    }

    private fun startInForeground(dnsName: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Туннель", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Действия в шторке — Android-аналог кнопок Live Activity: управление
        // туннелем без входа в приложение. «Выключить» — прямое ACTION_STOP,
        // «Перезапустить» — стоп+старт (частая операция после смены сети).
        // Стартовать выключенный туннель отсюда нельзя: Android 12+ запрещает
        // поднимать FGS из фона, а уведомление живёт только пока сервис жив.
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, TunnelService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val restartPi = PendingIntent.getService(
            this, 2, Intent(this, TunnelService::class.java).setAction(ACTION_RESTART),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("WSBridge")
            .setContentText("Туннель активен · $dnsName")
            .setContentIntent(pi)
            .setOngoing(true)
            // Аналог Live Activity (iOS build 96+): таймер аптайма рисует СИСТЕМА
            // по setWhen — сам обновляется каждую секунду, без наших будок
            // батареи. ActivityKit на Android нет, но системный хронометр
            // уведомления — тот же контракт «молния + время работы».
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .addAction(R.drawable.ic_notif_restart, "Перезапустить", restartPi)
            .addAction(R.drawable.ic_notif_stop, "Выключить", stopPi)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /// Финальный снапшот статистики: дельта байтов + хвост аптайма, затем
    /// закрытие сессии в сторе. Идемпотентен — все пути смерти зовут его,
    /// повторный вызов после закрытия ничего не начислит.
    private fun finalizeStats() {
        StatsStore.snapshot(EventLog.rxBytes, EventLog.upBytes)
        StatsStore.noteSessionEnd()
    }

    /// Закрываем именно ParcelFileDescriptor: его close() гарантированно будит
    /// поток, заблокированный в read(fd) (стрим-обёртки — нет).
    private fun closeTun() {
        runCatching { tunIn?.close() }
        runCatching { tunOut?.close() }
        runCatching { dnsFd?.close() }
        tunIn = null; tunOut = null; dnsFd = null
    }

    override fun onRevoke() {
        // Система отзывает VPN (пользователь выключил в настройках / другой VPN).
        EventLog.append("tunnel_stop:revoked:pkts=$packetCount")
        finalizeStats()
        // Остановка гасит сторож ТЕКУЩЕГО поколения: своего владельца у него
        // уже нет (сессия кончилась), а чужой не трогаем — им владеет новая.
        disarmWatchdog(startWatchdogGen)
        FrontsUpdater.persist()
        // Оба пути остановки обязаны снимать нудж и монитор: onRevoke приходит
        // из system_server и НЕ проходит через stopTunnel, так что без этих
        // строк туннель, отозванный из настроек, оставлял бы живой монитор и
        // напоминание «туннель давно включён» при выключенном туннеле.
        NetworkMonitor.stop()
        NotificationHelper.cancelLongSession(this)
        TunnelManager.updateRunning(false)
        stopped = true
        runCatching { lwipExecutor?.shutdownNow() }
        lwipExecutor = null
        closeTun()
        stopSelf()
    }

    override fun onDestroy() {
        stopped = true
        disarmWatchdog(startWatchdogGen)
        // Статистика: страховка для путей, не прошедших через stopTunnel/
        // onRevoke (start_fail:*, establish_null, tun_dead).
        finalizeStats()
        // Страховка на пути, которые до сюда не дошли (start_fail:*,
        // establish_null, tun_dead): сервис умирает всегда, а монитор сети и
        // нудж 6ч не должны пережить его. Оба вызова идемпотентны.
        NetworkMonitor.stop()
        NotificationHelper.cancelLongSession(this)
        TunnelManager.updateRunning(false)
        runCatching { lwipExecutor?.shutdownNow() }
        closeTun()
        super.onDestroy()
    }
}

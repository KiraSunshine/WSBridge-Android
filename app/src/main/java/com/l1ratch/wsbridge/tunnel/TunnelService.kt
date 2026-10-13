package com.l1ratch.wsbridge.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
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
import java.util.concurrent.TimeUnit

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
        private const val CHANNEL_ID = "tunnel"
        private const val NOTIF_ID = 1
    }

    private val lwip = LwipNative
    private val sessions = HashMap<Long, TunnelSession>()
    private var packetCount = 0L
    private var byteCount = 0L

    // lwipQueue: единственный поток, которому разрешено трогать lwIP и sessions.
    @Volatile private var lwipExecutor: ScheduledExecutorService? = null
    private var tunIn: FileInputStream? = null
    private var tunOut: FileOutputStream? = null
    private var dnsFd: android.os.ParcelFileDescriptor? = null
    private var dnsForwarder: DNSForwarder? = null
    @Volatile private var stopped = false
    @Volatile private var starting = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_NOT_STICKY: после убийства процесса VPN сам не воскрешаем
        // (consent-диалог показывается только по явному действию пользователя;
        // на iOS поведение то же — смерть расширения = туннель выключен).
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        if (intent.action == ACTION_STOP) {
            stopTunnel()
            return START_NOT_STICKY
        }
        if (starting || tunIn != null) return START_NOT_STICKY // уже работает или стартует

        val workerDomain = TunnelManager.workerDomain.trim().takeIf { it.isNotEmpty() }
        val dns = TunnelManager.selectedDNS

        // startForeground обязан успеть в 5с после startForegroundService —
        // делаем сразу и на main-потоке (это быстро).
        startInForeground(dns.name)

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
        Thread({
            try {
                startTunnel(workerDomain, dns)
            } catch (t: Throwable) {
                Log.e(TAG, "tunnel start failed", t)
                EventLog.append("start_fail:${t.javaClass.simpleName}:${t.message}")
                starting = false
                TunnelManager.updateRunning(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, "wsb-start").start()
        return START_NOT_STICKY
    }

    private fun startTunnel(workerDomain: String?, dns: TunnelManager.DNSConfig) {
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
            EventLog.append("start_fail:establish_null")
            starting = false
            TunnelManager.updateRunning(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (stopped) { // выключили, пока длился establish
            runCatching { tun.close() }
            starting = false
            stopSelf()
            return
        }

        dnsFd = tun
        tunIn = FileInputStream(tun.fileDescriptor)
        tunOut = FileOutputStream(tun.fileDescriptor)

        EventLog.reset()
        EventLog.append("cfg:worker=${workerDomain ?: "-"}")
        EventLog.append("tunnel_start v${BuildConfig.VERSION_NAME}")
        // Скачанные фронты применяются при каждом старте туннеля. FrontsUpdater
        // уже читает prefs при init — здесь страховка на случай, если сервис
        // поднялся раньше Activity (автозапуск, рестарт системы).
        FrontsUpdater.init(applicationContext)
        EventLog.append("fronts:${CFDomains.size}d")
        TunnelManager.updateRunning(true)

        lwipExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "wsb-lwip").apply { isDaemon = true }
        }
        dnsForwarder =
            if (dns.servers.isNotEmpty() || dns.dohURL != null) {
                DNSForwarder(dns.servers, dns.dohURL) { pkt -> writePacket(pkt) }
            } else null

        setupLWIP(workerDomain)
        startTimers()
        readLoop()
        starting = false
    }

    private fun setupLWIP(workerDomain: String?) {
        lwip.handler = object : LwipNative.Handler {
            override fun onOutput(packet: ByteArray) = writePacket(packet)
            override fun onAccept(connId: Long, dcIp: Long) {
                EventLog.append("accept:c$connId")
                sessions[connId] = TunnelSession(connId, dcIp, workerDomain, lwip) { r ->
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
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("WSBridge")
            .setContentText("Туннель активен · $dnsName")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopTunnel() {
        if (stopped && tunIn == null) { stopSelf(); return }
        EventLog.append("tunnel_stop:user:pkts=$packetCount")
        // Память живости фронтов — на диск до смерти сервиса (процесс может
        // быть убит системой сразу после stopSelf).
        FrontsUpdater.persist()
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
        FrontsUpdater.persist()
        TunnelManager.updateRunning(false)
        stopped = true
        runCatching { lwipExecutor?.shutdownNow() }
        lwipExecutor = null
        closeTun()
        stopSelf()
    }

    override fun onDestroy() {
        stopped = true
        TunnelManager.updateRunning(false)
        runCatching { lwipExecutor?.shutdownNow() }
        closeTun()
        super.onDestroy()
    }
}

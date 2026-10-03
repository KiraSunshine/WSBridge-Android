package com.l1ratch.wsbridge.tunnel

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/// WS-клиент к kws-гейтвею Telegram. Порт WSClient.swift (OkHttp вместо URLSession).
/// Каждый MTProto-пакет — отдельный WS-фрейм.
///
/// ponytail: один общий OkHttpClient на все соединения (~12 параллельных сессий).
/// Каскад: CF-фронты (ротация старта) → прямые IP гейтвеев → kws{dc}.web.telegram.org.
///
/// Прямые IP на Android реализованы чище, чем на iOS: вместо Host-хедера +
/// подмены peer-name в TLS-валидации (OkHttp перезаписывает Host из URL) —
/// кастомный Dns на per-endpoint клиенте: URL/SNI/Host/валидация сертификата
/// остаются доменными (kws{dc}.web.telegram.org, сертификат *.web.telegram.org
/// проходит штатно), а TCP уходит на выбранный IP.
class WSClient(private val tag: String = "") {

    companion object {
        /// Системный DNS-резолвер: InetAddress.getAllByName использует системный
        /// резолвер, который для disallowed-приложения идёт мимо VPN. Без этого
        /// OkHttp внутри VpnService-процесса может получить VPN-сеть как active
        /// network и DNS-запросы уйдут в TUN (где lwIP без UDP их потеряет).
        private val systemDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                InetAddress.getAllByName(hostname).toList()
        }

        private val client: OkHttpClient = OkHttpClient.Builder()
            .dns(systemDns)
            .pingInterval(20, TimeUnit.SECONDS) // гейтвей не должен рвать молчащий WS
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // WS живёт долго; таймаутами управляет каскад
            .build()

        private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ws-timer").apply { isDaemon = true }
        }

        /// Прямые IP kws-гейтвеев, для которых ДОКАЗАНО, что они обслуживают DC.
        /// .220 — дефолт десктопного tg-ws-proxy (совпадает с dc_redirects
        /// апстрима: {2: .220, 4: .220}). Проверено замером TLS и живым
        /// журналом: сертификат валиден для kws2/kws4 и НЕ валиден для
        /// kws1/kws3/kws5 — SAN *.telegram.org покрывает один уровень, а не
        /// kwsNNN.web.telegram.org. Гейтвей выбирает сертификат по SNI, а
        /// OkHttp берёт SNI из URL, поэтому для DC1/3/5 .220 недостижим в
        /// принципе, а не «пока не ожил».
        ///
        /// Для DC1/DC3/DC5 прямого адреса нет. Их домены резолвятся в TG-диапазон
        /// (kws1/kws3 -> 149.154.174.100, kws5 -> 149.154.170.100), который в
        /// проверенной сети не отвечает; туда ведёт kws{dc}.web.telegram.org в
        /// конце каскада. Добавлять сюда IP стоит только с подтверждением, что
        /// он живой И что сертификат покрывает нужный SNI.
        ///
        /// Зачем вообще ограничивать: .220 первым для чужого DC — это гарантированный
        /// провал TLS. Он дешёвый (сертификат отваливается сразу, без ожидания
        /// таймаута), но в журнале тестера давал ложную картину «DC1 не работает»
        /// и лишние ws_err на каждое переподключение: c7:DC1 -> .220 -> not verified.
        /// Адреса .99 и .174.100 сюда не нужны: их и так возвращает DNS для
        /// kws{dc}.web.telegram.org, то есть доменный эндпоинт их покрывает.
        private val dcGatewayIps = mapOf(
            2 to listOf("149.154.167.220"),
            4 to listOf("149.154.167.220"),
        )

        private var rrStart = 0

        /// Кэш здоровья эндпоинтов: упавший (timeout/ошибка до первых данных)
        /// исключается из каскадов на 10 минут. Ключ = IP для прямых, host для доменных.
        /// Одновременно ПАМЯТЬ реестра: фейлы/успехи уходят во FrontsUpdater —
        /// живость фронтов переживает рестарт и влияет на вход в каскад.
        /// Чужие ключи (IP, kws*.web.telegram.org) реестр просто игнорирует.
        private val deadUntil = HashMap<String, Long>()

        @Synchronized
        fun markDead(key: String) {
            deadUntil[key] = System.currentTimeMillis() + 600_000
            FrontsUpdater.recordFailure(key)
        }

        @Synchronized
        fun markAlive(key: String) {
            deadUntil.remove(key)
            FrontsUpdater.recordSuccess(key)
        }

        @Synchronized
        private fun aliveOnly(eps: List<Endpoint>): List<Endpoint> {
            val now = System.currentTimeMillis()
            return eps.filter { ep ->
                val until = deadUntil[ep.healthKey] ?: return@filter true
                if (until > now) false else { deadUntil.remove(ep.healthKey); true }
            }
        }

        private const val relaySecret = "wsb1" // == SECRET в tools/vps_relay.py (iOS-репо)
    }

    /// host — домен для URL/SNI/Host/сертификата; overrideIp — прямой IP
    /// гейтвея (null = обычный DNS).
    private class Endpoint(val host: String, val overrideIp: String?) {
        val healthKey: String get() = overrideIp ?: host
    }

    private var ws: WebSocket? = null
    private var firstRecv = false
    private var upPosted = false
    private var opened = false

    // relay-режим (прямой TCP на VPS)
    private var relaySocket: Socket? = null
    private var relayOut: OutputStream? = null
    private var relayReady = false
    private val relayPending = ByteArrayOutputStream()
    private val relayLock = Any()
    @Volatile private var closed = false

    private fun post(name: String) {
        EventLog.append(if (tag.isEmpty()) name else "$tag:$name")
    }

    /// Подключается к kws-гейтвею. Порядок: прямые IP гейтвеев → CF-фронты
    /// (ротация старта) → kws{dc}.web.telegram.org. Прямые IP первыми: они не
    /// требуют DNS и работают даже при полном отравлении доменов. CF-фронты
    /// быстрее на LTE, но только если DNS жив; идут вторыми.
    /// initFrame (64-байтовый MTProto init) шлётся первым фреймом на КАЖДОМ
    /// эндпоинте каскада — при failover старый socket со своим init выбрасывается.
    fun connect(
        dc: Int, isMedia: Boolean, isTestDC: Boolean, initFrame: ByteArray,
        onMessage: (ByteArray) -> Unit, onClose: () -> Unit,
    ) {
        val path = if (isTestDC) "/apiws_test" else "/apiws"
        // Media-DC у десктопа ходит на kws{dc}-1; обычный — kws{dc}.
        val gwHost = if (isMedia) "kws${dc}-1.web.telegram.org" else "kws$dc.web.telegram.org"

        // Round-robin старт: Telegram держит ~12 параллельных соединений —
        // каждое начнёт со своего фронта и живые найдутся быстрее.
        val fronts = CFDomains.domains(dc)
        rrStart = (rrStart + 1) % fronts.size
        val rotated = fronts.subList(rrStart, fronts.size) + fronts.subList(0, rrStart)

        // Порядок: прямые IP, обслуживающие ЭТОТ DC (без DNS, работают при
        // отравленных доменах) → CF-фронты (ротация старта) → kws{dc}.web.telegram.org.
        // Для чужого DC прямой IP не даёт даже TLS: сертификат не покрывает
        // kws{dc} (см. dcGatewayIps) — эндпоинт мёртв по построению.
        val directEps = (dcGatewayIps[dc] ?: emptyList()).map { Endpoint(gwHost, it) }
        val domainEps = rotated.take(6).map { Endpoint(it, null) } + Endpoint(gwHost, null)

        // Кэш здоровья фильтрует ВСЕ эндпоинты, включая прямые IP. Раньше
        // прямые были освобождены от него «чтобы не зависеть от DNS» — но
        // побочный эффект оказался хуже: когда гейтвей .220 умер (журнал
        // 11:12: .220 давал ws_up мгновенно, через 10 часов — таймаут 5с),
        // он НЕ помечался dead и каждая новая сессия снова начинала с него.
        // Клиент сдаётся через 8с и не доходит до живых CF-фронтов.
        // Очистка кэша при «все dead» (ifEmpty) гарантирует, что ничего не
        // теряется: все эндпоинты пробуются по кругу.
        val healthy = aliveOnly(directEps + domainEps)
        val endpoints = healthy.ifEmpty { directEps + domainEps }

        post("cascade:dc=$dc:${endpoints.size}ep")
        tryConnect(endpoints, path, 0, initFrame, onMessage, onClose)
    }

    private fun tryConnect(
        endpoints: List<Endpoint>, path: String, index: Int,
        initFrame: ByteArray, onMessage: (ByteArray) -> Unit, onClose: () -> Unit,
    ) {
        if (closed) return
        if (index >= endpoints.size) {
            post("ws_fail")
            onClose()
            return
        }
        val ep = endpoints[index]
        post("ws_try:${ep.healthKey}")

        val request = Request.Builder()
            .url("wss://${ep.host}$path")
            .header("Sec-WebSocket-Protocol", "binary")
            .build()
        // newBuilder дёшев: dispatcher и connection pool общие.
        val epClient = if (ep.overrideIp != null) {
            val ip = ep.overrideIp
            client.newBuilder().dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    listOf(InetAddress.getByName(ip))
            }).build()
        } else {
            client
        }

        // Failover валиден, пока гейтвей не прислал ни байта: тот же init
        // отправляется на следующем эндпоинте (CTR-позиции клиента и DC
        // синхронны от init, потерянные кадры MTProto ретраит сам).
        // После первых полученных данных любая ошибка закрывает сессию —
        // Telegram переподключится (новая сессия, новый каскад).
        val claimed = AtomicBoolean(false)
        val delivered = AtomicBoolean(false)

        fun advance() {
            if (claimed.compareAndSet(false, true)) {
                ws = null
                tryConnect(endpoints, path, index + 1, initFrame, onMessage, onClose)
            }
        }
        fun fail() {
            if (claimed.compareAndSet(false, true)) {
                ws = null
                post("ws_fail:${ep.healthKey}")
                onClose()
            }
        }

        val socket = epClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 101 получен; init (отправленный сразу после newWebSocket) уже
                // в очереди OkHttp и уйдёт первым фреймом.
                delivered.set(true)
                opened = true
                if (!upPosted) { upPosted = true; post("ws_up") }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                claimed.set(true)
                firstRecv = true
                markAlive(ep.healthKey)
                onMessage(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // гейтвей шлёт только binary; строки игнорируем
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                post("ws_err:${ep.healthKey}:${t.message ?: t.javaClass.simpleName}")
                if (firstRecv) {
                    markAlive(ep.healthKey) // умерла живая сессия — эндпоинт не виноват
                    fail()
                } else {
                    markDead(ep.healthKey)
                    advance()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                post("ws_closed:$code:$reason")
                claimed.set(true)
                onClose()
            }
        })
        ws = socket
        // Init — первым фреймом (OkHttp буферизует до конца handshake).
        socket.send(initFrame.toByteString())

        // Таймер только на фазу коннекта (5с: живые фронты дают 101 за <1с,
        // висящие эндпоинты не должны съедать терпение Telegram).
        // Доставленный init (onOpen → delivered) гасит его; отменять schedule
        // не нужно — claim-логика сама пропускает срабатывание.
        scheduler.schedule({
            if (!delivered.get() && claimed.compareAndSet(false, true)) {
                post("ws_timeout:${ep.healthKey}")
                markDead(ep.healthKey)
                socket.cancel()
                ws = null
                tryConnect(endpoints, path, index + 1, initFrame, onMessage, onClose)
            }
        }, 5, TimeUnit.SECONDS)
    }

    /// Pipe-режим через СОБСТВЕННЫЙ CF worker пользователя: сырые байты в обе
    /// стороны, worker сам открывает TCP к dst:443. Ни гейтвея, ни сплиттера.
    fun connectPipe(workerDomain: String, dst: String, onMessage: (ByteArray) -> Unit, onClose: () -> Unit) {
        post("ws_try:$workerDomain")
        val request = Request.Builder()
            .url("wss://$workerDomain/apiws?dst=$dst")
            .build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Эквивалент ws_ping на iOS: onOpen == WS реально открыт (101).
                opened = true
                post("ws_ping")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!firstRecv) {
                    firstRecv = true
                    if (!upPosted) { upPosted = true; post("ws_up") }
                }
                onMessage(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {}

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                post("ws_closed:err:${t.message ?: "?"}")
                onClose()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                post("ws_closed:$code:$reason")
                onClose()
            }
        })
        ws = socket
        // Watchdog: рвём в 7с ТОЛЬКО если WS не открылся. При живом onOpen
        // воркер здоров, а DC может легитимно думать над тяжёлым getDifference
        // дольше 7с — убив такое соединение, перезапускаем catch-up с нуля.
        // Зависший connect к DC закрывает fail-fast воркера (4с).
        scheduler.schedule({
            if (!closed && !firstRecv && !opened) {
                post("ws_slow")
                socket.cancel()
                ws = null
                onClose()
            }
        }, 7, TimeUnit.SECONDS)
    }

    /// Прямой режим: сырой TCP на своё реле (VPS) — без CF, без WS.
    /// Протокол тот же, что у воркера: строка «секрет dst\n», дальше сырой поток.
    fun connectRelay(host: String, port: Int, dst: String, onMessage: (ByteArray) -> Unit, onClose: () -> Unit) {
        post("relay_try:$host:$port")
        Thread({
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), 5000)
                socket.tcpNoDelay = true
                val out = socket.getOutputStream()
                synchronized(relayLock) {
                    if (closed) return@Thread
                    relaySocket = socket
                    relayOut = out
                    relayReady = true
                    out.write("$relaySecret $dst\n".toByteArray())
                    if (relayPending.size() > 0) out.write(relayPending.toByteArray())
                    relayPending.reset()
                }
                post("relay_up")
                val buf = ByteArray(65536)
                val input = socket.getInputStream()
                while (!closed) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        if (!firstRecv) {
                            firstRecv = true
                            if (!upPosted) { upPosted = true; post("ws_up") }
                        }
                        onMessage(buf.copyOf(n))
                    }
                }
                if (!closed) {
                    post("relay_closed:end")
                    onClose()
                }
            } catch (e: IOException) {
                if (!closed) {
                    post("relay_closed:${e.message ?: "io"}")
                    onClose()
                }
            } finally {
                runCatching { socket.close() }
            }
        }, "relay-$host").apply { isDaemon = true }.start()
        // Реле принимает мгновенно; нет connect за 5с — адрес недоступен.
    }

    /// send сразу после создания WS: OkHttp сам буферизует до конца handshake.
    fun send(data: ByteArray) {
        if (closed) return
        relaySocket?.let {
            synchronized(relayLock) {
                try {
                    if (relayReady) relayOut?.write(data) else relayPending.write(data)
                } catch (_: IOException) { /* обрыв добьёт read-поток → onClose */ }
            }
            return
        }
        ws?.send(data.toByteString())
    }

    fun sendBatch(parts: List<ByteArray>) {
        for (p in parts) send(p)
    }

    fun close() {
        closed = true
        ws?.cancel() // cancel, не close: не ждём close-фрейма от гейтвея
        ws = null
        synchronized(relayLock) {
            relayReady = false
            runCatching { relaySocket?.close() }
            relaySocket = null
        }
    }
}

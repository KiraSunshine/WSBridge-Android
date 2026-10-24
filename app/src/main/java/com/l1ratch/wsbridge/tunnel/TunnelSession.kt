package com.l1ratch.wsbridge.tunnel

/// Оркестратор одного соединения: lwIP TCP ↔ WS к kws-гейтвею.
/// Порт TunnelSession.swift. Парсит init, коннектится к kws{dc}, мостит
/// байты через MsgSplitter.
///
/// ponytail: ВСЕ lwIP-операции (write, close) вызываются ТОЛЬКО с lwip-потока
/// (single-thread executor TunnelService). WS-колбэки приходят с потоков OkHttp
/// — handleWSData/handleWSClose перебрасывают работу через queue.
class TunnelSession(
    val connId: Long,
    private val dcIP: Long,
    private val workerDomain: String?,
    /// Override «DC → IP» из настроек (iOS build 90): канонический адрес DC
    /// → IP, который worker'у реально диалить. Пусто = как прилетело.
    private val dcOverride: Map<String, String> = emptyMap(),
    private val bridge: LwipNative,
    private val queue: (Runnable) -> Unit,
) {
    private var ws: WSClient? = null
    private var splitter: MsgSplitter? = null
    private var initBuffer = ByteArray(0)
    private var initParsed = false
    private var wsConnected = false
    private var dataSent = false
    private var upHeadLogged = false
    private var pending: ByteArray? = null
    private val createdAt = System.currentTimeMillis()

    /// Сессия закрыта; слот connId мог уйти новому соединению — писать/закрывать нельзя.
    private var dead = false
    private var recvCount = 0
    private var wfailLogged = 0

    /// Данные от клиента (Telegram) через lwIP. Вызывается на lwip-потоке.
    fun handleData(data: ByteArray) {
        EventLog.rxBytes += data.size
        // Pipe-режим: собственный CF worker пользователя. Без init-парсинга и
        // сплиттера — сырой поток в WS, worker домостит его до DC:443.
        if (workerDomain != null) {
            if (!upHeadLogged) {
                upHeadLogged = true
                logHead(data)
            }
            if (!wsConnected) startPipe()
            forwardToWS(data)
            return
        }
        if (!initParsed) {
            initBuffer += data
            if (initBuffer.size >= InitParser.handshakeLen) {
                val initData = initBuffer.copyOfRange(0, InitParser.handshakeLen)
                val parsed = InitParser.parse(initData)
                if (parsed != null) {
                    initParsed = true
                    if (!upHeadLogged) {
                        upHeadLogged = true
                        logHead(initData)
                    }
                    startWS(parsed)
                    if (initBuffer.size > InitParser.handshakeLen) {
                        forwardToWS(initBuffer.copyOfRange(InitParser.handshakeLen, initBuffer.size))
                    }
                } else {
                    // Не MTProto (обычно TLS от Telegram Web) — сначала причина,
                    // потом в журнал; хекс-дамп здесь только мешал бы читать лог.
                    postEvent("c${connId}:bad_init:${InitParser.describe(initData)}")
                    bridge.nativeClose(connId)
                }
            }
        } else {
            forwardToWS(data)
        }
    }

    /// Хекс первых байт — только для принятого соединения, не для отброшенных:
    /// иначе журнал из 200 строк состоит из дампов и причину отказа не видно.
    private fun logHead(data: ByteArray) {
        val head = data.take(48).joinToString("") { "%02x".format(it) }
        postEvent("uphead:c$connId:${data.size}:$head")
    }

    private fun startWS(parsed: InitParser.ParsedInit) {
        // DC берётся из адреса, к которому подключился клиент: в init на Android
        // его нет (см. TelegramDCs.dcByIp). Раньше читали init — получали
        // kws26369.web.telegram.org и NXDOMAIN на каждом эндпоинте.
        val dstIp = formatIp(dcIP)
        val dc = TelegramDCs.resolve(dstIp, parsed.dcId, parsed.isMedia)
        postEvent("init:conn${connId}:DC${dc.dc}${if (dc.fromIp) ":dst=$dstIp" else ":miss:$dstIp"}")

        splitter = MsgSplitter(parsed.key, parsed.iv, parsed.protoTag)

        // 64-байтовый init — первый WS-фрейм. WSClient шлёт его на каждом
        // эндпоинте каскада, поэтому передаём сюда, а не отдельным send().
        // dc_idx приводим к выбранному DC: гейтвей сверяет его с hostname.
        val initData = InitParser.rewriteDc(
            initBuffer.copyOfRange(0, InitParser.handshakeLen), parsed.key, parsed.iv, dc.dc
        )

        val ws = WSClient(tag = "c$connId")
        this.ws = ws
        ws.connect(
            dc = dc.dc, isMedia = dc.isMedia, isTestDC = dc.isTest,
            initFrame = initData,
            onMessage = { data -> handleWSData(data) },
            onClose = { handleWSClose() },
        )
        wsConnected = true
        postEvent("ws_sent")
    }

    private fun startPipe() {
        val workerDomain = workerDomain ?: return
        var dst = formatIp(dcIP)
        // «DC → IP»: если на канонический адрес DC в настройках назначен
        // другой IP — worker диалит его, а не исходный (iOS build 90).
        val overrideIP = dcOverride[dst]
        if (!overrideIP.isNullOrEmpty()) {
            postEvent("pipe_remap:$dst->$overrideIP")
            dst = overrideIP
        }
        postEvent("pipe:conn$connId:$dst" + if (dcIP == 0L) ":raw=0" else "")
        val ws = WSClient(tag = "c$connId")
        this.ws = ws
        val onMsg: (ByteArray) -> Unit = { data -> handleWSData(data) }
        val onCls: () -> Unit = { handleWSClose() }
        // Поле «host:port» = прямое реле на VPS: сырой TCP, CF не участвует.
        val parts = workerDomain.split(":", limit = 2)
        val port = parts.getOrNull(1)?.toIntOrNull()
        if (parts.size == 2 && port != null) {
            ws.connectRelay(parts[0], port, dst, onMsg, onCls)
        } else {
            ws.connectPipe(workerDomain, dst, onMsg, onCls)
        }
        wsConnected = true
    }

    private fun postEvent(name: String) = EventLog.append(name)

    private fun forwardToWS(data: ByteArray) {
        if (!wsConnected) return
        val ws = ws ?: return
        EventLog.upBytes += data.size
        // ws_data = клиентские данные реально ушли на гейтвей (один раз на сессию).
        if (!dataSent) {
            dataSent = true
            postEvent("ws_data:conn$connId:${data.size}B")
        }
        val splitter = splitter
        if (splitter != null) {
            val parts = splitter.split(data)
            if (parts.size == 1) ws.send(parts[0])
            else if (parts.size > 1) ws.sendBatch(parts)
        } else {
            ws.send(data)
        }
    }

    /// Данные от kws-гейтвея → клиенту через lwIP.
    /// WS-колбэк приходит с потока OkHttp — гоним через очередь.
    private fun handleWSData(data: ByteArray) {
        // ponytail: ws_recv на КАЖДЫЙ кадр затапливал журнал — первые 3 и далее каждый 100-й.
        recvCount += 1
        if (recvCount <= 3 || recvCount % 100 == 0) {
            postEvent("ws_recv:c${connId}:n${recvCount}:${data.size}B")
        }
        queue {
            // Сессия мертва — слот connId мог быть переиспользован новым
            // соединением; запоздалые байты отравят его шифрпоток.
            if (dead) return@queue
            EventLog.wsDown += data.size
            writeOrQueue(data)
        }
    }

    /// ponytail: tcp_write может вернуть ERR_MEM (окно/буфер забиты). Раньше
    /// байты дропались — дыра в шифрпотоке фатальна для MTProto. Теперь
    /// очередь: дожидается sent-колбэка и дописывает.
    /// Куски ≤65535: native-мост принимает u16-длину, WS-кадр может быть >64KB.
    private fun writeOrQueue(data: ByteArray): Boolean {
        pending?.let {
            pending = it + data
            EventLog.pendCur += data.size
            return false
        }
        var off = 0
        while (off < data.size) {
            val end = minOf(off + 65535, data.size)
            val chunk = data.copyOfRange(off, end)
            if (bridge.nativeWrite(connId, chunk, chunk.size) == 0) {
                off = end
                continue
            }
            EventLog.writeFails += 1
            val rest = data.copyOfRange(off, data.size)
            pending = rest
            EventLog.pendCur += rest.size
            if (wfailLogged < 3) {
                wfailLogged += 1
                postEvent("wfail:c$connId:inmem=${bridge.nativeInmemDrops()}")
            }
            return false
        }
        return true
    }

    /// Освободилось место в send-буфере lwIP — дописываем очередь. На lwip-потоке.
    fun handleSent() {
        EventLog.sentCb += 1
        val p = pending ?: return
        pending = null
        EventLog.pendCur = 0 // writeOrQueue пересчитает, если снова ERR_MEM
        writeOrQueue(p)
    }

    private fun handleWSClose() {
        postEvent("ws_close:c$connId")
        queue {
            // Если сессию уже закрыл клиент (handleClose), слот мог уйти
            // новому соединению — nativeClose убил бы его.
            if (dead) return@queue
            dead = true
            bridge.nativeClose(connId)
        }
    }

    /// Соединение закрыто. reason: 0 = FIN клиента, 1 = stop,
    /// отрицательное = err_t lwIP (RST и т.п.). На lwip-потоке.
    fun handleClose(reason: Int = 0) {
        dead = true
        val why = if (reason == 0) "fin" else "r$reason"
        postEvent("c${connId}:close:$why:${(System.currentTimeMillis() - createdAt) / 1000}s")
        ws?.close()
        ws = null
        wsConnected = false
    }

    companion object {
        fun formatIp(ip: Long): String =
            "${(ip shr 24) and 255}.${(ip shr 16) and 255}.${(ip shr 8) and 255}.${ip and 255}"

        // ── «DC → IP» маппинг (порт TunnelSession.swift, iOS build 90) ──

        /// Канонические IPv4 главных датацентров. В pipe-режиме init не
        /// парсится, DC-номер недоступен — матчим override по адресу назначения.
        /// Таблица та же, что на iOS: конфиги переносятся между платформами.
        val canonicalIPs: List<Pair<Int, String>> = listOf(
            1 to "149.154.175.50",
            2 to "149.154.167.220",
            3 to "149.154.175.100",
            4 to "149.154.167.91",
            5 to "91.108.56.130",
        )

        /// Из строк «номер:IP» (по одной на строку) строит карту
        /// канонический_IP_этого_DC → указанный_IP. Невалидные строки молча
        /// пропускаются — список руками правит пользователь, ошибки тут не
        /// фатальны.
        fun parseDCOverride(rawText: String): Map<String, String> {
            val canonByDC = canonicalIPs.toMap()
            val map = HashMap<String, String>()
            for (raw in rawText.lines()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val parts = line.split(":", limit = 2)
                if (parts.size != 2) continue
                val dc = parts[0].trim().toIntOrNull() ?: continue
                val canon = canonByDC[dc] ?: continue
                val ip = parts[1].trim()
                // IP грубо валидируем: 4 октета числами 0–255.
                val octets = ip.split(".").mapNotNull { it.toIntOrNull() }
                if (octets.size != 4 || octets.any { it !in 0..255 }) continue
                map[canon] = ip
            }
            return map
        }
    }
}

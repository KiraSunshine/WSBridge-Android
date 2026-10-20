package com.l1ratch.wsbridge.tunnel

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.os.SystemClock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket

/// Перехватывает DNS-запросы (UDP:53) из туннеля и форвардит их.
///
/// Транспорты по приоритету:
/// 1. **DoT** (RFC 7858): TLS к IP:853 с SNI = dotHostname. Коннект идёт
///    напрямую к IP — резолв имени сервера НЕ нужен, это самый надёжный
///    вариант под туннелем, когда собственный резолвер ещё не поднялся.
/// 2. **DoH** (RFC 8484): HTTPS POST на dohURL. Обязательно требует резолва
///    имени хоста из URL, и этот резолв делает система уже под туннелем —
///    ступень, которая и ломала DoH.
/// 3. **plain UDP:53** — фолбэк для конфигов без шифрования и на случай,
///    когда оба шифрованных транспорта не ответили.
///
/// На Android форвардер ОБЯЗАТЕЛЕН: lwIP собран с LWIP_UDP=0, а
/// addDnsServer даёт системному резолверу только один адрес — приложения,
/// резолвящие сами, шлют UDP в TUN и без форвардера умерли бы молча.
/// В отличие от iOS здесь нет системного NEDNSOverTLSSettings, поэтому DoT
/// сделан вручную: TLS + SNI + кадрирование DNS-over-TCP.
class DNSForwarder(
    private val dnsServers: List<String>,
    private val dotHostname: String? = null,
    private val dohURL: String? = null,
    private val writePacket: (ByteArray) -> Unit,
) {
    /// OkHttpClient для DoH-запросов. Таймауты короткие — DNS должен быть быстрым.
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    /// Пул на всё время жизни форвардера. daemon-потоки: форвардер живёт
    /// столько же, сколько сервис, и не должен удерживать процесс.
    /// ponytail: 2 потока, а не кэширующий пул — DNS-запросы редки и коротки,
    /// а лишние параллельные TLS-сессии только жгут батарею.
    private val requestPool: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "dns-fwd").apply { isDaemon = true }
    }

    /// Проверяет, является ли пакет DNS-запросом (UDP:53).
    /// Возвращает true если пакет перехвачен и обработан.
    fun tryHandle(packet: ByteArray): Boolean {
        if (packet.size <= 28) return false
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (packet[9].toInt() != 17) return false // UDP
        if (packet.size <= ihl + 8) return false
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        if (dstPort != 53) return false
        val payload = packet.copyOfRange(ihl + 8, packet.size)
        if (payload.isEmpty() || dnsServers.isEmpty()) return false

        // Ограниченный пул вместо потока на пакет. Раньше здесь создавался
        // поток на каждый перехваченный пакет с обоснованием «запросов
        // единицы» — но это неверно: браузер, ANY-запрос или шторм от любого
        // приложения в туннеле дают поток плюс TCP+TLS-сессию на секунды, и
        // всё это в процессе VPN (OOM/EMFILE = смерть туннеля). Два потока
        // покрывают реальную нагрузку: DNS-запросы редки и коротки.
        requestPool.execute {
            val response = resolve(payload)
            if (response != null) {
                writePacket(wrapResponse(response, packet, ihl))
            } else {
                EventLog.append(
                    "dns:fail:${effectiveModeLabel()}:udp=${dnsServers.size}"
                )
            }
        }
        return true
    }

    /// Что фактически пытались использовать — для журнала. Раньше строка
    /// журнала писалась из TunnelService и сообщала ВЫБРАННЫЙ транспорт, а не
    /// сработавший: при сбое DoT она врала.
    private fun effectiveModeLabel(): String = when {
        !dotHostname.isNullOrBlank() && !dohURL.isNullOrBlank() -> "dot+doh"
        !dotHostname.isNullOrBlank() -> "dot"
        !dohURL.isNullOrBlank() -> "doh"
        else -> "plain"
    }

    /// Порядок попыток.
    ///
    /// Шифрованный транспорт выбирает пользователь, и форвардер его НЕ
    /// расширяет: если сработавший транспорт отличается от выбранного, это
    /// видно в журнале, но молча деградировать в открытый UDP нельзя —
    /// провайдер снова увидит домены, то есть сломается ровно то, ради чего
    /// транспорт выбирался. Plain UDP остаётся доступен только конфигам,
    /// у которых шифрованного транспорта нет вообще.
    ///
    /// Второй шифрованный транспорт пробуется как запасной: у пресетов в
    /// списке есть заведомо нерабочие адреса (см. комментарии к xbox/malw),
    /// и отказ одного сервера не должен оставлять туннель без резолва вовсе.
    private fun resolve(payload: ByteArray): ByteArray? {
        // Бюджет по elapsedRealtime: wall-clock прыгает от NTP и перевода
        // часов, и дедлайн считался бы неверно (в NetworkMonitor проект
        // осознанно использует именно elapsedRealtime).
        val deadline = SystemClock.elapsedRealtime() + TOTAL_BUDGET_MS
        if (!dotHostname.isNullOrBlank() && SystemClock.elapsedRealtime() < deadline) {
            queryDoT(dotHostname, payload)?.let { return it }
        }
        if (!dohURL.isNullOrBlank() && SystemClock.elapsedRealtime() < deadline) {
            queryDoH(dohURL, payload)?.let { return it }
        }
        // Шифрованный транспорт был, но не ответил — молчим, а не разоблачаемся.
        if (!dotHostname.isNullOrBlank() || !dohURL.isNullOrBlank()) {
            EventLog.append("dns:encrypted_failed:${effectiveModeLabel()}")
            return null
        }
        for (server in dnsServers) queryUDP(server, payload)?.let { return it }
        return null
    }

    /// DoT: TLS на 853 с SNI, DNS-over-TCP (RFC 7858) — 2-байтный big-endian
    /// префикс длины + DNS-пакет, ответ в том же формате.
    private fun queryDoT(hostname: String, payload: ByteArray): ByteArray? {
        // Кадр собирается ЗАРАНЕЕ, вне цикла: при смене сервера на проводе
        // должен уйти тот же самый кадр, а не пересобранный.
        // Если кадр построить нельзя (пустой или больше 65535), не отправляем
        // заведомо битую длину, а отдаём решение следующему транспорту —
        // исключение отсюда убило бы поток dns-fwd молча, вместе с фолбэком.
        val frame = try {
            frameQuery(payload)
        } catch (_: IllegalArgumentException) {
            return null
        }
        for (server in dnsServers) {
            try {
                // Сокет поднимается до IP, а TLS-обёртка — на хост: так SNI и
                // проверка имени работают, хотя резолва имени нет (адрес берём
                // из списка). Проверка имени ВКЛЮЧЕНА: без неё любой сертификат,
                // подписанный доверенным CA, выдавал бы себя за резолвер —
                // «аутентичность по IP-списку» этого не заменяет, потому что
                // доверие к IP означает лишь, что мы не ходим по DNS.
                val plain = java.net.Socket()
                plain.use { raw ->
                    raw.soTimeout = 3000
                    raw.connect(InetSocketAddress(InetAddress.getByName(server), 853), 3000)
                    val factory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
                    val sock = factory.createSocket(raw, hostname, 853, true) as SSLSocket
                    sock.use { s ->
                        val params = s.sslParameters
                        params.endpointIdentificationAlgorithm = "HTTPS"
                        params.serverNames = listOf(SNIHostName(hostname))
                        s.sslParameters = params
                        s.soTimeout = 3000
                        s.startHandshake()

                        val out = DataOutputStream(s.outputStream)
                        out.write(frame)
                        out.flush()

                        val input = DataInputStream(s.inputStream)
                        // Заголовок читается readFully — он дочитывает сегмент
                        // за сегментом, поэтому приход длины и тела разными
                        // TCP-сегментами обрабатывается верно.
                        val header = ByteArray(2)
                        input.readFully(header)
                        val len = frameLength(header)
                        // Потолок — 16-битное поле длины RFC 7858 (65535), а НЕ
                        // 4096: 4096 это лимит UDP-датаграммы, к TLS он не
                        // относится, и длинный ответ (DNSSEC, большой RRset,
                        // TXT/ANY) молча превращался в «сервер не ответил».
                        if (len <= 0) return null
                        val buf = ByteArray(len)
                        input.readFully(buf)
                        return buf
                    }
                }
            } catch (_: Exception) {
                // сервер не ответил — пробуем следующий
            }
        }
        return null
    }

    /// DoH: RFC 8484, wire format over HTTPS POST.
    /// Content-Type: application/dns-message. Тело — сырой DNS-пакет.
    private fun queryDoH(url: String, payload: ByteArray): ByteArray? = try {
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/dns-message".toMediaType()))
            .header("Accept", "application/dns-message")
            .build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.bytes()
        }
    } catch (_: Exception) {
        null
    }

    /// Plain UDP:53 — фолбэк для конфигов без шифрования.
    private fun queryUDP(server: String, payload: ByteArray): ByteArray? = try {
        DatagramSocket().use { sock ->
            sock.soTimeout = 3000
            val addr = InetAddress.getByName(server)
            sock.send(DatagramPacket(payload, payload.size, addr, 53))
            val buf = ByteArray(4096)
            val dp = DatagramPacket(buf, buf.size)
            sock.receive(dp)
            buf.copyOf(dp.length)
        }
    } catch (_: Exception) {
        null
    }

    /// Кадр запроса DoT (RFC 7858): 2-байтная big-endian длина + DNS-пакет.
    /// internal, а не private: это чистая сборка байтов, и ошибка в длине или
    /// порядке байт молча ломает DNS целиком — проверяется юнит-тестом.
    /// Пустой пакет и пакет, который не влезает в 16-битную длину, отвергаются
    /// исключением: усечь длину молча — значит отправить по проводу кадр,
    /// который сервер прочитает как чужой, и получить пустой ответ без ошибки.
    internal fun frameQuery(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty()) { "DNS payload must not be empty" }
        require(payload.size <= MAX_FRAME_PAYLOAD) { "DNS payload too large: ${payload.size}" }
        val out = ByteArray(payload.size + 2)
        out[0] = ((payload.size shr 8) and 0xFF).toByte()
        out[1] = (payload.size and 0xFF).toByte()
        payload.copyInto(out, 2)
        return out
    }

    /// Длина из 2-байтного big-endian префикса кадра — без I/O и без проверок
    /// диапазона (их делает parseResponse): тесту нужно уметь собрать в том
    /// числе и заведомо плохую длину, чтобы доказать, что она отвергается.
    internal fun frameLength(header: ByteArray): Int {
        require(header.size >= 2) { "frame header must be 2 bytes" }
        return ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
    }

    /// Обратная сторона кадра: 2-байтный префикс длины + уже прочитанные байты
    /// → DNS-сообщение. Потолок — 16-битное поле длины (RFC 7858), а не лимит
    /// UDP-датаграммы: длинный TLS-ответ это норма (DNSSEC, большие RRset).
    /// Проверка `body.size < len` остаётся для случая, когда тело пришло
    /// отдельным вызовом: иначе обрезанный ответ уехал бы в туннель как
    /// валидный, и клиент не переспросил бы.
    /// internal по той же причине, что и frameQuery.
    internal fun parseResponse(header: ByteArray, body: ByteArray): ByteArray? {
        val len = frameLength(header)
        if (len <= 0 || len > MAX_FRAME_PAYLOAD) return null
        if (body.size < len) return null
        return body.copyOf(len)
    }

    /// Оборачивает DNS-ответ обратно в IP/UDP пакет (swap src/dst).
    /// internal, а не private: это чистая сборка байтов, и единственное место, где
    /// ошибка поля молча роняет весь DNS-ответ — проверяется юнит-тестом.
    internal fun wrapResponse(response: ByteArray, original: ByteArray, ihl: Int): ByteArray {
        val totalLen = 20 + 8 + response.size
        val udpLen = 8 + response.size
        val out = ByteArray(totalLen)

        out[0] = 0x45
        out[2] = (totalLen shr 8).toByte()
        out[3] = (totalLen and 0xFF).toByte()
        out[8] = 64 // TTL
        out[9] = 17 // UDP
        System.arraycopy(original, 16, out, 12, 4) // src = original dst (DNS server side)
        System.arraycopy(original, 12, out, 16, 4) // dst = original src (client)

        // UDP: swap ports
        System.arraycopy(original, ihl + 2, out, 20, 2) // src port = original dst port (53)
        System.arraycopy(original, ihl, out, 22, 2)      // dst port = original src port
        out[24] = (udpLen shr 8).toByte()
        out[25] = (udpLen and 0xFF).toByte()
        // UDP checksum = 0 (optional for IPv4)
        System.arraycopy(response, 0, out, 28, response.size)

        // IP header checksum
        out[10] = 0; out[11] = 0
        val csum = checksum(out, 0, 20)
        out[10] = (csum shr 8).toByte()
        out[11] = (csum and 0xFF).toByte()
        return out
    }

    companion object {
        /// Потолок кадра DoT: ровно 16 бит поля длины (RFC 7858) — и на
        /// запрос, и на ответ. Раньше здесь стояло 4096 (лимит UDP-датаграммы),
        /// и это было ошибкой: длинный TLS-ответ (DNSSEC, большой RRset,
        /// TXT/ANY) не доходил, а выглядело это как «сервер не ответил» —
        /// без единой строки в журнале. Одна константа на оба направления:
        /// разъехавшись, границы не сломали бы ни одну сборку и убили бы
        /// весь DNS в туннеле — молча.
        const val MAX_FRAME_PAYLOAD = 0xFFFF

        /// Общий бюджет времени на одну попытку резолва. Один DoT-сервер
        /// тратит до 6с (connect + handshake + чтение), а серверов в пресете
        /// до четырёх — без бюджета клиент, ждущий DNS, копил бы десятки
        /// секунд. Бюджет режет хвост: не успевшие серверы не пробуем.
        const val TOTAL_BUDGET_MS = 8000L

        fun checksum(b: ByteArray, off: Int, len: Int): Int {
            var sum = 0
            var i = off
            while (i < off + len - 1) {
                sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
                i += 2
            }
            if (len % 2 == 1) sum += (b[off + len - 1].toInt() and 0xFF) shl 8
            while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
            return sum.inv() and 0xFFFF
        }
    }
}
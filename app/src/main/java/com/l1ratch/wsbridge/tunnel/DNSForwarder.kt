package com.l1ratch.wsbridge.tunnel

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
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

        // ponytail: свой поток на запрос — DNS-запросов из туннеля единицы,
        // пул не нужен.
        Thread({
            val response = resolve(payload)
            if (response != null) {
                writePacket(wrapResponse(response, packet, ihl))
            } else {
                EventLog.append(
                    "dns:fail:dot=${!dotHostname.isNullOrBlank()}:doh=${!dohURL.isNullOrBlank()}:udp=${dnsServers.size}"
                )
            }
        }, "dns-fwd").apply { isDaemon = true }.start()
        return true
    }

    /// Порядок попыток: DoT → DoH → plain UDP.
    private fun resolve(payload: ByteArray): ByteArray? {
        if (dotHostname != null) queryDoT(dotHostname, payload)?.let { return it }
        if (dohURL != null) queryDoH(dohURL, payload)?.let { return it }
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
                val s = javax.net.ssl.SSLSocketFactory.getDefault()
                    .createSocket() as SSLSocket
                s.use { sock ->
                    val params = sock.sslParameters
                    // Hostname verification отключена: коннектимся к IP, а имя
                    // живёт только в SNI — IP не совпадёт с хостом сертификата.
                    // Целостность даёт TLS, аутентичность — доверие к IP-списку.
                    params.endpointIdentificationAlgorithm = null
                    params.serverNames = listOf(SNIHostName(hostname))
                    sock.sslParameters = params
                    sock.soTimeout = 3000
                    sock.connect(InetSocketAddress(InetAddress.getByName(server), 853), 3000)
                    sock.startHandshake()

                    val out = DataOutputStream(sock.outputStream)
                    out.write(frame)
                    out.flush()

                    val input = DataInputStream(sock.inputStream)
                    // Чтение байт-в-байт как было: 2 байта заголовка, затем
                    // РОВНО len байт тела через readFully. Длину приходится
                    // разбирать здесь же — иначе не узнать, сколько читать, —
                    // а сам разбор/проверку делает общая чистая функция.
                    val header = ByteArray(2)
                    input.readFully(header)
                    val len = frameLength(header)
                    if (len <= 0 || len > MAX_DNS_MESSAGE) return null
                    val buf = ByteArray(len)
                    input.readFully(buf)
                    return parseResponse(header, buf)
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
    /// → DNS-сообщение. Границы те же, что были в queryDoT
    /// (`len <= 0 || len > 4096`), плюс проверка, что тело не короче длины
    /// (обрыв TCP-потока): иначе ответ уехал бы в туннель обрезанным, а клиент
    /// принял бы его за настоящий ответ и не переспросил.
    /// internal по той же причине, что и frameQuery.
    internal fun parseResponse(header: ByteArray, body: ByteArray): ByteArray? {
        val len = frameLength(header)
        if (len <= 0 || len > MAX_DNS_MESSAGE) return null
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
        /// Потолок длины DNS-ответа: длина в кадре DoT — 2 байта, но больше
        /// 4096 байт UDP-ответ не несёт, и именно эту границу проверял
        /// queryDoT (`len <= 0 || len > 4096`). Константа одна на весь файл:
        /// разъехавшись, границы не сломали бы ни одну сборку и убили бы
        /// весь DNS в туннеле — молча.
        const val MAX_DNS_MESSAGE = 4096

        /// Потолок полезной нагрузки кадра запроса: ровно столько влезает в
        /// 2-байтный big-endian префикс длины (RFC 7858), и больше этого
        /// закодировать нечем. Отдельно от MAX_DNS_MESSAGE намеренно: там
        /// ограничение транспорта (UDP), здесь — только формат кадра.
        const val MAX_FRAME_PAYLOAD = 0xFFFF

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
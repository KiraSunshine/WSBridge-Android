package com.l1ratch.wsbridge.tunnel

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/// Перехватывает DNS-запросы (UDP:53) из туннеля и форвардит их на указанные
/// серверы. Приоритет: DoH (если задан dohURL) > plain UDP:53.
///
/// На Android это ОБЯЗАТЕЛЬНО: lwIP собран с LWIP_UDP=0, а setDnsServer
/// даёт системному резолверу только один адрес — приложения, резолвящие сами
/// (Telegram), шлют UDP в TUN и без форвардера умерли бы молча.
///
/// Без DoH гео-блокировки по DNS не обходятся: провайдер видит запрос на
/// порт 53 и может отравить ответ или заблокировать. DoH шифрует запрос
/// внутри TLS, провайдер видит только HTTPS-трафик к домену резолвера.
class DNSForwarder(
    private val dnsServers: List<String>,
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
        if (payload.isEmpty()) return false

        // ponytail: свой поток на запрос — DNS-запросов из туннеля единицы
        // (Telegram резолвит DC-адреса редко), пул не нужен.
        Thread({
            // Приоритет 1: DoH (если задан).
            if (dohURL != null) {
                val response = queryDoH(dohURL, payload)
                if (response != null) {
                    writePacket(wrapResponse(response, packet, ihl))
                    return@Thread
                }
                // DoH упал — fallback на plain UDP.
            }

            // Приоритет 2: plain UDP:53.
            if (dnsServers.isNotEmpty()) {
                for (server in dnsServers) {
                    val response = queryUDP(server, payload) ?: continue
                    writePacket(wrapResponse(response, packet, ihl))
                    return@Thread
                }
            }

            EventLog.append("dns:fail:doh=${dohURL != null}:udp=${dnsServers.size}")
        }, "dns-fwd").apply { isDaemon = true }.start()
        return true
    }

    /// DoH-запрос: RFC 8484, DNS wire format over HTTPS POST.
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

    /// Plain UDP:53 запрос.
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

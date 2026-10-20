package com.l1ratch.wsbridge

import com.l1ratch.wsbridge.tunnel.DNSForwarder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/// DNS-ответ уходит в туннель как собранный с нуля IP/UDP пакет. Ошибка в любом
/// поле заголовка не даёт исключения — ядро просто отбрасывает пакет, и в журнале
/// не появляется ничего. Поэтому каждое поле проверяется явно.
///
/// Кадрирование DoT (RFC 7858) проверяется здесь же: 2-байтная длина — это
/// единственное место, где ошибка порядка байт молча ломает ВЕСЬ DNS в туннеле
/// (сервер прочитает длину как чужие байты и ответит мусором либо порвёт коннект).
class DNSForwarderTest {

    private val ihl = 20

    private fun forwarder() = DNSForwarder(listOf("8.8.8.8")) { }

    /// Реалистичный DNS-запрос: заголовок с id и QDCOUNT=1 плюс имя example.com.
    /// Ровно такие пакеты приносит TUN на UDP:53 — 29 байт.
    private fun dnsQuery(id: Int = 0x1234): ByteArray {
        val header = byteArrayOf(
            (id shr 8).toByte(), (id and 0xFF).toByte(), // id
            0x01, 0x00,                                  // flags: standard query, RD
            0x00, 0x01,                                  // QDCOUNT = 1
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00,          // AN/NS/AR = 0
        )
        val name = byteArrayOf(7) + "example".toByteArray() +
            byteArrayOf(3) + "com".toByteArray() + byteArrayOf(0)
        return header + name + byteArrayOf(0x00, 0x01, 0x00, 0x01) // QTYPE=A, QCLASS=IN
    }

    /// UDP:53 запрос клиента 10.0.0.2:34567 -> 8.8.8.8:53 с корректной
    /// контрольной суммой IP (как его приносит TUN).
    /// `ihl` > 20 — пакет с опциями: тогда UDP-заголовок стоит НЕ на 20-м байте,
    /// и порты надо брать по смещению ihl, иначе в ответ уедут байты опций.
    private fun request(
        ihl: Int = 20,
        srcPort: Int = 34567,
        dns: ByteArray = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01), // id=0x1234, qdcount=1
    ): ByteArray {
        val udpLen = 8 + dns.size
        val p = ByteArray(ihl + udpLen)

        p[0] = (0x40 or (ihl / 4)).toByte() // IPv4 + IHL (5 = 20 байт, 6 = 24)
        p[2] = (p.size shr 8).toByte(); p[3] = (p.size and 0xFF).toByte()
        p[8] = 64
        p[9] = 17 // UDP
        p[12] = 10; p[13] = 0; p[14] = 0; p[15] = 2   // src 10.0.0.2
        p[16] = 8; p[17] = 8; p[18] = 8; p[19] = 8     // dst 8.8.8.8
        if (ihl > 20) {
            // Опции (NOP NOP EOL EOL): без них IHL=6 был бы невалидным пакетом.
            p[20] = 0x01; p[21] = 0x01; p[22] = 0x00; p[23] = 0x00
        }
        p[ihl] = (srcPort shr 8).toByte(); p[ihl + 1] = (srcPort and 0xFF).toByte()
        p[ihl + 2] = 0; p[ihl + 3] = 53
        p[ihl + 4] = (udpLen shr 8).toByte(); p[ihl + 5] = (udpLen and 0xFF).toByte()
        System.arraycopy(dns, 0, p, ihl + 8, dns.size)

        p[10] = 0; p[11] = 0
        val csum = DNSForwarder.checksum(p, 0, ihl)
        p[10] = (csum shr 8).toByte(); p[11] = (csum and 0xFF).toByte()
        return p
    }

    private fun wrap(response: ByteArray): ByteArray {
        val fwd = DNSForwarder(listOf("8.8.8.8")) { }
        return fwd.wrapResponse(response, request(), ihl)
    }

    private fun be16(b: ByteArray, off: Int) = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    // ── Кадрирование DoT (RFC 7858) ──────────────────────────────────

    /// Длина идёт ДВУМЯ байтами big-endian и считается от полезной нагрузки,
    /// а не от кадра целиком: сервер читает именно её и отрезает ровно столько.
    /// (Для запроса в 29 байт старший байт нулевой — переключение байт на
    /// границе 255/256 проверяется отдельным тестом.)
    @Test
    fun `frameQuery prefixes a DNS query with its big endian length`() {
        val dns = dnsQuery()
        val frame = forwarder().frameQuery(dns)

        assertEquals(12 + 1 + 7 + 1 + 3 + 1 + 4, dns.size) // заголовок + имя + QTYPE/QCLASS
        assertEquals(29, dns.size)
        assertEquals(dns.size + 2, frame.size)
        assertEquals(0x00, frame[0].toInt() and 0xFF) // 29 = 0x001D
        assertEquals(0x1D, frame[1].toInt() and 0xFF)
        assertArrayEqualsBytes(dns, frame.copyOfRange(2, frame.size))
    }

    /// Смещение на границе 255/256: если байты длины перепутать местами
    /// (или писать little-endian), ошибка вылезает ровно здесь, на 300 байтах,
    /// и ни на одном меньшем пакете.
    @Test
    fun `frameQuery keeps both length bytes across the 255 boundary`() {
        val fwd = forwarder()

        val f255 = fwd.frameQuery(ByteArray(255) { 0x11 })
        val f256 = fwd.frameQuery(ByteArray(256) { 0x22 })
        assertEquals(0x00, f255[0].toInt() and 0xFF)
        assertEquals(0xFF, f255[1].toInt() and 0xFF)
        assertEquals(0x01, f256[0].toInt() and 0xFF)
        assertEquals(0x00, f256[1].toInt() and 0xFF)

        for (len in listOf(255, 256, 257, 300, 4096)) {
            val payload = ByteArray(len) { (it and 0x7F).toByte() }
            val frame = fwd.frameQuery(payload)
            assertEquals("len=$len", len + 2, frame.size)
            assertEquals("len=$len", len shr 8, frame[0].toInt() and 0xFF)
            assertEquals("len=$len", len and 0xFF, frame[1].toInt() and 0xFF)
            assertArrayEqualsBytes(payload, frame.copyOfRange(2, frame.size))
        }
    }

    /// Круговой прогон: то, что положили в кадр, обязано вернуться из него
    /// байт-в-байт — иначе клиент получит не свой ответ (сбой id/вопроса).
    @Test
    fun `frameQuery and parseResponse round-trip the query`() {
        val dns = dnsQuery()
        val fwd = forwarder()
        val frame = fwd.frameQuery(dns)
        val header = frame.copyOfRange(0, 2)

        assertEquals(dns.size, fwd.frameLength(header))

        val parsed = fwd.parseResponse(header, frame.copyOfRange(2, frame.size))
        assertNotNull(parsed)
        assertArrayEqualsBytes(dns, parsed!!)
    }

    /// Границы длины ответа. 0 — сервер сказал «ничего»; 4097+ — это уже не
    /// наш лимит (в UDP больше 4096 не приходит), и такой длине доверять
    /// нельзя: по ней читаются байты из сокета. Обе крайние точки обязаны
    /// отвергаться, а 1 и 4096 — проходить.
    @Test
    fun `parseResponse rejects lengths outside the sane range`() {
        val fwd = forwarder()

        assertNull("len=0", fwd.parseResponse(byteArrayOf(0x00, 0x00), ByteArray(16)))
        assertNull("len=4097", fwd.parseResponse(byteArrayOf(0x10, 0x01), ByteArray(4097)))
        assertNull("len=65535", fwd.parseResponse(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), ByteArray(65535)))

        assertNotNull("len=1", fwd.parseResponse(byteArrayOf(0x00, 0x01), ByteArray(1)))
        assertNotNull("len=4096", fwd.parseResponse(byteArrayOf(0x10, 0x00), ByteArray(4096)))
    }

    /// Обрыв TCP-потока: длина обещает больше, чем пришло. Взять «сколько есть»
    /// нельзя — обрезанный ответ уедет в туннель как валидный, и клиент не
    /// переспросит. Лишние байты (следующий кадр в том же сокете) отрезаются.
    @Test
    fun `parseResponse needs the whole body and drops trailing bytes`() {
        val fwd = forwarder()

        assertNull("тело короче длины", fwd.parseResponse(byteArrayOf(0x00, 0x10), ByteArray(15)))

        val exact = fwd.parseResponse(byteArrayOf(0x00, 0x10), ByteArray(16) { 0x5A })
        assertNotNull(exact)
        assertEquals(16, exact!!.size)

        val trimmed = fwd.parseResponse(byteArrayOf(0x00, 0x04), ByteArray(10) { 0x42 })
        assertNotNull(trimmed)
        assertEquals(4, trimmed!!.size)
        assertArrayEqualsBytes(ByteArray(4) { 0x42 }, trimmed)
    }

    /// Длину больше 65535 в 2-байтный префикс не записать. Молча усечённая
    /// длина = кадр, который сервер прочитает как чужой, и пустой ответ без
    /// единой ошибки в журнале — поэтому отказ, а не усечение.
    @Test
    fun `frameQuery refuses payloads that do not fit in the length field`() {
        val fwd = forwarder()

        assertThrows(IllegalArgumentException::class.java) { fwd.frameQuery(ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { fwd.frameQuery(ByteArray(65536)) }

        val max = fwd.frameQuery(ByteArray(65535))
        assertEquals(65537, max.size)
        assertEquals(0xFF, max[0].toInt() and 0xFF)
        assertEquals(0xFF, max[1].toInt() and 0xFF)
    }

    @Test
    fun `response swaps addresses and ports`() {
        val body = ByteArray(16) { 0x42 }
        val out = wrap(body)

        assertEquals(ihl + 8 + body.size, out.size)
        // src = адрес DNS-сервера, dst = клиент (симметрично исходному запросу).
        assertEquals(8, out[12].toInt()); assertEquals(8, out[13].toInt())
        assertEquals(8, out[14].toInt()); assertEquals(8, out[15].toInt())
        assertEquals(10, out[16].toInt()); assertEquals(0, out[17].toInt())
        assertEquals(0, out[18].toInt()); assertEquals(2, out[19].toInt())
        // Ответ идёт с 53 на клиентский порт.
        assertEquals(53, be16(out, 20))
        assertEquals(34567, be16(out, 22))
        // Полезная нагрузка DNS неповрежденна.
        assertArrayEqualsBytes(body, out.copyOfRange(28, out.size))
    }

    /// Порты клиента бывают любыми. Ошибка в порядке байт порта (0x0102 вместо
    /// 0x0201) не видна на «красивом» 34567, поэтому берём значения, у которых
    /// байты заведомо разные, включая оба края диапазона.
    @Test
    fun `response swaps ports for any client port`() {
        val fwd = forwarder()
        for (port in listOf(1, 1024, 34567, 65535)) {
            val out = fwd.wrapResponse(ByteArray(8) { 1 }, request(srcPort = port), ihl)
            assertEquals("port=$port", 53, be16(out, 20))
            assertEquals("port=$port", port, be16(out, 22))
        }
    }

    @Test
    fun `ip header length and checksum are valid`() {
        val out = wrap(ByteArray(16) { 0x7F })
        assertEquals(0x45, out[0].toInt() and 0xFF)   // IPv4, IHL=5
        assertEquals(17, out[9].toInt())             // протокол UDP
        assertEquals(64, out[8].toInt())             // TTL
        assertEquals(out.size, be16(out, 2))         // total length
        assertEquals(8 + 16, be16(out, 24))          // UDP length
        // Свойство IP-заголовка: пересчёт контрольной суммы по пакету как есть
        // даёт ноль. (Обнулять поле checksum нужно только при ВЫЧИСЛЕНИИ суммы —
        // проверка идёт по собранному пакету, иначе всегда возвращается сама сумма.)
        assertEquals(0, DNSForwarder.checksum(out, 0, ihl))
    }

    /// Входящий пакет с опциями (IHL=24): UDP-заголовок у него на 24-м байте,
    /// но ответ всегда собирается с IHL=5, то есть UDP на 20-м. Если порты
    /// брать по фиксированному смещению 20, в ответ уедут байты опций — пакет
    /// уйдёт не с 53 и не на порт клиента, и DNS умрёт молча.
    @Test
    fun `response finds udp header behind ip options`() {
        val fwd = forwarder()
        val original = request(ihl = 24)
        val body = ByteArray(20) { 0x5A }
        val out = fwd.wrapResponse(body, original, 24)

        // Опции в ответ не тащатся: заголовок всегда 20 байт.
        assertEquals(20 + 8 + body.size, out.size)
        assertEquals(0x45, out[0].toInt() and 0xFF)
        assertEquals(0x46, original[0].toInt() and 0xFF) // вход был 24-байтный — иначе тест пустой

        // Порты взяты из UDP-заголовка входящего пакета (смещение 24), а лежат
        // в собранном ответе на 20-м байте.
        assertEquals(53, be16(out, 20))
        assertEquals(34567, be16(out, 22))
        assertEquals(8 + body.size, be16(out, 24))
        assertEquals(out.size, be16(out, 2))
        assertEquals(0, DNSForwarder.checksum(out, 0, 20))
        assertArrayEqualsBytes(body, out.copyOfRange(28, out.size))
    }

    @Test
    fun `udp checksum is zero which ipv4 permits`() {
        val out = wrap(ByteArray(8) { 1 })
        assertEquals(0, be16(out, 26))
    }

    private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals("byte $i", expected[i], actual[i])
    }
}
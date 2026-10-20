package com.l1ratch.wsbridge.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/// Порт `startPathMonitor()` из PacketTunnelProvider.swift (iOS build 82):
/// переключение Wi-Fi ↔ соты рвёт WS-сессии и состояние lwIP МОЛЧА — трафик
/// «протухает» до ручного перезапуска туннеля, и пользователь видит «включено»
/// при нерабочем интернете.
///
/// На iOS монитор живёт в расширении, потому что приложение в фоне усыплено и
/// NWPathMonitor там молчит. На Android это не нужно: сервис и UI — один
/// процесс, поэтому ОДИН монитор закрывает обе задачи — системный нудж
/// (`NotificationHelper.postNetChange`) и in-app алерт (`alertAt` /
/// `lastChangeDesc`, их читает UI).
///
/// ponytail: сравниваем только транспорт (WIFI/CELLULAR/ETHERNET…) и
/// валидность сети, без IP-адресов и имён интерфейсов. `onCapabilitiesChanged`
/// приходит часто (полоса, лимиты, счётчики), а подпись при этом не меняется —
/// лишних записей в журнале и лишних нуджей не будет. Если понадобится ловить
/// смену сети ВНУТРИ одного транспорта (роуминг, смена соты без смены
/// транспорта) — добавлять сюда адрес сети.
object NetworkMonitor {

    /// Троттлинг системного нуджа (iOS: 300 с). Журнал пишется ВСЕГДА,
    /// глушится только уведомление — иначе флэпы Wi-Fi забивают шторку.
    private const val NOTIF_THROTTLE_MS = 5 * 60 * 1000L

    /// Время последней смены сети (wall-clock), 0 = алерта нет.
    /// UI читает это поле и сам решает, показывать ли баннер «сеть менялась»;
    /// снимается через [clearAlert].
    var alertAt: Long by mutableStateOf(0L)

    /// Человекочитаемое описание последней смены: «Wi-Fi → сотовая сеть».
    var lastChangeDesc: String by mutableStateOf("")

    /// Подпись состояния, с которым сравниваем: «online:WIFI», «offline:none».
    /// null = базовый замер ещё не сделан (первый колбэк после [start]).
    @Volatile private var lastSig: String? = null

    /// Отметка последнего нуджа. Именно `elapsedRealtime`, а не wall-clock:
    /// троттлинг не должен ломаться от перевода часов и NTP-прыжков.
    @Volatile private var lastNotifAt = 0L

    @Volatile private var cm: ConnectivityManager? = null
    @Volatile private var appContext: Context? = null

    /// Колбэки одной регистрации система доставляет серийно с одного
    /// handler-потока, так что порядок «prev → cur» не рвётся.
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onChanged()
        override fun onLost(network: Network) = onChanged()
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = onChanged()

        /// Сеть пропала, не успев стать доступной (doze, переход в роуминг).
        ///
        /// Обработчик без параметров — и это не упрощение: недоступность не про
        /// конкретную сеть, а про отсутствие сети по умолчанию вовсе. Свежее
        /// состояние в колбэке не передаётся никак, поэтому его надо перечитать
        /// в [signature] — как это и делает [onChanged].
        ///
        /// Выбран вариант «маршрутизировать onUnavailable в [onChanged]», а НЕ
        /// «в offline-ветке обнулять базовую подпись»:
        ///
        /// 1. Исчезновение сети становится ЗАПИСАННЫМ состоянием, а не дырой в
        ///    наблюдении. Возврат сети после пропажи сравнивается с честным
        ///    `offline:none` и читается как «нет сети → сотовая сеть» — а не как
        ///    «Wi-Fi → сотовая сеть», где одной строкой прикрыт весь провал,
        ///    включая самый важный для туннеля момент: уход в offline.
        /// 2. Обнуление `lastSig = null` — это НЕ то же самое, а регресс: для
        ///    [onChanged] null означает «базовый замер ещё не сделан», поэтому
        ///    возврат сети сразу после пропажи был бы проглочен начисто — без
        ///    строки `net_change:` и без нуджа, ровно в том сценарии, ради
        ///    которого монитор и заведён.
        /// 3. Ничего не «подавить» этот override не может: он не гасит другие
        ///    колбэки, а только перечитывает состояние, поэтому он безопасен
        ///    даже там, где система его не позовёт.
        ///
        /// ponytail: документация обещает `onUnavailable()` только для
        /// `requestNetwork()`-регистраций, а мы на
        /// `registerDefaultNetworkCallback` — значит, на части устройств этот
        /// метод не позовут, и фикс там best-effort. Сам метод добавлен в API 26,
        /// а `minSdk` у нас 24: на 24–25 переопределение просто никем не
        /// вызывается (сборка идёт против compileSdk, вреда нет, но и пользы
        /// там ноль). Плюс `signature()` читает состояние синхронно, а SDK прямо
        /// предупреждает, что внутри колбэков такое чтение может отдать
        /// устаревший объект. Все три ограничения принимаем: они способны лишь
        /// отсрочить запись пропажи до следующего события, но не выдумать смену —
        /// сравнение идёт с последним УВИДЕННЫМ состоянием, а не с моментом
        /// времени.
        override fun onUnavailable() = onChanged()
    }

    /// Подписка на смену сети по умолчанию. Вызывается сервисом при старте
    /// туннеля (рядом с startTimers) — как `startPathMonitor()` на iOS.
    ///
    /// Требует ACCESS_NETWORK_STATE: без него регистрация бросает
    /// SecurityException, поэтому сбой пишем в журнал, а не роняем сервис.
    fun start(context: Context) {
        if (cm != null) return // уже слушаем (повторный вызов после рестарта сервиса)
        val app = context.applicationContext
        val manager = app.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            EventLog.append("net_mon:fail:no_cm")
            return
        }
        appContext = app
        // Базовый замер с нуля: registerDefaultNetworkCallback сразу отдаёт
        // текущее состояние, поэтому на старте «смены» не будет — как на iOS,
        // где первый pathUpdateHandler только запоминает lastPathSig.
        lastSig = null
        lastNotifAt = 0L
        try {
            manager.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            EventLog.append("net_mon:fail:${e.javaClass.simpleName}:${e.message}")
            appContext = null
            return
        }
        cm = manager
    }

    /// Отписка. Вызывается во ВСЕХ путях остановки туннеля: пока туннель
    /// выключен, «сеть сменилась» никого не касается, а живая подписка
    /// удерживала бы сервис от выхода.
    fun stop() {
        val manager = cm ?: return
        cm = null
        appContext = null
        runCatching { manager.unregisterNetworkCallback(callback) }
        // Базовую подпись сбрасываем: следующий start не должен писать
        // net_change от состояния, оставшегося с прошлой сессии.
        lastSig = null
        lastNotifAt = 0L
        // alertAt/lastChangeDesc НЕ трогаем: это память о смене сети во время
        // последней сессии. iOS-версия показывает такой алерт по возвращении
        // в приложение даже после выключения туннеля; снимает его UI.
    }

    /// Снять in-app алерт (UI вызвал его после показа баннера).
    fun clearAlert() {
        alertAt = 0L
    }

    /// Колбэк приходит с binder-потока системы. EventLog синхронизирован, а
    /// запись Compose-состояния из бэкграунд-потока для этого проекта штатная
    /// (TunnelManager.updateRunning делается так же из wsb-start/wsb-tun).
    private fun onChanged() {
        val manager = cm ?: return
        val cur = signature(manager)
        val prev = lastSig
        lastSig = cur
        if (prev == null || prev == cur) return // базовый замер / ничего не изменилось

        // Журнал — ВСЕГДА, включая уход в offline (паритет с iOS).
        EventLog.append("net_change:$prev->$cur")
        lastChangeDesc = describe(prev, cur)

        // In-app алерт ставим на любую смену: iOS build 83 показывает баннер по
        // любой строке net_change:, и пользователь должен знать, что туннель
        // остался на старой сети. Системный нудж — только когда новый путь
        // реально рабочий (guard по path.status == .satisfied на iOS).
        alertAt = System.currentTimeMillis()
        if (!cur.startsWith("online:")) return
        val now = SystemClock.elapsedRealtime()
        if (lastNotifAt != 0L && now - lastNotifAt < NOTIF_THROTTLE_MS) return
        lastNotifAt = now
        val app = appContext ?: return
        NotificationHelper.postNetChange(app, lastChangeDesc)
    }

    /// Подпись текущего состояния: «online:WIFI», «offline:none».
    /// Статус — аналог `path.status` на iOS: online = сеть прошла проверку
    /// (VALIDATED), unvalidated = интернет-капабилити есть, проверки нет,
    /// offline = ни активной сети, ни интернет-капабилити.
    private fun signature(manager: ConnectivityManager): String {
        val caps = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
            ?: return "offline:none"
        val status = when {
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> "online"
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "unvalidated"
            else -> "offline"
        }
        val keys = transportKeys(caps).joinToString("+").ifEmpty { "none" }
        return "$status:$keys"
    }

    /// Транспорты сети — ключами, как «WIFI+CELLULAR» в iOS-подписи.
    /// Свои собственные WS-соединения исключены из туннеля
    /// (addDisallowedApplication), поэтому сеть по умолчанию у процесса — та
    /// самая физическая сеть, а не наш VPN-интерфейс: смена Wi-Fi ↔ соты видна.
    private fun transportKeys(caps: NetworkCapabilities): List<String> {
        val out = ArrayList<String>(2)
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) out.add("WIFI")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) out.add("CELLULAR")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) out.add("ETHERNET")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) out.add("VPN")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) out.add("BLUETOOTH")
        // TRANSPORT_USB есть только с API 31 — проверка версии нужна линтеру.
        if (Build.VERSION.SDK_INT >= 31 && caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)) {
            out.add("USB")
        }
        return out.sorted()
    }

    private val transportLabels = mapOf(
        "WIFI" to "Wi-Fi",
        "CELLULAR" to "сотовая сеть",
        "ETHERNET" to "Ethernet",
        "VPN" to "VPN",
        "BLUETOOTH" to "Bluetooth",
        "USB" to "USB",
        "none" to "нет сети",
    )
    private val statusLabels = mapOf(
        "online" to "онлайн",
        "unvalidated" to "без проверки",
        "offline" to "нет сети",
    )

    /// «Wi-Fi → сотовая сеть» для UI и текста нуджа. Если транспорт тот же
    /// (например, Wi-Fi дошёл до VALIDATED), показываем переход статуса —
    /// иначе получилось бы бессмысленное «Wi-Fi → Wi-Fi».
    private fun describe(prev: String, cur: String): String {
        val prevKeys = prev.substringAfter(':')
        val curKeys = cur.substringAfter(':')
        if (prevKeys == curKeys) {
            val was = statusLabels[prev.substringBefore(':')] ?: prev
            val now = statusLabels[cur.substringBefore(':')] ?: cur
            return "${transportNames(curKeys)}: $was → $now"
        }
        return "${transportNames(prevKeys)} → ${transportNames(curKeys)}"
    }

    private fun transportNames(keys: String): String =
        keys.split('+').joinToString("+") { transportLabels[it] ?: it.lowercase() }
}
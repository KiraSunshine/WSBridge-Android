package com.l1ratch.wsbridge.tunnel

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.l1ratch.wsbridge.MainActivity
import com.l1ratch.wsbridge.R
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/// Нуджи туннеля — порт `scheduleLongSessionNudge()` / `cancelLongSessionNudge()`
/// и блоков `UNNotificationRequest` из PacketTunnelProvider.swift (iOS build 82/86).
///
/// iOS использует две нотификации с идентификаторами `wsbridge.netChange` и
/// `wsbridge.longSession`; здесь это ID каналов и ID уведомлений. Одинаковый ID
/// для повторных публикаций — не мелочь: новая смена сети ЗАМЕНЯЕТ прошлую в
/// шторке (как повторный `add` с тем же identifier на iOS), а не копит стопку.
///
/// Владелец нуджей — процесс-владелец туннеля (на iOS расширение, у нас
/// сервис): пока туннель включён, процесс жив; когда туннель выключен,
/// [cancelLongSession] снимает и запланированный таймер, и уже показанное
/// уведомление — тот самый анти-спам фикс iOS build 86.
object NotificationHelper {

    /// Каналы названы по идентификаторам iOS — так проще сверять поведение двух
    /// клиентов по журналу, а не по названиям.
    private const val CHANNEL_NET = "wsbridge.netChange"
    private const val CHANNEL_LONG = "wsbridge.longSession"

    private const val NOTIF_ID_NET = 2
    private const val NOTIF_ID_LONG = 3

    /// 6 часов — как `timeInterval: 6 * 3600` в iOS.
    private const val LONG_SESSION_DELAY_MS = 6 * 60 * 60 * 1000L

    /// ponytail: таймер «долго включён» живёт в процессе, а не в AlarmManager.
    /// На iOS триггер держит система, поэтому он переживает смерть расширения —
    /// но на Android смерть процесса означает смерть VpnService, то есть
    /// туннель выключен, и нудж «туннель давно включён» после этого был бы
    /// ровно тем спамом, который чинил iOS build 86. In-process таймер этот
    /// класс спама закрывает конструктивно: он не может пережить туннель.
    /// AlarmManager понадобится только если нудж должен доживать до конца
    /// процесса — тогда же придётся объявлять ресивер в манифесте.
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "wsb-nudge").apply { isDaemon = true }
        }

    @Volatile private var longSessionFuture: ScheduledFuture<*>? = null

    /// Каналы обязаны существовать ДО публикации; на API 26+ без канала
    /// уведомление молча не показывается. Вызывается сервисом на старте
    /// туннеля, а также из путей публикации — потому что нудж 6ч может
    /// сработать уже после того, как система перезапустила процесс.
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NET,
                "Смена сети",
                // DEFAULT, а не LOW: это просьба перезапустить туннель, но и не
                // HIGH — нудж не должен звонить поверх того, что делает человек.
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Телефон переключился на другую сеть — туннель мог перестать работать"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LONG,
                "Долгая сессия",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Напоминание перезапустить туннель после 6 часов работы"
            }
        )
    }

    /// «Сеть сменилась» — порт блока `wsbridge.netChange`: троттлинг 5 мин и
    /// выбор момента живут в [NetworkMonitor], здесь только публикация.
    /// `desc` — человекочитаемая смена («Wi-Fi → сотовая сеть»).
    fun postNetChange(context: Context, desc: String) {
        val app = context.applicationContext
        if (!canPost(app)) {
            // Android 13+ без разрешения: notify() — тихий no-op. Пишем в
            // журнал, иначе в диагностике будет пробел «нудж не пришёл».
            EventLog.append("notif_net:denied")
            return
        }
        ensureChannels(app)
        val n = NotificationCompat.Builder(app, CHANNEL_NET)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Сеть сменилась")
            .setContentText(
                if (desc.isEmpty()) {
                    "Телефон переподключился к другой сети. Если интернет тормозит — перезапусти туннель в WSBridge."
                } else {
                    "$desc. Если интернет тормозит — перезапусти туннель в WSBridge."
                }
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    if (desc.isEmpty()) {
                        "Телефон переподключился к другой сети. Если интернет тормозит — перезапусти туннель в WSBridge."
                    } else {
                        "$desc. Если интернет тормозит — перезапусти туннель в WSBridge."
                    }
                )
            )
            .setContentIntent(openApp(app, NOTIF_ID_NET))
            .setAutoCancel(true)
            // Замена прошлого нуджа о смене сети, а не вторая строка в шторке —
            // это делает константный ID [NOTIF_ID_NET]: ключ уведомления у
            // системы — (пакет, ID, тег), второй записи с тем же ключом не
            // бывает, новая строка просто встаёт на место прошлой.
            //
            // `setOnlyAlertOnce` здесь СОЗНАТЕЛЬНО НЕ ставится. Его контракт:
            // «оповестить (звук, вибро, баннер) только один раз, при первом
            // показе; при обновлении уведомление не оповещает». С ним внимание
            // привлекал ровно ПЕРВЫЙ нудж за сессию: дальше тот же ID считался
            // обновлением уже показанного, и нуджи молча правили текст в шторке.
            // Без флага каждое обновление снова оповещает — это и есть паритет с
            // iOS, где новый `UNNotificationRequest` с тем же identifier
            // доставляется заново, со звуком.
            //
            // ponytail: отмену прошлой записи (cancel + notify) сюда не
            // добавляю. Она «гарантированнее» только на кривых прошивках, но
            // ценой мигания строки в шторке — а 5 минут троттлинга в
            // [NetworkMonitor] и так не дают нуджам идти чаще одного раза за
            // окно троттлинга.
            .build()
        post(app, NOTIF_ID_NET, n, "notif_net")
    }

    /// Планирует нудж «туннель включён давно» — одноразово через 6 часов.
    /// Вызывается при старте туннеля (паритет с `scheduleLongSessionNudge()`).
    fun scheduleLongSession(context: Context) {
        val app = context.applicationContext
        // Идемпотентность: перезапуск туннеля не должен оставить два таймера, а
        // висящее в шторке напоминание прошлой сессии — не должно пережить её.
        cancelLongSession(app)
        ensureChannels(app)
        longSessionFuture = scheduler.schedule(
            { postLongSession(app) },
            LONG_SESSION_DELAY_MS,
            TimeUnit.MILLISECONDS,
        )
        EventLog.append("notif_long:sched:6h")
    }

    /// Снимает нудж 6ч целиком: и запланированный таймер, и уже показанное
    /// уведомление. Вызывается в ОБОИХ путях остановки туннеля — это и есть
    /// анти-спам фикс (`cancelLongSessionNudge()` на iOS): напоминание не
    /// должно дожить до момента, когда туннель уже выключен.
    fun cancelLongSession(context: Context) {
        longSessionFuture?.cancel(false)
        longSessionFuture = null
        // cancel() доходит и до показанного уведомления: ID константный, поэтому
        // снять его можно даже из нового процесса (NotificationManager знает
        // уведомления своего пакета).
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID_LONG) }
        EventLog.append("notif_long:cancel")
    }

    /// Публикация по таймеру. Каналы проверяем здесь, а не только на старте
    /// сервиса: между стартом и +6ч система могла пересоздать процесс.
    private fun postLongSession(app: Context) {
        if (!canPost(app)) {
            EventLog.append("notif_long:denied")
            return
        }
        ensureChannels(app)
        val n = NotificationCompat.Builder(app, CHANNEL_LONG)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Туннель включен давно")
            .setContentText("Больше 6 часов без перезапуска. Если интернет тормозит — перезапусти туннель в WSBridge.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Больше 6 часов без перезапуска. Если интернет тормозит — перезапусти туннель в WSBridge."
                )
            )
            .setContentIntent(openApp(app, NOTIF_ID_LONG))
            .setAutoCancel(true)
            .build()
        post(app, NOTIF_ID_LONG, n, "notif_long")
    }

    /// Проверка разрешения до notify(): на API 33+ без POST_NOTIFICATIONS
    /// вызов — тихий no-op, поэтому спрашиваем сами и фиксируем отказ в
    /// журнале. До 33 разрешение не нужно вовсе.
    /// Проверка разрешения до notify(): на API 33+ без POST_NOTIFICATIONS
    /// вызов — тихий no-op, поэтому спрашиваем сами и фиксируем отказ в
    /// журнале. До 33 разрешение не нужно вовсе.
    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /// Публикация нуджа.
    ///
    /// @SuppressLint: lint требует проверять разрешение прямо перед notify(),
    /// но проверка уже сделана в canPost() — каждый вызов post() идёт после
    /// неё. Иначе lint-ошибка уровня error заблокирует сборку.
    @SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, n: Notification, tag: String) {
        try {
            NotificationManagerCompat.from(context).notify(id, n)
            EventLog.append("$tag:ok")
        } catch (e: Exception) {
            // Публикация уведомления не стоит падения сервиса и туннеля.
            EventLog.append("$tag:${e.javaClass.simpleName}:${e.message}")
        }
    }

    /// Тап по нуджу открывает приложение — как переход в WSBridge по iOS-нотификации.
    private fun openApp(context: Context, requestCode: Int): PendingIntent? =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
package com.l1ratch.wsbridge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.l1ratch.wsbridge.tunnel.StatsStore
import kotlinx.coroutines.delay
import java.util.Locale

/// Экран статистики (порт StatsView, iOS build 91-94): график трафика по
/// дням, аптайм и включения за выбранное окно.
///
/// Палитру дублируем локально (Green/Orange из MainActivity private):
/// графике нужны те же акценты, а таскать сквозной файл темы ради двух
/// прямоугольников — оверкилл.
private val ChartDown = Color(0xFF4ADE80) // к клиенту: mint = «скачивание»
private val ChartUp = Color(0xFFF59E0B)   // от клиента: тёплый = «отдаём»

/// Байты в человекочитаемом виде. Locale.US принудительно: "%.1f" в
/// ru-локали даёт запятую, и «1,5 ГБ» в UI выглядит опечаткой на фоне
/// целочисленных подписей.
internal fun formatBytes(b: Long): String {
    if (b < 1024) return "$b Б"
    val kb = b / 1024.0
    if (kb < 1024) return if (kb >= 100) "${kb.toInt()} КБ" else "%.1f КБ".format(Locale.US, kb)
    val mb = kb / 1024.0
    if (mb < 1024) return if (mb >= 100) "${mb.toInt()} МБ" else "%.1f МБ".format(Locale.US, mb)
    val gb = mb / 1024.0
    return if (gb >= 100) "${gb.toInt()} ГБ" else "%.1f ГБ".format(Locale.US, gb)
}

/// Аптайм: «5 ч 32 м», «48 м», «12 с». Минуты обрезаются — для дневной
/// статистики секундная точность шумит.
internal fun formatDuration(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return when {
        h > 0 -> "${h} ч ${m} м"
        m > 0 -> "$m м"
        else -> "$sec с"
    }
}

/// Человеко-ключ дня «2026-10-24» → «24.10»: в подписях оси год не нужен,
/// окно максимум 90 дней.
private fun dayLabel(key: String): String {
    val parts = key.split("-")
    if (parts.size != 3) return key
    return "${parts[2]}.${parts[1]}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatsScreen(navigate: (String) -> Unit) {
    /// 0 = «Все» (как All на iOS): окно не режем, показываем что накопилось.
    var rangeDays by remember { mutableStateOf(7) }
    var history by remember { mutableStateOf(StatsStore.history()) }

    // Живой опрос, как в журнале: стор обновляется сервисом каждые 15с,
    // тянем раз в 2с — дёшево и без колбэков (один процесс).
    LaunchedEffect(Unit) {
        while (true) {
            history = StatsStore.history()
            delay(2000)
        }
    }

    // Окно — по ДНЯМ ДАННЫХ, не по календарю: график и итоги всегда
    // согласованы (totals(daysBack) считал бы календарное окно и разошёлся
    // бы с последними N записями).
    val visible = if (rangeDays == 0) history else history.takeLast(rangeDays)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Статистика") },
                navigationIcon = {
                    IconButton(onClick = { navigate("main") }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((days, label) in listOf(7 to "7 дн", 30 to "30 дн", 90 to "90 дн", 0 to "Все")) {
                    FilterChip(
                        selected = rangeDays == days,
                        onClick = { rangeDays = days },
                        label = { Text(label) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            if (visible.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Данных пока нет.\nВключи туннель — статистика копится сама, по дням.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                return@Column
            }

            TrafficChart(visible)
            Spacer(Modifier.height(4.dp))
            // Подписи оси: только края — на 90 дней подпись каждого дня
            // превращается в кашу.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(dayLabel(visible.first().first), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
                Text(dayLabel(visible.last().first), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
            }
            Spacer(Modifier.height(16.dp))

            // Итоги окна — из тех же записей, что на графике.
            val up = visible.sumOf { it.second.upBytes }
            val down = visible.sumOf { it.second.downBytes }
            val secs = visible.sumOf { it.second.seconds }
            val ses = visible.sumOf { it.second.sessions }
            Text(
                "↑ ${formatBytes(up)} · ↓ ${formatBytes(down)} · аптайм ${formatDuration(secs)} · включений $ses",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(ChartUp))
                Text(" от клиента (загрузка)", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Spacer(Modifier.size(12.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(ChartDown))
                Text(" к клиенту (скачивание)", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    }
}

/// Столбчатый график «пара баров на день» на чистом Canvas — без
/// библиотек графиков: данных максимум 90 точек, а зависимость ради этого
/// раздувать APK незачем. Масштаб — по максимуму ОКНА (не всей истории):
/// иначе один гигабайтный день расплющивал бы недельный обзор в ноль.
@Composable
private fun TrafficChart(days: List<Pair<String, StatsStore.DayUsage>>) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
    ) {
        val maxVal = days.maxOf { maxOf(it.second.upBytes, it.second.downBytes) }
            .coerceAtLeast(1L)
        val n = days.size
        // Слот дня делится на [gap|up|gap|down|gap]: на широких окнах бары
        // худеют сами, минимальная ширина 2px не даёт им исчезнуть вовсе.
        val slot = size.width / n
        val barW = (slot * 0.32f).coerceAtLeast(2f)
        val gap = ((slot - 2f * barW) / 3f).coerceAtLeast(0f)
        val chartH = size.height * 0.94f // снизу 6% — под базовую линию
        days.forEachIndexed { i, (_, d) ->
            val x = i * slot
            val hUp = chartH * (d.upBytes.toFloat() / maxVal)
            val hDown = chartH * (d.downBytes.toFloat() / maxVal)
            // Столбцы растут от базовой линии вверх.
            if (hUp > 0f) {
                drawRect(ChartUp, topLeft = Offset(x + gap, size.height - hUp), size = Size(barW, hUp))
            }
            if (hDown > 0f) {
                drawRect(ChartDown, topLeft = Offset(x + gap + barW + gap, size.height - hDown), size = Size(barW, hDown))
            }
        }
        // Базовая линия: без неё столбцы «висят в воздухе».
        drawRect(
            Color.White.copy(alpha = 0.15f),
            topLeft = Offset(0f, size.height - 1f),
            size = Size(size.width, 1.5f),
        )
    }
}
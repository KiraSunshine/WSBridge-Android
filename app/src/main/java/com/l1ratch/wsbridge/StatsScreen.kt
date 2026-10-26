package com.l1ratch.wsbridge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.l1ratch.wsbridge.tunnel.StatsStore
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/// Экран статистики (порт StatsView, iOS build 91-94): график трафика по
/// дням, аптайм и включения за выбранное окно.
///
/// Палитру дублируем локально (Green/Orange из MainActivity private):
/// графике нужны те же акценты, а таскать сквозной файл темы ради двух
/// прямоугольников — оверкилл.
private val ChartDown = Color(0xFF4ADE80) // к клиенту: mint = «скачивание»
private val ChartUp = Color(0xFFF59E0B)   // от клиента: тёплый = «отдаём»

private val DayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

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

private fun todayKey(): String = DayKeyFormat.format(Calendar.getInstance().time)

private fun startOfToday(): Calendar = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}

private fun parseDay(key: String): Calendar? {
    val parts = key.split("-")
    if (parts.size != 3) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    val day = parts[2].toIntOrNull() ?: return null
    return Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day)
    }
}

/// Окно всегда календарное: дни без трафика остаются на графике нулями.
/// Иначе два соседних столбика могли оказаться с разницей в неделю.
private fun fillWindow(
    history: List<Pair<String, StatsStore.DayUsage>>,
    rangeDays: Int,
): List<Pair<String, StatsStore.DayUsage>> {
    if (history.isEmpty()) return emptyList()
    val byDay = history.associate { it.first to it.second }
    val end = startOfToday()
    val start = if (rangeDays > 0) {
        (end.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -(rangeDays - 1)) }
    } else {
        parseDay(history.first().first) ?: (end.clone() as Calendar)
    }

    val out = ArrayList<Pair<String, StatsStore.DayUsage>>()
    val cursor = start.clone() as Calendar
    while (!cursor.after(end)) {
        val key = DayKeyFormat.format(cursor.time)
        out += key to (byDay[key] ?: StatsStore.DayUsage())
        cursor.add(Calendar.DAY_OF_YEAR, 1)
    }
    return out
}

/// Верхняя граница шкалы — «круглое» число (1/2/5 × 10^n), чтобы подписи
/// были вида 100 МБ / 1 ГБ, а не 973,4 МБ.
private fun niceMax(value: Long): Long {
    if (value <= 0L) return 1L
    var step = 1L
    while (step <= value / 10L) step *= 10L
    val fraction = value.toDouble() / step
    val nice = when {
        fraction <= 1.0 -> 1L
        fraction <= 2.0 -> 2L
        fraction <= 5.0 -> 5L
        else -> 10L
    }
    return nice * step
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatsScreen(navigate: (String) -> Unit) {
    /// 0 = «Все» (как All на iOS): окно не режем, показываем что накопилось.
    var rangeDays by remember { mutableStateOf(7) }
    var history by remember { mutableStateOf(StatsStore.history()) }
    /// Выбранный день храним ключом, а не индексом: при смене окна индекс
    /// указывал бы на другой день.
    var selectedKey by remember { mutableStateOf<String?>(null) }

    // Живой опрос, как в журнале: стор обновляется сервисом каждые 15с,
    // тянем раз в 2с — дёшево и без колбэков (один процесс).
    LaunchedEffect(Unit) {
        while (true) {
            history = StatsStore.history()
            delay(2000)
        }
    }

    // Новое окно — новый выбор: показываем последний день периода.
    LaunchedEffect(rangeDays) { selectedKey = null }

    val visible = if (history.isEmpty()) emptyList() else fillWindow(history, rangeDays)
    val hasData = visible.any {
        val d = it.second
        d.upBytes > 0 || d.downBytes > 0 || d.seconds > 0 || d.sessions > 0
    }

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

            if (!hasData) {
                Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (history.isEmpty()) {
                            "Данных пока нет.\nВключи туннель — статистика копится сама, по дням."
                        } else {
                            "За выбранный период данных нет.\nПопробуй другой диапазон."
                        },
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                return@Column
            }

            val selectedIndex = visible
                .indexOfFirst { it.first == selectedKey }
                .let { if (it >= 0) it else visible.lastIndex }
            val selected = visible[selectedIndex]

            TrafficChart(
                days = visible,
                selectedIndex = selectedIndex,
                onSelect = { selectedKey = visible[it].first },
            )
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(ChartDown))
                Text(" скачано", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Spacer(Modifier.width(14.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(ChartUp))
                Text(" отправлено", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "Высота столбика — весь трафик за день. Нажми на день, чтобы увидеть точные значения.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
            )
            Spacer(Modifier.height(12.dp))

            SelectedDayCard(selected.first, selected.second)

            Spacer(Modifier.height(18.dp))
            Text(
                "Итого за период",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            val up = visible.sumOf { it.second.upBytes }
            val down = visible.sumOf { it.second.downBytes }
            val secs = visible.sumOf { it.second.seconds }
            val ses = visible.sumOf { it.second.sessions }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryTile("Скачано", formatBytes(down), ChartDown, Modifier.weight(1f))
                SummaryTile("Отправлено", formatBytes(up), ChartUp, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryTile(
                    "Аптайм",
                    formatDuration(secs),
                    MaterialTheme.colorScheme.onSurface,
                    Modifier.weight(1f),
                )
                SummaryTile(
                    "Включений",
                    ses.toString(),
                    MaterialTheme.colorScheme.onSurface,
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SelectedDayCard(key: String, day: StatsStore.DayUsage) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            dayLabel(key) + if (key == todayKey()) " · сегодня" else "",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "↓ ${formatBytes(day.downBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = ChartDown,
            )
            Text(
                "↑ ${formatBytes(day.upBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = ChartUp,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "аптайм ${formatDuration(day.seconds)} · включений ${day.sessions}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun SummaryTile(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
        )
    }
}

/// Столбчатый график: один составной столбик на день. Низ — скачано,
/// верх — отправлено, общая высота — весь трафик. Масштаб по максимуму
/// ОКНА, подписи дат — с шагом, который ещё читается на телефоне.
@Composable
private fun TrafficChart(
    days: List<Pair<String, StatsStore.DayUsage>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
    )
    val selectedLabelStyle = TextStyle(
        color = MaterialTheme.colorScheme.primary,
        fontSize = MaterialTheme.typography.labelSmall.fontSize,
        fontWeight = FontWeight.SemiBold,
    )
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val baselineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    val maxVal = niceMax(days.maxOf { it.second.downBytes + it.second.upBytes })
    val rightPadDp = 4.dp

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(240.dp)
            .pointerInput(days, selectedIndex) {
                detectTapGestures { pos ->
                    val left = 52.dp.toPx()
                    val right = rightPadDp.toPx()
                    val chartWidth = size.width - left - right
                    if (chartWidth <= 0f || pos.x < left || pos.x > size.width - right) {
                        return@detectTapGestures
                    }
                    val slot = chartWidth / days.size
                    val index = ((pos.x - left) / slot).toInt().coerceIn(0, days.lastIndex)
                    onSelect(index)
                }
            }
    ) {
        val left = 52.dp.toPx()
        val right = size.width - rightPadDp.toPx()
        val chartWidth = right - left
        val top = 14.dp.toPx()
        val bottom = size.height - 26.dp.toPx()
        val chartHeight = bottom - top
        if (chartWidth <= 0f || chartHeight <= 0f) return@Canvas

        val slot = chartWidth / days.size
        val barWidth = (slot * 0.62f).coerceAtLeast(1.5f).coerceAtMost(24.dp.toPx())

        // Подсветка выбранного дня — под столбиками, чтобы не перекрывать их.
        if (selectedIndex in days.indices) {
            drawRect(
                selectionColor,
                topLeft = Offset(left + selectedIndex * slot, top),
                size = Size(slot, chartHeight),
            )
        }

        // Сетка и подписи шкалы Y: 0, половина, максимум.
        for (i in 0..2) {
            val fraction = i / 2f
            val y = bottom - chartHeight * fraction
            drawLine(
                if (i == 0) baselineColor else gridColor,
                start = Offset(left, y),
                end = Offset(right, y),
                strokeWidth = if (i == 0) 1.5.dp.toPx() else 1.dp.toPx(),
            )
            val value = when (i) {
                0 -> 0L
                1 -> maxVal / 2
                else -> maxVal
            }
            val layout = textMeasurer.measure(formatBytes(value), labelStyle)
            drawText(
                layout,
                topLeft = Offset(
                    (left - 8.dp.toPx() - layout.size.width).coerceAtLeast(0f),
                    (y - layout.size.height / 2f).coerceAtLeast(0f),
                ),
            )
        }

        days.forEachIndexed { i, (_, day) ->
            val x = left + i * slot + (slot - barWidth) / 2f
            val downHeight = chartHeight * (day.downBytes.toFloat() / maxVal)
            val upHeight = chartHeight * (day.upBytes.toFloat() / maxVal)
            if (downHeight > 0f) {
                drawRect(
                    ChartDown,
                    topLeft = Offset(x, bottom - downHeight),
                    size = Size(barWidth, downHeight),
                )
            }
            if (upHeight > 0f) {
                drawRect(
                    ChartUp,
                    topLeft = Offset(x, bottom - downHeight - upHeight),
                    size = Size(barWidth, upHeight),
                )
            }
            if (downHeight <= 0f && upHeight <= 0f) {
                // День без трафика: тонкая засечка на базовой линии, чтобы
                // пустой день не выглядел как отсутствующий на графике.
                drawRect(
                    gridColor.copy(alpha = 0.22f),
                    topLeft = Offset(x, bottom - 1.dp.toPx()),
                    size = Size(barWidth, 1.dp.toPx()),
                )
            }
        }

        // Подписи дат: 7 дней — все, 30 — каждые 5, 90 — каждые 15.
        val step = when {
            days.size <= 7 -> 1
            days.size <= 31 -> 5
            days.size <= 92 -> 15
            else -> 30
        }
        val labels = buildSet {
            var i = 0
            while (i < days.size) {
                add(i)
                i += step
            }
            add(days.lastIndex)
        }
        for (i in labels) {
            val layout = textMeasurer.measure(
                dayLabel(days[i].first),
                if (i == selectedIndex) selectedLabelStyle else labelStyle,
            )
            val center = left + i * slot + slot / 2f
            val maxX = (right - layout.size.width).coerceAtLeast(left)
            drawText(
                layout,
                topLeft = Offset(
                    (center - layout.size.width / 2f).coerceIn(left, maxX),
                    bottom + 6.dp.toPx(),
                ),
            )
        }
    }
}

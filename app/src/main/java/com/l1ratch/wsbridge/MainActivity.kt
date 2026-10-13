package com.l1ratch.wsbridge

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Brush.Companion.radialGradient
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.l1ratch.wsbridge.tunnel.CFDomains
import com.l1ratch.wsbridge.tunnel.FrontsUpdater
import com.l1ratch.wsbridge.tunnel.TunnelService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/// Главный экран: молния-кнопка (порт ContentView.swift) + меню.
class MainActivity : ComponentActivity() {

    private val startTunnel = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Consent получен (или уже был) — запускаем сервис.
        launchTunnel()
    }
    private fun launchTunnel() {
        val intent = Intent(this, TunnelService::class.java).setAction(TunnelService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        // Авто-чек фронтов ПОСЛЕ startService (не до — гонка с записью настроек
        // во время старта). Rate-limit 30 мин; применяется при следующем
        // включении, не в текущей сессии.
        if (FrontsUpdater.shouldAutoUpdate()) {
            scope.launch {
                FrontsUpdater.update()
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private fun stopTunnel() {
        startService(Intent(this, TunnelService::class.java).setAction(TunnelService.ACTION_STOP))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TunnelManager.init(this)
        FrontsUpdater.init(this)
        setContent { App(onToggle = ::toggle) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun toggle() {
        if (TunnelManager.running) {
            stopTunnel()
            return
        }
        // VpnService.prepare: null = согласие уже есть, иначе системный диалог.
        val consent = VpnService.prepare(this)
        if (consent == null) launchTunnel() else startTunnel.launch(consent)
    }
}

private val Green = Color(0xFF17A05E)
private val Indigo = Color(0xFF1E3A6E)
private val BottomDark = Color(0xFF040810)
private val DarkBg = Color(0xFF0D1526)

/// Радиус Brush.radialGradient задаётся в пикселях, а не в dp.
@Composable
private fun px(dp: Dp): Float = with(LocalDensity.current) { dp.toPx() }

@Composable
fun App(onToggle: () -> Unit) {
    // Только тёмная тема (как на iOS): светлую отключили — автоадаптация
    // давала блёклые экраны, а градиенты под светлую ломали контраст.
    MaterialTheme(colorScheme = darkColorScheme(background = DarkBg, surface = DarkBg)) {
        var screen by remember { mutableStateOf<Any>("main") }
        when (screen) {
            "main" -> MainScreen(onToggle = onToggle, navigate = { screen = it })
            "dnsManage" -> DnsManageScreen(navigate = { screen = it })
            "worker" -> WorkerScreen(navigate = { screen = it })
            "journal" -> JournalScreen(navigate = { screen = it })
            "about" -> AboutScreen(navigate = { screen = it })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(onToggle: () -> Unit, navigate: (String) -> Unit) {
    val running = TunnelManager.running
    var showMenu by remember { mutableStateOf(false) }
    var showDns by remember { mutableStateOf(false) }
    // Ручное обновление фронтов: null = не идёт, "run" = качает, "ok:N"/"err" = итог.
    var frontsStatus by remember { mutableStateOf<String?>(null) }

    // Клик меню ставит "run" → этот эффект один раз скачивает и пишет итог.
    LaunchedEffect(frontsStatus) {
        when (frontsStatus) {
            "run" -> {
                val ok = FrontsUpdater.update()
                frontsStatus = if (ok) "ok:${CFDomains.size}" else "err"
            }
            "err" -> { delay(3500); frontsStatus = null }
            else -> if (frontsStatus?.startsWith("ok") == true) {
                delay(3500); frontsStatus = null
            }
        }
    }

    // Фон рисуем явно: Scaffold прозрачный (иначе перекроет свечения), а окно
    // в XML-теме чёрное — без этой заливки светлая тема давала чёрный экран.
    // Тема всегда тёмная (см. App).
    Box(Modifier.fillMaxSize().background(DarkBg)) {
        // Верхнее свечение (порт ContentView.swift): зелёное при работе, индиго в покое.
        // Радиальный градиент вместо Modifier.blur — тот же вид, без дорогого размытия.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .size(560.dp)
                .offset(y = (-240).dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            (if (running) Green else Indigo).copy(alpha = 0.55f),
                            Color.Transparent,
                        ),
                        radius = px(300.dp),
                    )
                )
        )
        // Нижняя глубина: почти чёрный, держит низ экрана спокойным.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .size(700.dp)
                .offset(y = 220.dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            BottomDark.copy(alpha = 0.85f),
                            Color.Transparent,
                        ),
                        radius = px(340.dp),
                    )
                )
        )

        Scaffold(
            containerColor = Color.Transparent,
            // contentColorFor(Transparent) не находит пару и отдаёт Unspecified,
            // тогда LocalContentColor остаётся дефолтным чёрным — и в тёмной теме
            // подпись DNS и иконка рисуются чёрным по тёмному. Задаём явно.
            contentColor = MaterialTheme.colorScheme.onBackground,
            topBar = {
                TopAppBar(
                    title = { },
                    actions = {
                        // Меню якоряется на коробке вокруг кнопки, иначе popup
                        // открывается от левого края, а не под кнопкой.
                        // Пузырь-кнопка (FilledIconButton) вместо голых «⋮»:
                        // три точки были еле заметны на градиенте.
                        Box {
                            FilledIconButton(
                                onClick = { showMenu = true },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = "Меню",
                                    modifier = Modifier.size(26.dp),
                                )
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Журнал туннеля") },
                                    leadingIcon = { Icon(Icons.Default.Info, null) },
                                    onClick = { showMenu = false; navigate("journal") },
                                )
                                DropdownMenuItem(
                                    text = { Text("DNS-серверы") },
                                    leadingIcon = { Icon(Icons.Default.NetworkWifi, null) },
                                    onClick = { showMenu = false; navigate("dnsManage") },
                                )
                                DropdownMenuItem(
                                    text = { Text("CF Worker") },
                                    leadingIcon = { Icon(Icons.Default.Cloud, null) },
                                    onClick = { showMenu = false; navigate("worker") },
                                )
                                DropdownMenuItem(
                                    text = { Text("Обновить фронты (${CFDomains.size})") },
                                    leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                    onClick = {
                                        showMenu = false
                                        if (frontsStatus == null) frontsStatus = "run"
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("О программе") },
                                    leadingIcon = { Icon(Icons.Default.Info, null) },
                                    onClick = { showMenu = false; navigate("about") },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    BoltButton(running, onToggle)
                    Spacer(Modifier.height(20.dp))
                    DnsPill { showDns = true }
                }

                // Версия — по нижнему краю, как на iOS.
                Text(
                    "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · © 2026 l1ratch",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                )
            }
        }
    }

    if (showDns) DnsSheet(onDismiss = { showDns = false }, navigate = navigate)

    // Статус обновления фронтов: поверх всего, автоскрытие — в LaunchedEffect.
    frontsStatus?.let { st ->
        val msg = when {
            st == "run" -> "Обновление списка фронтов…"
            st == "err" -> "Не удалось обновить (сеть?). Встроенный список активен."
            st.startsWith("ok") -> "Фронты обновлены: ${st.removePrefix("ok:")} доменов"
            else -> st
        }
        Snackbar { Text(msg) }
    }
}

/// Молния — и индикатор, и кнопка. Ореол сделан радиальным градиентом, а не
/// shadow(): тень от CircleShape на части устройств рисуется многоугольником,
/// и кнопка выглядит как обрезанный октагон.
@Composable
private fun BoltButton(running: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .size(240.dp)
            .background(
                Brush.radialGradient(
                    listOf(
                        // В покое — приглушённый индиго (не серый: серый читался
                        // как «сломано»), при работе — зелёное свечение.
                        (if (running) Green else Indigo).copy(alpha = if (running) 0.35f else 0.16f),
                        Color.Transparent,
                    ),
                    radius = px(120.dp),
                )
            )
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Bolt,
            contentDescription = if (running) "Выключить туннель" else "Включить туннель",
            tint = if (running) Green else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.size(170.dp),
        )
    }
}

@Composable
private fun DnsPill(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.NetworkWifi, null, Modifier.size(20.dp))
        Spacer(Modifier.size(10.dp))
        Text(TunnelManager.selectedDNS.name, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.size(10.dp))
        Box(
            Modifier
                .size(10.dp)
                .background(
                    if (TunnelManager.selectedDNS.servers.isEmpty()) Color.Gray else Green,
                    CircleShape
                )
        )
    }
}

/// Быстрый выбор DNS (лист поверх главного экрана).
@Composable
private fun DnsSheet(onDismiss: () -> Unit, navigate: (String) -> Unit) {
    var selected by remember { mutableStateOf(TunnelManager.selectedDNSId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        title = { Text("DNS") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Изменения применятся при следующем включении туннеля.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                for (config in TunnelManager.allDNS) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { selected = config.id; TunnelManager.selectedDNSId = config.id }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(config.name)
                            Text(
                                config.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                        if (selected == config.id) {
                            Icon(Icons.Default.CheckCircle, null, tint = Green)
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                TextButton(onClick = { onDismiss(); navigate("dnsManage") }) {
                    Text("Настройки DNS")
                }
            }
        },
    )
}

/// Управление DNS: пресеты (только просмотр) + свои (добавить/удалить).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsManageScreen(navigate: (String) -> Unit) {
    var editing by remember { mutableStateOf<TunnelManager.DNSConfig?>(null) }
    var custom by remember { mutableStateOf(TunnelManager.customDNS) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки DNS") },
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
            Text("Пресеты", style = MaterialTheme.typography.titleMedium)
            for (config in TunnelManager.dnsPresets) {
                DnsRow(config, onClick = null)
            }
            Spacer(Modifier.height(16.dp))
            Text("Свои серверы", style = MaterialTheme.typography.titleMedium)
            for (config in custom) {
                DnsRow(config, onClick = { editing = config })
                TextButton(onClick = {
                    custom = custom.filter { it.id != config.id }
                    TunnelManager.customDNS = custom
                    if (TunnelManager.selectedDNSId == config.id) TunnelManager.selectedDNSId = "system"
                }) {
                    Icon(Icons.Default.Delete, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(4.dp))
                    Text("Удалить ${config.name}", color = MaterialTheme.colorScheme.error)
                }
            }
            OutlinedButton(onClick = { editing = TunnelManager.DNSConfig("", "", "", emptyList(), null, false) }) {
                Text("Добавить DNS")
            }
        }
    }

    editing?.let { config ->
        DnsEditorDialog(
            initial = config.takeIf { it.id.isNotEmpty() },
            onDismiss = { editing = null },
            onSave = { name, desc, servers, dohURL ->
                val entry = TunnelManager.DNSConfig(
                    id = if (config.id.isNotEmpty()) config.id else java.util.UUID.randomUUID().toString(),
                    name = name, description = desc, servers = servers, dohURL = dohURL, isPreset = false,
                )
                custom = if (config.id.isNotEmpty()) custom.map { if (it.id == entry.id) entry else it }
                else custom + entry
                TunnelManager.customDNS = custom
                TunnelManager.selectedDNSId = entry.id
                editing = null
            },
        )
    }
}

@Composable
private fun DnsRow(config: TunnelManager.DNSConfig, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(config.name)
            val detail = buildString {
                if (config.dohURL != null) append("DoH · ")
                if (config.servers.isNotEmpty()) append(config.servers.joinToString(", "))
                else append(config.description)
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        if (TunnelManager.selectedDNSId == config.id) {
            Icon(Icons.Default.CheckCircle, null, tint = Green)
        } else if (config.isPreset) {
            // выбор пресета прямо из списка управления — одно касание
            TextButton(onClick = { TunnelManager.selectedDNSId = config.id }) { Text("Выбрать") }
        }
    }
}

@Composable
private fun DnsEditorDialog(
    initial: TunnelManager.DNSConfig?,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, servers: List<String>, dohURL: String?) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var servers by remember { mutableStateOf(initial?.servers?.toMutableList() ?: mutableListOf("")) }
    var dohURL by remember { mutableStateOf(initial?.dohURL ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val cleanDoh = dohURL.trim().takeIf { it.isNotEmpty() }
                    onSave(name.trim(), description.trim(), servers.map { it.trim() }.filter { it.isNotEmpty() }, cleanDoh)
                },
                enabled = name.isNotBlank() && (servers.any { it.isNotBlank() } || dohURL.isNotBlank()),
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        title = { Text(if (initial == null) "Новый DNS" else "Настройки DNS") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("Описание") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Text("DoH URL (приоритет)", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    dohURL,
                    { dohURL = it },
                    label = { Text("https://dns.example.com/dns-query") },
                    singleLine = true,
                )
                Text(
                    "DNS-over-HTTPS: запрос шифруется внутри TLS, провайдер не видит домен. Приоритет над plain UDP.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(8.dp))
                Text("Серверы (IP, fallback)", style = MaterialTheme.typography.labelLarge)
                servers.forEachIndexed { i, server ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            server,
                            { newValue ->
                                servers = servers.toMutableList().also { it[i] = newValue }
                            },
                            label = { Text("IP-адрес") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        if (servers.size > 1) {
                            IconButton(onClick = {
                                servers = servers.filterIndexed { idx, _ -> idx != i }.toMutableList()
                            }) {
                                Icon(Icons.Default.Delete, "Удалить сервер")
                            }
                        }
                    }
                }
                TextButton(onClick = { servers = (servers + "").toMutableList() }) { Text("Добавить сервер") }
                Text(
                    "Plain UDP:53 — fallback если DoH недоступен. Без DoH гео-блокировки по DNS не обходятся.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        },
    )
}

/// CF Worker: настройка собственного worker'а или реле (порт WorkerView).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkerScreen(navigate: (String) -> Unit) {
    var worker by remember { mutableStateOf(TunnelManager.workerDomain) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CF Worker") },
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
            OutlinedTextField(
                worker,
                { worker = it },
                label = { Text("worker.example.com или ip:port") },
                singleLine = true,
                enabled = !TunnelManager.running,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                TunnelManager.workerDomain = worker.trim()
                navigate("main")
            }) { Text("Сохранить") }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    worker.isBlank() -> "Пусто = kws-фронты Cloudflare (основной режим)."
                    worker.contains(":") -> "Прямой режим: сырой TCP на реле host:port, без CF."
                    else -> "Pipe-режим через свой CF worker. Перезапусти туннель, чтобы применилось."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

/// Журнал: моноширинный текст + обновить/скопировать (порт JournalView).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JournalScreen(navigate: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) {
        while (true) {
            text = TunnelManager.journal()
            loading = false
            delay(2000) // живой журнал: туннель в том же процессе, обновляем на месте
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Журнал") },
                navigationIcon = {
                    IconButton(onClick = { navigate("main") }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { loading = true; text = TunnelManager.journal(); loading = false }) {
                        Icon(Icons.Default.Refresh, "Обновить")
                    }
                    IconButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                    }) {
                        Icon(Icons.Default.ContentCopy, "Скопировать")
                    }
                },
            )
        }
    ) { padding ->
        if (loading && text.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    }
}

/// О программе (порт AboutView).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutScreen(navigate: (String) -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("О программе") },
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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Bolt, null, tint = Green, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(8.dp))
            Text("WSBridge", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(24.dp))
            Text("Что это", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(
                "WSBridge — VPN-туннель, работающий локально на вашем устройстве. Он перехватывает трафик Telegram и перенаправляет его через WebSocket-соединение к серверам Telegram в обход блокировок. Настройка в самом Telegram не требуется.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
            Text("Разработчик", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            LinkRow("Автор: l1ratch", null)
            LinkRow("Исходный код WSBridge-Android") {
                openUrl(context, "https://github.com/l1ratch/WSBridge-Android")
            }
            LinkRow("Оригинал: tg-ws-proxy (Flowseal)") {
                openUrl(context, "https://github.com/Flowseal/tg-ws-proxy")
            }
            LinkRow("Версия для iOS") {
                openUrl(context, "https://github.com/l1ratch/WSBridge-iOS")
            }
        }
    }
}

@Composable
private fun LinkRow(title: String, onClick: (() -> Unit)?) {
    Text(
        title,
        color = if (onClick != null) Green else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
    )
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }
}

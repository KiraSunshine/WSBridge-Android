package com.l1ratch.wsbridge

import android.app.Application
import com.l1ratch.wsbridge.tunnel.StatsStore

class WSBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // prefs должны быть готовы даже если процесс воскрес через START_STICKY
        // без MainActivity.
        TunnelManager.init(this)
        // Статистика живёт в одном процессе и сервисом, и UI: подключаем
        // персист один раз здесь — раньше любого читателя и любого писателя.
        StatsStore.attach(this)
    }
}

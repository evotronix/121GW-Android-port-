package com.eevblog.gw121

import android.app.Application
import com.eevblog.gw121.ble.BleManager
import com.eevblog.gw121.data.AppSettings
import com.eevblog.gw121.data.ContinuityEngine
import com.eevblog.gw121.data.SessionStore

class GwApplication : Application() {
    lateinit var settings: AppSettings
        private set
    lateinit var store: SessionStore
        private set
    lateinit var ble: BleManager
        private set
    lateinit var continuity: ContinuityEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = AppSettings(this)
        store = SessionStore(this)
        ble = BleManager(this)
        continuity = ContinuityEngine(this, settings)
    }

    companion object {
        lateinit var instance: GwApplication
            private set
    }
}

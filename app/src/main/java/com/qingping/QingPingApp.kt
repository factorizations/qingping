package com.qingping

import android.app.Application

class QingPingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)
        StatsStore.init(this)
    }
}

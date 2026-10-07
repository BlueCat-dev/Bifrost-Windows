package com.bifrost.twp

import android.app.Application
import com.bifrost.twp.data.ProxyRepository

class BifrostApp : Application() {

    override fun onCreate() {
        super.onCreate()

        try {
            ProxyRepository.getInstance(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

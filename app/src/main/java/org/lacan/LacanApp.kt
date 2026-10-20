package org.lacan

import android.app.Application
import org.lacan.runtime.AppRuntime

class LacanApp : Application() {
    override fun onCreate() { super.onCreate(); runtime = AppRuntime(this) }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) runtime.releaseIdleModel()
    }
    companion object { lateinit var runtime: AppRuntime; private set }
}

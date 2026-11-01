package org.lacan.access

import androidx.core.app.AppComponentFactory
import android.app.Service
import android.content.Intent
import android.os.Build

/** Keep the enabled accessibility component name stable across OS versions and app upgrades. */
class LacanComponentFactory : AppComponentFactory() {
    override fun instantiateServiceCompat(cl: ClassLoader, className: String, intent: Intent?): Service {
        if (Build.VERSION.SDK_INT >= 33 && className == LacanAccessibilityService::class.java.name) {
            return super.instantiateServiceCompat(cl, "org.lacan.access.LacanAccessibilityServiceApi33", intent)
        }
        return super.instantiateServiceCompat(cl, className, intent)
    }
}

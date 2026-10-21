package org.lacan.asr

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Explicitly selected for native ASR probes so LacanApp does not warm a second model. */
class IsolatedAsrRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)
}

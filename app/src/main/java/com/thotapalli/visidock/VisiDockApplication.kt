package com.thotapalli.visidock

import android.app.Application
import androidx.work.Configuration

/** Start the training scheduler when needed, rather than in the pre-activity provider phase. */
class VisiDockApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}

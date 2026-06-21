package io.sportsurge.app

import android.app.Application

/**
 * Application entry point. Kept as a thin stub — useful hook later if you want
 * to wire in DI (Hilt/Koin), logging, or work-managers.
 *
 * Required by the AndroidManifest even though it does nothing right now.
 */
class SportsurgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}

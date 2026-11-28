package me.srishanth.opentrack360

import android.app.Application
import me.srishanth.opentrack360.core.OpenTrackRuntime
import me.srishanth.opentrack360.tracking.TrackingRuntimeRegistry

class OpenTrackApplication : Application() {
    lateinit var runtime: OpenTrackRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = OpenTrackRuntime(this)
        TrackingRuntimeRegistry.provider = runtime
    }
}

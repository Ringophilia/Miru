package app.miru.browser

import android.app.Application
import android.app.ActivityManager
import android.os.Build
import android.os.Process
import app.miru.browser.engine.BrowserController
import app.miru.browser.engine.LibraryHistoryDelegate
import app.miru.data.BrowsingLibrary
import app.miru.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsRepository(application)
    val library = BrowsingLibrary(application)
    val browser = BrowserController(application, scope, LibraryHistoryDelegate(library))
}

class MiruApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Gecko's sandboxed/helper processes also instantiate Application.
        // They must not construct another browser store or recursively launch a runtime.
        val processName = if (Build.VERSION.SDK_INT >= 28) getProcessName() else {
            getSystemService(ActivityManager::class.java).runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
        }
        if (processName != packageName) return
        container = AppContainer(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::container.isInitialized) container.browser.onTrimMemory(level)
    }
}

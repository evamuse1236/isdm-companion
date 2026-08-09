package org.isdm.companion

import android.app.Application
import org.isdm.companion.data.RealLmsAdapter
import org.isdm.companion.engine.CompanionEngine
import org.isdm.companion.platform.AndroidNotifier
import org.isdm.companion.platform.SecureCredentialStore

class CompanionApplication : Application() {
    lateinit var credentialStore: SecureCredentialStore
        private set

    lateinit var engine: CompanionEngine
        private set

    override fun onCreate() {
        super.onCreate()
        credentialStore = SecureCredentialStore(this)
        engine = CompanionEngine(
            gateway = RealLmsAdapter(),
            notifier = AndroidNotifier(this),
        )
        AndroidNotifier.createChannels(this)
    }
}

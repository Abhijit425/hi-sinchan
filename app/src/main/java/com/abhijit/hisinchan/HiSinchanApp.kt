package com.abhijit.hisinchan

import android.app.Application
import com.abhijit.hisinchan.ai.ConversationEngine
import com.abhijit.hisinchan.data.AppDatabase
import com.abhijit.hisinchan.data.SecretStore
import com.abhijit.hisinchan.data.SettingsRepository
import com.abhijit.hisinchan.memory.MemoryRepository
import com.abhijit.hisinchan.voice.WakeWordService

/** Simple manual dependency container (no DI framework needed for one screen-set). */
class HiSinchanApp : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var secrets: SecretStore
        private set
    lateinit var memories: MemoryRepository
        private set
    lateinit var engine: ConversationEngine
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.create(this)
        settings = SettingsRepository(this)
        secrets = SecretStore(this)
        memories = MemoryRepository(database.memoryDao())
        engine = ConversationEngine(memories, secrets)
        WakeWordService.createChannels(this)
    }
}

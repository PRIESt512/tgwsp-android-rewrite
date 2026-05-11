package soft.shadlv.twp_rewritekts.repository

import android.content.Context
import soft.shadlv.twp_rewritekts.store.DataStoreSecurity

class ProxyConfigRepository(context: Context) {
    @PublishedApi
    internal val dataStoreSecurity = DataStoreSecurity(context)

    suspend inline fun <reified T> getConfig(fileName: String): T? {
        return dataStoreSecurity.getObject<T>(fileName)
    }

    suspend inline fun <reified T> saveConfig(config: T, fileName: String) {
        dataStoreSecurity.saveObject(fileName, config)
    }

    suspend fun dropAll(fileName: String) {
        dataStoreSecurity.deleteStore(fileName)
    }
}
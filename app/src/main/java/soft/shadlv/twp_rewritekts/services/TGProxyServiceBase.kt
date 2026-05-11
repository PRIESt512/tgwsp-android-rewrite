package soft.shadlv.twp_rewritekts.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_SCREEN_OFF
import android.content.Intent.ACTION_SCREEN_ON
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED
import android.os.Process
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.Serializer
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import main.ProxyControl
import soft.shadlv.twp_rewritekts.R
import soft.shadlv.twp_rewritekts.repository.ProxyConfigRepository
import soft.shadlv.twp_rewritekts.store.LocalProxyConfig
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

object StatusSerializer : Serializer<Boolean> {
    override val defaultValue: Boolean
        get() = false

    override suspend fun readFrom(input: InputStream): Boolean {
        try {
            return Json.decodeFromString<Boolean>(
                input.readBytes().decodeToString()
            )
        } catch (serialization: SerializationException) {
            throw CorruptionException("Unable to read Time", serialization)
        }
    }

    override suspend fun writeTo(t: Boolean, output: OutputStream) {
        output.write(
            Json.encodeToString(t)
                .encodeToByteArray()
        )
    }
}

object ServiceDataStoreProvider {
    private val instances = ConcurrentHashMap<ProxyType, DataStore<Boolean>>(2)

    fun getInstance(context: Context, type: ProxyType): DataStore<Boolean> {
        return instances.computeIfAbsent(type) {
            MultiProcessDataStoreFactory.create(
                serializer = StatusSerializer,
                produceFile = {
                    File(context.filesDir, type.statusFileName)
                },
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            )
        }
    }
}

enum class ProxyType(val statusFileName: String, val configFileName: String) {
    LOCAL("localStatus.json", "proxy_config_local.data"),
    EXTERNAL("externalStatus.json", "proxy_config_external.data")
}

abstract class TGProxyServiceBase : LifecycleService() {
    abstract val notificationId: Int
    abstract val proxyType: ProxyType

    companion object {
        private const val CHANNEL_ID: String = "ProxyChannel"
    }

    private val repository by lazy { ProxyConfigRepository(application) }
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }

    @Volatile
    private var isRun = false

    protected val proxyControl by lazy { ProxyControl() }

    private val dozeModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val powerManager = context.getSystemService(POWER_SERVICE) as PowerManager
            val isIdle = powerManager.isDeviceIdleMode
            val isScreenOn = powerManager.isInteractive

            if (isIdle) {
                Log.d("ProxyWatchdog", "System went into Doze Mode. Stopping proxy.")
                stopProxy()
            } else if (isScreenOn && !isRun) {
                Log.d("ProxyWatchdog", "System woke up. Starting proxy.")
                startProxy()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(ACTION_SCREEN_ON)
            addAction(ACTION_SCREEN_OFF)
        }
        registerReceiver(dozeModeReceiver, filter)
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                notificationId,
                buildNotification("Подготовка..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(notificationId, buildNotification("Подготовка..."))
        }

        startProxy()

        return START_STICKY
    }

    override fun onDestroy() {
        Log.d("TGProxyService", "proxy stopping")
        unregisterReceiver(dozeModeReceiver)
        stopProxy()
        PythonBackgroundEngine.shutdown(proxyType)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("TGProxyService", "Stop_hard")
    }

    private fun startProxy() {
        lifecycleScope.launch {
            try {
                val proxyConfig = repository.getConfig<LocalProxyConfig>(proxyType.configFileName)

                if (proxyConfig != null) {
                    startProxyEngine(proxyConfig)
                } else {
                    Log.e("TGProxyService", "Config is null, stopping self")
                    Toast.makeText(
                        applicationContext,
                        "Сохраните параметры в настройках",
                        Toast.LENGTH_SHORT
                    )
                        .show()
                    stopSelf()
                }
            } catch (ex: Exception) {
                Toast.makeText(applicationContext, "Ошибка запуска сервиса", Toast.LENGTH_SHORT)
                    .show()
                stopSelf()
            }
        }
    }

    @Synchronized
    private fun stopProxy() {
        if (!isRun) return

        runBlocking {
            withTimeoutOrNull(1500) {
                proxyControl.stop_proxy()
            }
            updateProxyStatus(false, "Прокси остановлен")
        }
    }

    @Synchronized
    private fun startProxyEngine(input: LocalProxyConfig) =
        lifecycleScope.launch(PythonBackgroundEngine.getDispatcher(proxyType)) {
            try {
                if (isRun) return@launch

                Log.d(
                    "TGProxyService",
                    "Proxy starting: Proxy Process PID: ${Process.myPid()}"
                )

                val dcip = input.dcip.replace(";", "\n")

                updateProxyStatus(true, "Прокси запущен")
                proxyControl.start_proxy(input.host, input.port, dcip, input.secret)

                Log.d("TGProxyService", "Proxy control stopped")
            } catch (e: Exception) {
                handleProxyCrash(e)
            }
        }

    private suspend fun handleProxyCrash(e: Exception) {
        val errorMessage =
            if (e is PyException) "PYTHON CRASHED: ${e.message}" else "Generic error: ${e.message}"
        Log.e("TGProxyService", errorMessage)

        updateProxyStatus(false, "Ошибка: Прокси остановлен")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Управление TG прокси (${proxyType.name})",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Уведомления о состоянии прокси-сервера (${proxyType.name})"
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TG Proxy (${proxyType.name})")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(isRun)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private suspend fun updateProxyStatus(status: Boolean, text: String) =
        withContext(Dispatchers.IO) {
            try {
                Log.d("TGProxyService - ${proxyType.name}", "Update status $status")
                val dataStore =
                    ServiceDataStoreProvider.getInstance(this@TGProxyServiceBase, proxyType)
                dataStore.updateData { prefs ->
                    status
                }
                isRun = status
                updateNotificationStatus(text)
            } catch (ex: Exception) {
                Log.d("TGProxyService", "Error write status")
            }
        }

    private fun updateNotificationStatus(text: String) {
        notificationManager.notify(notificationId, buildNotification(text))
    }
}

internal object PythonBackgroundEngine {
    private var executors  = ConcurrentHashMap<ProxyType, ExecutorService>()
    private var dispatchers = ConcurrentHashMap<ProxyType, CoroutineDispatcher>()

    @Synchronized
    fun getDispatcher(type: ProxyType): CoroutineDispatcher {
        return dispatchers.computeIfAbsent(type) {
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "PythonEngineThread - ${type.name}").apply {
                    isDaemon = true
                    priority = 8
                }
            }
            executors[type] = executor
            executor.asCoroutineDispatcher()
        }
    }

    @Synchronized
    fun shutdown(type: ProxyType) {
        val exec = executors.remove(type)
        dispatchers.remove(type)

        exec?.let {
            it.shutdown()
            try {
                if (!it.awaitTermination(3, SECONDS)) {
                    Log.w("TGProxyService - Pool", "Executor didn't stop in time, forcing shutdownNow")
                    it.shutdownNow()

                    if (!it.awaitTermination(1, SECONDS)) {
                        Log.e("TGProxyService - Pool", "Executor pool did not terminate")
                    }
                }
            } catch (ie: InterruptedException) {
                it.shutdownNow()
                Thread.currentThread().interrupt()
            } finally {
                Log.i("TGProxyService - Pool", "Cleaned up resources for ${type.name}")
            }
        }
    }
}
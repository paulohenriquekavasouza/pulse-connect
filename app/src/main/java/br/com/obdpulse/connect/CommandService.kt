package br.com.obdpulse.connect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CommandService : Service() {

    private val scope = MainScope()
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        val vin = intent?.getStringExtra(EXTRA_VIN)
        val spec = intent?.getStringExtra(EXTRA_COMMAND)?.let(VehicleCommands::of)
        if (job?.isActive == true) return START_NOT_STICKY
        if (vin == null || spec == null) {
            finish()
            return START_NOT_STICKY
        }
        job = scope.launch {
            try {
                run(vin, spec)
            } finally {
                finish()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun run(vin: String, spec: CommandSpec) {
        WidgetState.set(this, getString(spec.sending), busy = true)
        var started = false
        try {
            val pin = SessionHolder.pin?.takeIf { it.isNotBlank() } ?: UconnectStore.pin(this)?.takeIf { it.isNotBlank() }
                ?: throw UconnectException(getString(R.string.widget_need_pin))
            val attempt: suspend (UconnectSession) -> Unit = { session ->
                started = false
                val pinAuth = UconnectClient.authenticatePin(session, pin)
                started = true
                UconnectClient.command(session, vin, spec.command, pinAuth)
            }
            val current = SessionHolder.session
            if (current == null) {
                attempt(login())
            } else {
                try {
                    attempt(current)
                } catch (e: UconnectAuthException) {
                    attempt(login())
                }
            }
            report(getString(spec.sentShort), error = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(UconnectErrors.command(this, e, started), error = true)
        }
    }

    private fun report(text: String, error: Boolean) {
        WidgetState.set(this, text, busy = false, error = error)
        Toast.makeText(this, text, if (error) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private suspend fun login(): UconnectSession {
        val email = UconnectStore.email(this)
        val password = UconnectStore.password(this)
        if (!UconnectStore.isSaved(this) || email.isNullOrBlank() || password.isNullOrBlank()) {
            throw UconnectException(getString(R.string.widget_sign_in))
        }
        val session = UconnectClient.login(email, password)
        SessionHolder.onLogin(session, UconnectStore.pin(this))
        return session
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.widget_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.widget_notification))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "commands"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_VIN = "vin"
        private const val EXTRA_COMMAND = "command"

        fun intent(context: Context, vin: String, command: String): Intent =
            Intent(context, CommandService::class.java)
                .setAction("command.$command")
                .putExtra(EXTRA_VIN, vin)
                .putExtra(EXTRA_COMMAND, command)
    }
}

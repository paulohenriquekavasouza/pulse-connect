package br.com.obdpulse.connect

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

class WidgetConfirmActivity : Activity() {

    private var vin: String? = null
    private var command: String? = null
    private lateinit var gate: BiometricGate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vin = intent.getStringExtra(EXTRA_VIN)
        val spec = intent.getStringExtra(EXTRA_COMMAND)?.let(VehicleCommands::of)
        if (vin == null || spec == null) {
            finish()
            return
        }
        this.vin = vin
        command = spec.command
        val name = UconnectStore.vehicles(this).firstOrNull { it.vin == vin }?.name ?: getString(R.string.app_name)
        gate = BiometricGate(
            this,
            onSuccess = {
                send()
                finish()
            },
            onFailure = { message ->
                if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                finish()
            },
        )
        gate.start(getString(spec.biometricTitle), name)
    }

    override fun onDestroy() {
        if (::gate.isInitialized) gate.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (::gate.isInitialized) gate.onActivityResult(requestCode, resultCode)
    }

    private fun send() {
        val vin = vin ?: return
        val command = command ?: return
        startForegroundService(CommandService.intent(this, vin, command))
    }

    companion object {
        private const val EXTRA_VIN = "vin"
        private const val EXTRA_COMMAND = "command"

        fun intent(context: Context, vin: String, command: String): Intent =
            Intent(context, WidgetConfirmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_VIN, vin)
                .putExtra(EXTRA_COMMAND, command)
    }
}

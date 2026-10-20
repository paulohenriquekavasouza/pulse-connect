package br.com.obdpulse.connect

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.widget.Toast

class WidgetConfirmActivity : Activity() {

    private var vin: String? = null
    private var command: String? = null
    private var cancellation: CancellationSignal? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vin = intent.getStringExtra(EXTRA_VIN)
        val spec = intent.getStringExtra(EXTRA_COMMAND)?.let(VehicleCommands::of)
        if (vin == null || spec == null) {
            finish()
            return
        }
        val name = UconnectStore.vehicles(this).firstOrNull { it.vin == vin }?.name ?: getString(R.string.app_name)
        this.vin = vin
        command = spec.command
        if (spec.command in BIOMETRIC_COMMANDS) {
            authenticate(spec, name)
            return
        }
        val (title, message) = when (spec.command) {
            UconnectClient.CMD_LOCK -> R.string.confirm_lock_title to R.string.confirm_lock_message
            UconnectClient.CMD_STOP -> R.string.confirm_stop_title to R.string.confirm_stop_message
            else -> R.string.confirm_start_title to R.string.confirm_start_message
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(getString(message, name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(spec.label) { _, _ -> send() }
            .setOnDismissListener { finish() }
            .show()
    }

    override fun onDestroy() {
        cancellation?.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CREDENTIAL && resultCode == RESULT_OK) send()
        finish()
    }

    private fun authenticate(spec: CommandSpec, name: String) {
        val title = getString(if (spec.command == UconnectClient.CMD_START) R.string.biometric_start_title else R.string.biometric_unlock_title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val builder = BiometricPrompt.Builder(this)
                .setTitle(title)
                .setSubtitle(name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }
            val signal = CancellationSignal()
            cancellation = signal
            builder.build().authenticate(
                signal,
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        send()
                        finish()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                            errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                        ) {
                            Toast.makeText(this@WidgetConfirmActivity, errString, Toast.LENGTH_LONG).show()
                        }
                        finish()
                    }
                },
            )
            return
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) {
            Toast.makeText(this, R.string.biometric_no_lock, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(keyguard.createConfirmDeviceCredentialIntent(title, name), REQUEST_CREDENTIAL)
    }

    private fun send() {
        val vin = vin ?: return
        val command = command ?: return
        startForegroundService(CommandService.intent(this, vin, command))
    }

    companion object {
        private const val EXTRA_VIN = "vin"
        private const val EXTRA_COMMAND = "command"
        private const val REQUEST_CREDENTIAL = 1
        private val BIOMETRIC_COMMANDS = setOf(UconnectClient.CMD_UNLOCK, UconnectClient.CMD_START)

        fun intent(context: Context, vin: String, command: String): Intent =
            Intent(context, WidgetConfirmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_VIN, vin)
                .putExtra(EXTRA_COMMAND, command)
    }
}

package br.com.obdpulse.connect

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

class BiometricGate(
    private val activity: Activity,
    private val onSuccess: () -> Unit,
    private val onFailure: (message: CharSequence?) -> Unit,
) {

    private var cancellation: CancellationSignal? = null

    fun isAvailable(): Boolean = activity.getSystemService(KeyguardManager::class.java).isDeviceSecure

    fun start(title: String, subtitle: String) {
        if (!isAvailable()) {
            onFailure(activity.getString(R.string.biometric_no_lock))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val builder = BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setSubtitle(subtitle)
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
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onSuccess()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        val cancelled = errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                            errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                        onFailure(if (cancelled) null else errString)
                    }
                },
            )
            return
        }
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        @Suppress("DEPRECATION")
        activity.startActivityForResult(keyguard.createConfirmDeviceCredentialIntent(title, subtitle), REQUEST_CREDENTIAL)
    }

    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQUEST_CREDENTIAL) return false
        if (resultCode == Activity.RESULT_OK) onSuccess() else onFailure(null)
        return true
    }

    fun cancel() {
        cancellation?.cancel()
        cancellation = null
    }

    private companion object {
        const val REQUEST_CREDENTIAL = 4101
    }
}

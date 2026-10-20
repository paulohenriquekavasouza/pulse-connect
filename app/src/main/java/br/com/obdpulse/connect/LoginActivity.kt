package br.com.obdpulse.connect

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LoginActivity : Activity() {

    private val scope = MainScope()

    private lateinit var form: View
    private lateinit var busy: View
    private lateinit var busyText: TextView
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var pin: EditText
    private lateinit var save: Switch
    private lateinit var connect: Button
    private lateinit var clear: Button
    private lateinit var status: TextView
    private lateinit var locked: View
    private lateinit var lockedText: TextView
    private lateinit var gate: BiometricGate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        form = findViewById(R.id.login_form)
        busy = findViewById(R.id.login_busy)
        busyText = findViewById(R.id.login_busy_text)
        email = findViewById(R.id.uconnect_email)
        password = findViewById(R.id.uconnect_password)
        pin = findViewById(R.id.uconnect_pin)
        save = findViewById(R.id.uconnect_save)
        connect = findViewById(R.id.uconnect_connect)
        clear = findViewById(R.id.uconnect_clear)
        status = findViewById(R.id.uconnect_status)
        locked = findViewById(R.id.login_locked)
        lockedText = findViewById(R.id.login_locked_text)
        gate = BiometricGate(
            this,
            onSuccess = {
                SessionHolder.unlocked = true
                loginWithSaved()
            },
            onFailure = { message -> showLocked(message) },
        )
        findViewById<View>(R.id.login_unlock).setOnClickListener { unlock() }
        findViewById<View>(R.id.login_other_account).setOnClickListener {
            clearCredentials()
            setBusy(false, null)
        }

        connect.setOnClickListener { doLogin(fromUser = true) }
        clear.setOnClickListener { clearCredentials() }

        val saved = UconnectStore.isSaved(this)
        clear.visibility = if (saved) View.VISIBLE else View.GONE
        if (!saved) return
        if (SessionHolder.unlocked || !gate.isAvailable()) {
            SessionHolder.unlocked = true
            loginWithSaved()
        } else {
            unlock()
        }
    }

    override fun onDestroy() {
        gate.cancel()
        scope.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        gate.onActivityResult(requestCode, resultCode)
    }

    private fun unlock() {
        showLocked(null)
        gate.start(getString(R.string.biometric_app_title), getString(R.string.biometric_app_subtitle))
    }

    private fun showLocked(message: CharSequence?) {
        form.visibility = View.GONE
        busy.visibility = View.GONE
        locked.visibility = View.VISIBLE
        lockedText.text = message ?: getString(R.string.login_locked)
    }

    private fun loginWithSaved() {
        email.setText(UconnectStore.email(this).orEmpty())
        password.setText(UconnectStore.password(this).orEmpty())
        pin.setText(UconnectStore.pin(this).orEmpty())
        doLogin(fromUser = false)
    }

    private fun doLogin(fromUser: Boolean) {
        val user = email.text.toString().trim()
        val pass = password.text.toString()
        if (user.isEmpty() || pass.isEmpty()) {
            if (fromUser) Toast.makeText(this, R.string.uconnect_need_credentials, Toast.LENGTH_SHORT).show()
            return
        }
        if (fromUser && save.isChecked) UconnectStore.save(this, user, pass, pin.text.toString())
        setBusy(true, R.string.uconnect_status_connecting)
        scope.launch {
            try {
                val session = UconnectClient.login(user, pass)
                SessionHolder.onLogin(session, pin.text.toString().ifBlank { UconnectStore.pin(this@LoginActivity) })
                SessionHolder.unlocked = true
                busyText.setText(R.string.uconnect_status_vehicles)
                val vehicles = UconnectClient.listVehicles(session)
                SessionHolder.vehicles = vehicles
                if (vehicles.isNotEmpty()) UconnectStore.saveVehicles(this@LoginActivity, vehicles)
                if (vehicles.isEmpty()) {
                    showError(getString(R.string.uconnect_status_no_vehicles))
                    return@launch
                }
                val target = SessionHolder.returnTo
                SessionHolder.returnTo = null
                if (target != null && target != LoginActivity::class.java) {
                    startActivity(Intent(this@LoginActivity, target))
                } else {
                    Navigation.openHome(this@LoginActivity)
                }
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(UconnectErrors.describe(this@LoginActivity, e))
            }
        }
    }

    private fun showError(text: String) {
        setBusy(false, null)
        status.text = text
        status.visibility = View.VISIBLE
    }

    private fun clearCredentials() {
        UconnectStore.clear(this)
        SessionHolder.clear()
        email.setText("")
        password.setText("")
        pin.setText("")
        clear.visibility = View.GONE
        status.setText(R.string.uconnect_cleared)
        status.visibility = View.VISIBLE
    }

    private fun setBusy(isBusy: Boolean, text: Int?) {
        locked.visibility = View.GONE
        form.visibility = if (isBusy) View.GONE else View.VISIBLE
        busy.visibility = if (isBusy) View.VISIBLE else View.GONE
        text?.let(busyText::setText)
        status.visibility = View.GONE
    }
}

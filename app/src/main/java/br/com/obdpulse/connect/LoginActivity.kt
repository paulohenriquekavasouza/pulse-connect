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

        connect.setOnClickListener { doLogin(fromUser = true) }
        clear.setOnClickListener { clearCredentials() }

        val saved = UconnectStore.isSaved(this)
        clear.visibility = if (saved) View.VISIBLE else View.GONE
        if (saved) {
            email.setText(UconnectStore.email(this).orEmpty())
            password.setText(UconnectStore.password(this).orEmpty())
            pin.setText(UconnectStore.pin(this).orEmpty())
            doLogin(fromUser = false)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
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
        form.visibility = if (isBusy) View.GONE else View.VISIBLE
        busy.visibility = if (isBusy) View.VISIBLE else View.GONE
        text?.let(busyText::setText)
        status.visibility = View.GONE
    }
}

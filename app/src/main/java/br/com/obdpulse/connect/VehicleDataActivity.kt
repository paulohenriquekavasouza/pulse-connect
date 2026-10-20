package br.com.obdpulse.connect

import android.app.Activity
import android.os.Bundle
import android.view.View
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

abstract class VehicleDataActivity : Activity() {

    private val scope = MainScope()
    private lateinit var refreshButton: View

    protected abstract val layoutId: Int

    protected open val explore: Boolean = false

    protected abstract fun bindViews()

    protected abstract fun showLoading()

    protected abstract fun showData(data: VehicleData)

    protected abstract fun showError(message: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SessionHolder.session == null) {
            AuthFlow.relogin(this, null)
            return
        }
        setContentView(layoutId)
        refreshButton = findViewById(R.id.refresh)
        refreshButton.setOnClickListener { refresh() }
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        bindViews()
        refresh()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    protected fun refresh() {
        val session = SessionHolder.session
        if (session == null) {
            AuthFlow.relogin(this, null)
            return
        }
        refreshButton.isEnabled = false
        showLoading()
        scope.launch {
            try {
                showData(
                    UconnectClient.fetchVehicleData(session, UconnectStore.vin(this@VehicleDataActivity), explore),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!AuthFlow.handle(this@VehicleDataActivity, e, null)) showError(UconnectErrors.describe(this@VehicleDataActivity, e))
            } finally {
                refreshButton.isEnabled = true
            }
        }
    }
}

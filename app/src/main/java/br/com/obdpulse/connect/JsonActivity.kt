package br.com.obdpulse.connect

import android.content.ClipData
import android.content.ClipboardManager
import android.view.View
import android.widget.TextView
import android.widget.Toast

class JsonActivity : VehicleDataActivity() {

    override val layoutId: Int = R.layout.activity_json

    override val explore: Boolean = true

    private lateinit var message: TextView
    private lateinit var jsonText: TextView
    private var currentJson = ""

    override fun bindViews() {
        message = findViewById(R.id.json_message)
        jsonText = findViewById(R.id.json_text)
        findViewById<View>(R.id.copy).setOnClickListener { copyJson() }
    }

    override fun showLoading() {
        message.setText(R.string.status_loading)
    }

    override fun showData(data: VehicleData) {
        currentJson = VehicleJson.build(data)
        jsonText.text = currentJson
        message.text = if (data.errors.isEmpty()) {
            getString(R.string.status_updated)
        } else {
            getString(R.string.status_partial, data.errors.keys.joinToString(", "))
        }
    }

    override fun showError(message: String) {
        this.message.text = message
    }

    private fun copyJson() {
        if (currentJson.isEmpty()) return
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.json_title), currentJson))
        Toast.makeText(this, R.string.json_copied, Toast.LENGTH_SHORT).show()
    }
}

package br.com.obdpulse.connect

import android.widget.TextView

class StatusActivity : VehicleDataActivity() {

    override val layoutId: Int = R.layout.activity_status

    private lateinit var message: TextView
    private lateinit var vehicleText: TextView
    private lateinit var panelText: TextView
    private lateinit var tiresText: TextView
    private lateinit var doorsText: TextView
    private lateinit var locationText: TextView

    override fun bindViews() {
        message = findViewById(R.id.status_message)
        vehicleText = findViewById(R.id.status_vehicle)
        panelText = findViewById(R.id.status_panel)
        tiresText = findViewById(R.id.status_tires)
        doorsText = findViewById(R.id.status_doors)
        locationText = findViewById(R.id.status_location)
    }

    override fun showLoading() {
        message.setText(R.string.status_loading)
    }

    override fun showData(data: VehicleData) {
        val report = VehicleFormatter.report(VehicleParser.parse(data))
        vehicleText.text = report.vehicle
        panelText.text = report.panel
        tiresText.text = report.tires
        doorsText.text = report.doors
        locationText.text = report.location
        message.text = if (data.errors.isEmpty()) {
            getString(R.string.status_updated)
        } else {
            getString(R.string.status_partial, data.errors.keys.joinToString(", "))
        }
    }

    override fun showError(message: String) {
        this.message.text = message
    }
}

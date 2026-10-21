package br.com.obdpulse.connect

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.text.InputFilter
import android.text.InputType
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class VehicleActivity : Activity() {

    private val scope = MainScope()

    private lateinit var vehicle: UconnectVehicle
    private var fromList = false

    private lateinit var actionsRow: LinearLayout
    private lateinit var actionsLoading: View
    private lateinit var actionsProgress: View
    private lateinit var actionsStatus: TextView
    private lateinit var image: ImageView
    private lateinit var message: TextView
    private lateinit var map: WebView
    private lateinit var mapPlaceholder: TextView
    private lateinit var locationUpdated: TextView
    private lateinit var locationNote: TextView
    private lateinit var locateButton: View
    private lateinit var locating: View

    private val actionViews = ArrayList<View>()
    private var location: VehicleLocation? = null
    private var locateJob: Job? = null
    private var tickerJob: Job? = null

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vin = intent.getStringExtra(EXTRA_VIN) ?: UconnectStore.vin(this)
        val found = SessionHolder.vehicles.firstOrNull { it.vin == vin }
        if (SessionHolder.session == null || found == null) {
            AuthFlow.relogin(this, SessionHolder.pending)
            return
        }
        vehicle = found
        fromList = intent.getBooleanExtra(EXTRA_FROM_LIST, false)
        UconnectStore.saveVin(this, vehicle.vin)

        setContentView(R.layout.activity_vehicle)
        actionsRow = findViewById(R.id.vehicle_actions)
        actionsLoading = findViewById(R.id.vehicle_actions_loading)
        actionsProgress = findViewById(R.id.vehicle_actions_progress)
        actionsStatus = findViewById(R.id.vehicle_actions_status)
        image = findViewById(R.id.vehicle_image)
        message = findViewById(R.id.vehicle_message)
        map = findViewById(R.id.vehicle_map)
        mapPlaceholder = findViewById(R.id.vehicle_map_placeholder)
        locationUpdated = findViewById(R.id.vehicle_location_updated)
        locationNote = findViewById(R.id.vehicle_location_note)
        locateButton = findViewById(R.id.vehicle_locate)
        locating = findViewById(R.id.vehicle_locating)

        bindHeader()

        val hasOthers = SessionHolder.vehicles.size > 1
        findViewById<View>(R.id.vehicle_back).apply {
            visibility = if (hasOthers) View.VISIBLE else View.GONE
            setOnClickListener { goBack() }
        }
        if (hasOthers && !fromList && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
        findViewById<View>(R.id.vehicle_menu).setOnClickListener { showMenu(it) }

        findViewById<View>(R.id.vehicle_map_frame).clipToOutline = true
        map.settings.javaScriptEnabled = true
        map.setBackgroundColor(getColor(R.color.bg_bottom))
        map.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
        locateButton.setOnClickListener { refreshLocation(force = true) }

        actionsLoading.setOnClickListener { loadVehicleInfo(null) }

        val pending = SessionHolder.pending
        SessionHolder.pending = null
        val pendingCommand = pending?.command?.takeIf { pending.type == PendingAction.Type.COMMAND }
        loadVehicleInfo(pendingCommand)
        refreshLocation(force = false)
    }

    private fun bindHeader() {
        bindNames()
        ImageLoader.bindVehicle(scope, image, vehicle.imageUrl)
    }

    private fun bindNames() {
        val shown = VehicleNicknames.apply(this, vehicle)
        findViewById<TextView>(R.id.vehicle_title).text = shown.name
        findViewById<TextView>(R.id.vehicle_subtitle).apply {
            text = shown.subtitle
            visibility = if (shown.subtitle.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun editNickname() {
        val pad = (resources.displayMetrics.density * 20).toInt()
        val input = EditText(this).apply {
            setText(VehicleNicknames.get(this@VehicleActivity, vehicle.vin).orEmpty())
            hint = vehicle.name
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(VehicleNicknames.MAX_LENGTH))
            isSingleLine = true
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.nickname_title)
            .setMessage(getString(R.string.nickname_message, vehicle.name))
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.nickname_save) { _, _ ->
                VehicleNicknames.set(this, vehicle.vin, input.text.toString())
                bindNames()
            }
            .show()
    }

    private fun loadVehicleInfo(pendingCommand: String?) {
        val session = SessionHolder.session
        if (session == null) {
            AuthFlow.relogin(this, null)
            return
        }
        actionsRow.visibility = View.GONE
        findViewById<View>(R.id.vehicle_no_actions).visibility = View.GONE
        actionsLoading.visibility = View.VISIBLE
        actionsLoading.isClickable = false
        actionsProgress.visibility = View.VISIBLE
        actionsStatus.setText(R.string.vehicle_checking_actions)
        scope.launch {
            try {
                val vehicles = UconnectClient.listVehicles(session)
                val fresh = vehicles.firstOrNull { it.vin == vehicle.vin }
                if (vehicles.isNotEmpty()) {
                    SessionHolder.vehicles = vehicles
                    UconnectStore.saveVehicles(this@VehicleActivity, vehicles)
                }
                if (fresh != null) {
                    val imageChanged = fresh.imageUrl != vehicle.imageUrl
                    vehicle = fresh
                    if (imageChanged) bindHeader() else bindNames()
                }
                buildActions()
                if (pendingCommand != null && pendingCommand in vehicle.commands) execute(pendingCommand)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!AuthFlow.handle(this@VehicleActivity, e, null)) {
                    actionsProgress.visibility = View.GONE
                    actionsStatus.setText(R.string.vehicle_actions_failed)
                    actionsLoading.isClickable = true
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::vehicle.isInitialized) return
        tickerJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                renderUpdated()
            }
        }
    }

    override fun onPause() {
        tickerJob?.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::vehicle.isInitialized && SessionHolder.vehicles.size > 1 && !fromList) goBack() else super.onBackPressed()
    }

    private fun goBack() {
        if (!fromList) Navigation.openGarage(this)
        finish()
    }

    private fun buildActions() {
        val specs = VehicleCommands.supported(vehicle)
        actionsRow.removeAllViews()
        actionViews.clear()
        val gap = (resources.displayMetrics.density * 10).toInt()
        specs.forEachIndexed { index, spec ->
            val tile = layoutInflater.inflate(R.layout.item_action, actionsRow, false)
            tile.findViewById<ImageView>(R.id.action_icon).apply {
                setImageResource(spec.icon)
                if (spec.accent) {
                    setBackgroundResource(R.drawable.action_icon_accent_bg)
                    imageTintList = getColorStateList(R.color.accent)
                }
            }
            tile.findViewById<TextView>(R.id.action_label).setText(spec.label)
            tile.setOnClickListener { execute(spec.command) }
            if (index > 0) (tile.layoutParams as LinearLayout.LayoutParams).marginStart = gap
            actionsRow.addView(tile)
            actionViews += tile
        }
        actionsLoading.visibility = View.GONE
        actionsRow.visibility = if (specs.isEmpty()) View.GONE else View.VISIBLE
        findViewById<View>(R.id.vehicle_no_actions).visibility = if (specs.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun execute(command: String) {
        val spec = VehicleCommands.of(command) ?: return
        val pending = PendingAction(PendingAction.Type.COMMAND, vehicle.vin, command)
        val session = SessionHolder.session
        if (session == null) {
            AuthFlow.relogin(this, pending)
            return
        }
        val pin = SessionHolder.pin.orEmpty()
        if (pin.isEmpty()) {
            showMessage(getString(R.string.uconnect_need_pin))
            return
        }
        setActionsEnabled(false)
        showMessage(getString(spec.sending))
        scope.launch {
            var commandStarted = false
            try {
                val pinAuth = UconnectClient.authenticatePin(session, pin)
                commandStarted = true
                UconnectClient.command(session, vehicle.vin, command, pinAuth)
                showMessage(getString(spec.sent))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!AuthFlow.handle(this@VehicleActivity, e, pending)) {
                    showMessage(UconnectErrors.command(this@VehicleActivity, e, commandStarted))
                }
            } finally {
                setActionsEnabled(true)
            }
        }
    }

    private fun refreshLocation(force: Boolean) {
        val session = SessionHolder.session
        if (session == null) {
            AuthFlow.relogin(this, PendingAction(PendingAction.Type.LOCATE, vehicle.vin, null))
            return
        }
        val canLocate = UconnectClient.CMD_LOCATE in vehicle.commands
        locateJob?.cancel()
        locateJob = scope.launch {
            setLocating(true)
            try {
                val last = UconnectClient.location(session, vehicle.vin)
                showLocation(last)
                if (!canLocate || !(force || VehicleFormatter.isStale(last?.updatedMs))) return@launch
                val pin = SessionHolder.pin.orEmpty()
                if (pin.isEmpty()) {
                    showNote(getString(R.string.vehicle_locate_no_pin))
                    return@launch
                }
                showNote(getString(R.string.vehicle_locating))
                val pinAuth = UconnectClient.authenticatePin(session, pin)
                UconnectClient.command(session, vehicle.vin, UconnectClient.CMD_LOCATE, pinAuth)
                val before = last?.updatedMs ?: 0L
                repeat(LOCATE_POLLS) {
                    delay(LOCATE_INTERVAL_MS)
                    val current = UconnectClient.location(session, vehicle.vin)
                    if (current != null && (current.updatedMs ?: 0L) > before) {
                        showLocation(current)
                        showNote(getString(R.string.vehicle_located))
                        return@launch
                    }
                }
                showNote(getString(R.string.vehicle_locate_timeout))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!AuthFlow.handle(this@VehicleActivity, e, null)) {
                    if (location == null) mapPlaceholder.setText(R.string.vehicle_location_none)
                    showNote(UconnectErrors.describe(this@VehicleActivity, e))
                }
            } finally {
                setLocating(false)
            }
        }
    }

    private fun showLocation(value: VehicleLocation?) {
        if (value == null) {
            if (location == null) {
                mapPlaceholder.setText(R.string.vehicle_location_none)
                mapPlaceholder.visibility = View.VISIBLE
            }
            renderUpdated()
            return
        }
        location = value
        map.loadDataWithBaseURL("https://unpkg.com", mapHtml(value.latitude, value.longitude), "text/html", "utf-8", null)
        map.visibility = View.VISIBLE
        mapPlaceholder.visibility = View.GONE
        renderUpdated()
    }

    private fun renderUpdated() {
        val current = location
        locationUpdated.text = if (current?.updatedMs == null) {
            VehicleFormatter.EMPTY
        } else {
            getString(R.string.vehicle_location_updated, VehicleFormatter.updated(current.updatedMs))
        }
    }

    private fun showNote(text: String) {
        locationNote.text = text
        locationNote.visibility = View.VISIBLE
    }

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }

    private fun setLocating(busy: Boolean) {
        locating.visibility = if (busy) View.VISIBLE else View.GONE
        locateButton.isEnabled = !busy
        locateButton.alpha = if (busy) 0.4f else 1f
    }

    private fun setActionsEnabled(enabled: Boolean) {
        for (view in actionViews) {
            view.isEnabled = enabled
            view.alpha = if (enabled) 1f else 0.5f
        }
    }

    private fun showMenu(anchor: View) {
        val isFavorite = UconnectStore.favorite(this) == vehicle.vin
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_STATUS, 0, R.string.menu_status)
            menu.add(0, MENU_JSON, 1, R.string.menu_json)
            menu.add(0, MENU_FAVORITE, 2, if (isFavorite) R.string.menu_favorite_remove else R.string.menu_favorite_add)
            menu.add(0, MENU_NICKNAME, 3, R.string.menu_nickname)
            if (SessionHolder.vehicles.size > 1) menu.add(0, MENU_SWITCH, 4, R.string.menu_switch_vehicle)
            menu.add(0, MENU_LOGOUT, 5, R.string.menu_logout)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_STATUS -> startActivity(Intent(this@VehicleActivity, StatusActivity::class.java))
                    MENU_JSON -> startActivity(Intent(this@VehicleActivity, JsonActivity::class.java))
                    MENU_FAVORITE -> toggleFavorite(isFavorite)
                    MENU_NICKNAME -> editNickname()
                    MENU_SWITCH -> goBack()
                    MENU_LOGOUT -> Navigation.confirmLogout(this@VehicleActivity)
                }
                true
            }
        }.show()
    }

    private fun toggleFavorite(isFavorite: Boolean) {
        if (isFavorite) {
            UconnectStore.setFavorite(this, null)
            Toast.makeText(this, R.string.garage_favorite_cleared, Toast.LENGTH_SHORT).show()
        } else {
            UconnectStore.setFavorite(this, vehicle.vin)
            Toast.makeText(this, getString(R.string.garage_favorite_set, VehicleNicknames.apply(this, vehicle).name), Toast.LENGTH_SHORT).show()
        }
    }

    private fun mapHtml(lat: Double, lon: Double): String {
        val la = String.format(Locale.US, "%.6f", lat)
        val lo = String.format(Locale.US, "%.6f", lon)
        return """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
            <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
            <style>
              html,body,#map{height:100%;margin:0;background:#0A0C11}
              .leaflet-tile-pane{filter:invert(1) hue-rotate(180deg) brightness(.9) contrast(.9)}
              .leaflet-control-attribution{background:rgba(10,12,17,.7)!important;color:#99A2B2!important;font-size:9px}
              .leaflet-control-attribution a{color:#99A2B2!important}
            </style>
            </head><body><div id="map"></div><script>
            var m=L.map('map',{zoomControl:false,attributionControl:true}).setView([$la,$lo],16);
            m.attributionControl.setPrefix(false);
            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
              {maxZoom:19,attribution:'© OpenStreetMap'}).addTo(m);
            L.circle([$la,$lo],{radius:40,color:'#EF5350',weight:1,fillColor:'#EF5350',fillOpacity:.15}).addTo(m);
            L.circleMarker([$la,$lo],{radius:8,color:'#FFFFFF',weight:3,fillColor:'#EF5350',fillOpacity:1}).addTo(m);
            </script></body></html>
        """.trimIndent()
    }

    companion object {
        private const val EXTRA_VIN = "vin"
        private const val EXTRA_FROM_LIST = "from_list"
        private const val MENU_STATUS = 1
        private const val MENU_JSON = 2
        private const val MENU_FAVORITE = 3
        private const val MENU_SWITCH = 4
        private const val MENU_LOGOUT = 5
        private const val MENU_NICKNAME = 6
        private const val LOCATE_POLLS = 8
        private const val LOCATE_INTERVAL_MS = 4_000L
        private const val TICK_MS = 30_000L

        fun intent(context: Context, vin: String, fromList: Boolean): Intent =
            Intent(context, VehicleActivity::class.java)
                .putExtra(EXTRA_VIN, vin)
                .putExtra(EXTRA_FROM_LIST, fromList)
    }
}

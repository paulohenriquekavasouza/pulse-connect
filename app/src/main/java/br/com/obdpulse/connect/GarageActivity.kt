package br.com.obdpulse.connect

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

class GarageActivity : Activity() {

    private val scope = MainScope()
    private lateinit var list: LinearLayout
    private val cards = LinkedHashMap<String, View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SessionHolder.session == null || SessionHolder.vehicles.isEmpty()) {
            AuthFlow.relogin(this, null)
            return
        }
        setContentView(R.layout.activity_garage)
        list = findViewById(R.id.garage_list)
        findViewById<View>(R.id.garage_menu).setOnClickListener { showMenu(it) }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) updateFavorites()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render() {
        list.removeAllViews()
        cards.clear()
        for (vehicle in SessionHolder.vehicles.map { VehicleNicknames.apply(this, it) }) {
            val card = layoutInflater.inflate(R.layout.item_vehicle, list, false)
            card.findViewById<TextView>(R.id.vehicle_name).text = vehicle.name
            card.findViewById<TextView>(R.id.vehicle_subtitle).apply {
                text = vehicle.subtitle
                visibility = if (vehicle.subtitle.isBlank()) View.GONE else View.VISIBLE
            }
            ImageLoader.bindVehicle(scope, card.findViewById<ImageView>(R.id.vehicle_image), vehicle.imageUrl)
            card.findViewById<ImageButton>(R.id.vehicle_favorite).setOnClickListener { toggleFavorite(vehicle) }
            card.setOnClickListener {
                startActivity(VehicleActivity.intent(this, vehicle.vin, fromList = true))
            }
            list.addView(card)
            cards[vehicle.vin] = card
        }
        updateFavorites()
    }

    private fun updateFavorites() {
        val favorite = UconnectStore.favorite(this)
        for ((vin, card) in cards) {
            val selected = vin == favorite
            card.findViewById<ImageButton>(R.id.vehicle_favorite).apply {
                setImageResource(if (selected) R.drawable.ic_star else R.drawable.ic_star_outline)
                imageTintList = getColorStateList(if (selected) R.color.amber else R.color.text_secondary)
                contentDescription = getString(if (selected) R.string.menu_favorite_remove else R.string.menu_favorite_add)
            }
            card.findViewById<View>(R.id.vehicle_favorite_badge).visibility = if (selected) View.VISIBLE else View.GONE
        }
    }

    private fun toggleFavorite(vehicle: UconnectVehicle) {
        if (UconnectStore.favorite(this) == vehicle.vin) {
            UconnectStore.setFavorite(this, null)
            Toast.makeText(this, R.string.garage_favorite_cleared, Toast.LENGTH_SHORT).show()
        } else {
            UconnectStore.setFavorite(this, vehicle.vin)
            Toast.makeText(this, getString(R.string.garage_favorite_set, vehicle.name), Toast.LENGTH_SHORT).show()
        }
        updateFavorites()
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_LOGOUT, 0, R.string.menu_logout)
            setOnMenuItemClickListener {
                Navigation.confirmLogout(this@GarageActivity)
                true
            }
        }.show()
    }

    private companion object {
        const val MENU_LOGOUT = 1
    }
}

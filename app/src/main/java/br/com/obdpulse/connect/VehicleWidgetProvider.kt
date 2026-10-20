package br.com.obdpulse.connect

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class VehicleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                render(app, manager, appWidgetIds)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        onUpdate(context, manager, intArrayOf(appWidgetId))
    }

    companion object {

        private const val COMPACT_MAX_HEIGHT_DP = 110

        private const val IMAGE_TIMEOUT_MS = 8_000L

        private val TILES = mapOf(
            UconnectClient.CMD_LOCK to R.id.widget_lock,
            UconnectClient.CMD_UNLOCK to R.id.widget_unlock,
            UconnectClient.CMD_START to R.id.widget_start,
            UconnectClient.CMD_STOP to R.id.widget_stop,
        )

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, VehicleWidgetProvider::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, VehicleWidgetProvider::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }

        private suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val vehicle = VehicleRouter.widgetVehicle(
                UconnectStore.vehicles(context),
                UconnectStore.favorite(context),
                UconnectStore.vin(context),
            )
            val image = vehicle?.imageUrl?.let { url -> withTimeoutOrNull(IMAGE_TIMEOUT_MS) { ImageLoader.load(context, url) } }?.let(::thumbnail)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val compact = build(context, vehicle, image, compact = true)
                val full = build(context, vehicle, image, compact = false)
                val views = RemoteViews(
                    mapOf(
                        SizeF(100f, 40f) to compact,
                        SizeF(100f, COMPACT_MAX_HEIGHT_DP.toFloat()) to full,
                    ),
                )
                for (id in ids) manager.updateAppWidget(id, views)
                return
            }
            for (id in ids) {
                val options = manager.getAppWidgetOptions(id)
                val height = maxOf(
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0),
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0),
                )
                manager.updateAppWidget(id, build(context, vehicle, image, compact = height < COMPACT_MAX_HEIGHT_DP))
            }
        }

        private fun build(context: Context, vehicle: UconnectVehicle?, image: Bitmap?, compact: Boolean): RemoteViews {
            val views = RemoteViews(context.packageName, if (compact) R.layout.widget_vehicle_compact else R.layout.widget_vehicle)
            views.setOnClickPendingIntent(R.id.widget_header, openApp(context))
            if (image != null) views.setImageViewBitmap(R.id.widget_image, image)
            else views.setImageViewResource(R.id.widget_image, R.drawable.ic_car_widget)

            val signedIn = vehicle != null && UconnectStore.isSaved(context)
            val status = if (signedIn) WidgetState.get(context) else null
            val busy = status?.busy == true
            views.setViewVisibility(R.id.widget_progress, if (busy) View.VISIBLE else View.GONE)
            val supported = if (signedIn) VehicleCommands.supported(vehicle!!) else emptyList()
            views.setViewVisibility(R.id.widget_actions, if (supported.isEmpty()) View.GONE else View.VISIBLE)

            if (!compact) {
                views.setTextViewText(R.id.widget_name, if (signedIn) vehicle!!.name else context.getString(R.string.app_name))
                val text = when {
                    !signedIn -> context.getString(R.string.widget_sign_in)
                    status == null -> null
                    status.busy || status.error -> status.text
                    else -> context.getString(R.string.widget_sent, status.text, VehicleFormatter.stamp(status.atMs))
                } ?: when {
                    supported.isEmpty() -> context.getString(R.string.vehicle_no_actions)
                    else -> vehicle!!.subtitle.ifBlank { context.getString(R.string.widget_ready) }
                }
                views.setTextViewText(R.id.widget_status, text)
                views.setTextColor(
                    R.id.widget_status,
                    context.getColor(if (status?.error == true && !busy) R.color.accent else R.color.text_secondary),
                )
            }

            for ((command, tile) in TILES) {
                val spec = supported.firstOrNull { it.command == command }
                views.setViewVisibility(tile, if (spec == null) View.GONE else View.VISIBLE)
                if (spec != null) views.setOnClickPendingIntent(tile, commandIntent(context, vehicle!!.vin, spec))
            }
            return views
        }

        private fun commandIntent(context: Context, vin: String, spec: CommandSpec): PendingIntent {
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val code = VehicleCommands.ALL.indexOf(spec) + 1
            return PendingIntent.getActivity(context, code, WidgetConfirmActivity.intent(context, vin, spec.command), flags)
        }

        private fun openApp(context: Context): PendingIntent {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(context, LoginActivity::class.java)
            return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun thumbnail(bitmap: Bitmap): Bitmap {
            val width = 300
            if (bitmap.width <= width) return bitmap
            val height = bitmap.height * width / bitmap.width
            return Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
    }
}

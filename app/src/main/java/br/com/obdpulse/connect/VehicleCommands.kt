package br.com.obdpulse.connect

import android.content.Context

data class CommandSpec(
    val command: String,
    val label: Int,
    val icon: Int,
    val sending: Int,
    val sent: Int,
    val sentShort: Int,
    val biometricTitle: Int,
    val accent: Boolean = false,
)

object VehicleCommands {

    val ALL = listOf(
        CommandSpec(
            UconnectClient.CMD_LOCK, R.string.action_lock, R.drawable.ic_lock,
            R.string.action_locking, R.string.action_locked, R.string.action_locked_short,
            R.string.biometric_lock_title,
        ),
        CommandSpec(
            UconnectClient.CMD_UNLOCK, R.string.action_unlock, R.drawable.ic_lock_open,
            R.string.action_unlocking, R.string.action_unlocked, R.string.action_unlocked_short,
            R.string.biometric_unlock_title,
        ),
        CommandSpec(
            UconnectClient.CMD_START, R.string.action_start, R.drawable.ic_power,
            R.string.action_starting, R.string.action_started, R.string.action_started_short,
            R.string.biometric_start_title, accent = true,
        ),
        CommandSpec(
            UconnectClient.CMD_STOP, R.string.action_stop, R.drawable.ic_power_off,
            R.string.action_stopping, R.string.action_stopped, R.string.action_stopped_short,
            R.string.biometric_stop_title,
        ),
    )

    fun of(command: String): CommandSpec? = ALL.firstOrNull { it.command == command }

    fun supported(vehicle: UconnectVehicle): List<CommandSpec> = ALL.filter { it.command in vehicle.commands }
}

object UconnectErrors {

    fun describe(context: Context, error: Exception): String =
        if (error is UconnectNetworkException) {
            error.message.orEmpty()
        } else {
            context.getString(R.string.uconnect_status_error, error.message.orEmpty())
        }

    fun command(context: Context, error: Exception, commandStarted: Boolean): String {
        val text = describe(context, error)
        if (error !is UconnectNetworkException) return text
        val outcome = if (commandStarted && error.sent) R.string.command_maybe_sent else R.string.command_not_sent
        return text + " " + context.getString(outcome)
    }
}

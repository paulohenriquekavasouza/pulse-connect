package br.com.obdpulse.connect

import org.json.JSONObject

data class UconnectVehicle(
    val vin: String,
    val name: String,
    val subtitle: String,
    val commands: Set<String>,
    val imageUrl: String?,
)

data class VehicleLocation(
    val latitude: Double,
    val longitude: Double,
    val updatedMs: Long?,
)

data class AwsCreds(
    val accessKey: String,
    val secretKey: String,
    val sessionToken: String,
)

data class UconnectSession(
    val uid: String,
    val creds: AwsCreds,
)

open class UconnectException(message: String) : Exception(message)

class UconnectAuthException(message: String) : UconnectException(message)

class UconnectNetworkException(message: String, val sent: Boolean) : UconnectException(message)

data class VehicleData(
    val vehicle: JSONObject?,
    val status: JSONObject?,
    val remoteStatus: JSONObject?,
    val location: JSONObject?,
    val errors: Map<String, String>,
    val extras: Map<String, JSONObject> = emptyMap(),
)

class PendingAction(
    val type: Type,
    val vin: String?,
    val command: String?,
) {
    enum class Type { COMMAND, LOCATE }
}

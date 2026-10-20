package br.com.obdpulse.connect

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object UconnectStore {
    private const val FILE = "uconnect"
    private const val KEY_ALIAS = "uconnect_cred_key"
    private const val KEY_EMAIL = "email"
    private const val KEY_PASSWORD = "password"
    private const val KEY_PIN = "pin"
    private const val KEY_SAVE = "save"
    private const val KEY_VIN = "vin"
    private const val KEY_FAVORITE = "favorite_vin"
    private const val KEY_VEHICLES = "vehicles"

    fun isSaved(context: Context): Boolean = prefs(context).getBoolean(KEY_SAVE, false)

    fun email(context: Context): String? = decrypt(context, KEY_EMAIL)
    fun password(context: Context): String? = decrypt(context, KEY_PASSWORD)
    fun pin(context: Context): String? = decrypt(context, KEY_PIN)
    fun vin(context: Context): String? = prefs(context).getString(KEY_VIN, null)

    fun save(context: Context, email: String, password: String, pin: String) {
        prefs(context).edit()
            .putString(KEY_EMAIL, encrypt(email))
            .putString(KEY_PASSWORD, encrypt(password))
            .putString(KEY_PIN, encrypt(pin))
            .putBoolean(KEY_SAVE, true)
            .apply()
    }

    fun saveVin(context: Context, vin: String) {
        if (vin(context) == vin) return
        prefs(context).edit().putString(KEY_VIN, vin).apply()
        VehicleWidgetProvider.refresh(context)
    }

    fun vehicles(context: Context): List<UconnectVehicle> = VehicleCatalog.decode(prefs(context).getString(KEY_VEHICLES, null))

    fun saveVehicles(context: Context, vehicles: List<UconnectVehicle>) {
        prefs(context).edit().putString(KEY_VEHICLES, VehicleCatalog.encode(vehicles)).apply()
        VehicleWidgetProvider.refresh(context)
    }

    fun favorite(context: Context): String? = prefs(context).getString(KEY_FAVORITE, null)

    fun setFavorite(context: Context, vin: String?) {
        prefs(context).edit().apply { if (vin == null) remove(KEY_FAVORITE) else putString(KEY_FAVORITE, vin) }.apply()
        VehicleWidgetProvider.refresh(context)
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        WidgetState.clear(context)
        VehicleWidgetProvider.refresh(context)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun decrypt(context: Context, field: String): String? {
        val stored = prefs(context).getString(field, null) ?: return null
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, 12)
            val data = raw.copyOfRange(12, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val data = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + data, Base64.NO_WRAP)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }
}

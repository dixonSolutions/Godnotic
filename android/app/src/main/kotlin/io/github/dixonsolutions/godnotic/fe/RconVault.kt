package io.github.dixonsolutions.godnotic.fe

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The rcon passwords the player chose to remember, one per server (host:port).
 *
 * Each is sealed with AES-256-GCM; the key lives in the Android Keystore (never in the app's
 * files) and the server address is bound in as the associated data, so an entry copied under
 * another address does not open. The vault never logs, prints or returns the text of a
 * password except from [load], and keeps it out of config.cfg, crash reports and chat exports
 * by never handing it to any of them.
 */
class RconVault(private val blobs: Blobs, private val keys: () -> SecretKey) {
    /** Where the sealed entries sit (SharedPreferences on Android, a map in tests). */
    interface Blobs {
        fun get(name: String): String?
        fun put(name: String, value: String)
        fun remove(name: String)
    }

    fun save(server: String, password: String) {
        val addr = normalize(server)
        if (addr.isEmpty() || password.isEmpty()) return
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, keys())
        c.updateAAD(addr.toByteArray())
        val sealed = c.doFinal(password.toByteArray())
        blobs.put(PREFIX + addr, hex(c.iv + sealed))
    }

    /** The remembered password for [server], or null: none, or it no longer opens (a lost key). */
    fun load(server: String): String? {
        val addr = normalize(server)
        val raw = blobs.get(PREFIX + addr) ?: return null
        return try {
            val all = unhex(raw)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, keys(), GCMParameterSpec(128, all, 0, IV_BYTES))
            c.updateAAD(addr.toByteArray())
            String(c.doFinal(all, IV_BYTES, all.size - IV_BYTES))
        } catch (e: Exception) {
            blobs.remove(PREFIX + addr)
            null
        }
    }

    fun forget(server: String) = blobs.remove(PREFIX + normalize(server))

    companion object {
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val PREFIX = "rcon:"
        private const val ALIAS = "godnotic.rcon.v1"

        /** "Host:26000" and "host" are the same server (26000 is the default port). */
        fun normalize(server: String): String {
            val s = server.trim().lowercase()
            if (s.isEmpty()) return ""
            return if (s.contains(':')) s else "$s:26000"
        }

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        fun create(ctx: Context): RconVault {
            val prefs = ctx.getSharedPreferences("rcon_vault", Context.MODE_PRIVATE)
            return RconVault(object : Blobs {
                override fun get(name: String) = prefs.getString(name, null)
                override fun put(name: String, value: String) { prefs.edit().putString(name, value).apply() }
                override fun remove(name: String) { prefs.edit().remove(name).apply() }
            }, ::keystoreKey)
        }

        private fun keystoreKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            return gen.generateKey()
        }
    }
}

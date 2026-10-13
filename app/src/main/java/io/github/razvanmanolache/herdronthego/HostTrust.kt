package io.github.razvanmanolache.herdronthego

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.security.MessageDigest
import java.util.Base64

interface KnownHostsStore {
    /** Version-two pins hold Base64 key blobs, not legacy nonstandard fingerprints. */
    fun get(port: Int, host: String): String?
    fun put(port: Int, host: String, key: String)
}

fun hostFingerprint(key: ByteArray): String = "SHA256:" + Base64.getEncoder().withoutPadding()
    .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))

/** JSch calls this during key exchange, before sending any authentication secret. */
class VerifyingHostKeys(
    private val store: KnownHostsStore, private val host: String, private val port: Int,
    private val acceptUnknown: (String) -> Boolean,
) : HostKeyRepository {
    var rejection: String? = null
        private set

    override fun check(server: String?, key: ByteArray): Int {
        val encoded = Base64.getEncoder().encodeToString(key)
        val known = store.get(port, host)
        if (known != null) {
            if (known == encoded) return HostKeyRepository.OK
            rejection = "Host key CHANGED for $host:$port. Verify the server before reconnecting"
            return HostKeyRepository.CHANGED
        }
        if (!acceptUnknown(hostFingerprint(key))) {
            rejection = "Host key rejected for $host:$port"
            return HostKeyRepository.NOT_INCLUDED
        }
        store.put(port, host, encoded)
        return HostKeyRepository.OK
    }

    override fun getKnownHostsRepositoryID(): String = "Herdr pinned host keys"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    override fun add(hostkey: HostKey?, userinfo: UserInfo?) { /* Pins are saved only by an accepted check. */ }
    override fun remove(host: String?, type: String?) { /* Never allow JSch to overwrite a pin. */ }
    override fun remove(host: String?, type: String?, key: ByteArray?) { }
}

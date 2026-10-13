package io.github.razvanmanolache.herdronthego

import org.junit.Assert.*
import org.junit.Test

class PairAddressTest {
    private val pin = "a".repeat(64)
    @Test fun acceptsOnlyAnExplicitPhysicalCertificatePin() {
        assertEquals(PairAddress("pc.example", 7443, pin), PairAddress.parse("herdr-pair://pc.example:7443?fingerprint=$pin"))
        assertEquals(pin, PairAddress.parse("herdr-pair://pc.example?fingerprint=${pin.uppercase()}").fingerprint)
    }
    @Test fun rejectsUnpinnedOrAmbiguousAddresses() {
        listOf("http://pc.example", "herdr-pair://pc.example", "herdr-pair://pc.example?fingerprint=123456",
            "herdr-pair://user:password@pc.example?fingerprint=$pin", "herdr-pair://pc.example/path?fingerprint=$pin",
            "herdr-pair://pc.example?fingerprint=$pin&other=x", "herdr-pair://pc.example:0?fingerprint=$pin",
            "herdr-pair://pc.example?fingerprint=$pin#fragment").forEach { value ->
            assertThrows(Exception::class.java) { PairAddress.parse(value) }
        }
    }
    @Test fun sshAndPairedDestinationsCannotShareAnIdentity() {
        val ssh = SavedConnection("pc", 7443, "user", "named")
        val paired = ssh.copy(protocol = "paired", certificate = pin)
        assertNotEquals(ssh.key, paired.key)
        assertEquals("pc:7443:user", ssh.key)
        assertEquals(paired.key, paired.copy(session = "other").key)
    }
}

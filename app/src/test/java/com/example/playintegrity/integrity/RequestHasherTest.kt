package com.example.playintegrity.integrity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestHasherTest {

    // Same fixture as server/test/unit.test.js — keeps client and server canonical formats in lockstep.
    private val fixture = ConfigRequest(
        requestId = "00112233445566778899aabbccddeeff",
        timestampMillis = 1_700_000_000_000,
        packageName = "com.example.playintegrity",
        appVersionCode = 1,
        configScope = "default",
    )

    @Test
    fun canonicalPayload_matchesServerFormat() {
        assertEquals(
            "v1\nPOST\n/v1/config\n00112233445566778899aabbccddeeff\n1700000000000\ncom.example.playintegrity\n1\ndefault",
            RequestHasher.canonicalPayload(fixture),
        )
    }

    @Test
    fun sha256Hex_knownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            RequestHasher.sha256Hex("abc"),
        )
    }

    @Test
    fun requestHash_matchesServerFixture() {
        // Value printed by: node -e "import('./src/integrity/requestHash.js').then(m=>console.log(m.expectedRequestHash({...})))"
        assertEquals(SERVER_FIXTURE_HASH, RequestHasher.requestHash(fixture))
    }

    @Test
    fun requestHash_changesWhenAnyFieldChanges() {
        val base = RequestHasher.requestHash(fixture)
        assertNotEquals(base, RequestHasher.requestHash(fixture.copy(configScope = "admin")))
        assertNotEquals(base, RequestHasher.requestHash(fixture.copy(timestampMillis = fixture.timestampMillis + 1)))
        assertNotEquals(base, RequestHasher.requestHash(fixture.copy(requestId = "f".repeat(32))))
    }

    @Test
    fun newRequestId_is32HexCharsAndUnique() {
        val ids = (1..1000).map { RequestHasher.newRequestId() }.toSet()
        assertEquals(1000, ids.size)
        assertTrue(ids.all { Regex("^[0-9a-f]{32}$").matches(it) })
    }

    companion object {
        const val SERVER_FIXTURE_HASH = "af0127633ddef9f563a1a9c953ee92883e6fa9ce59ce4eaf31bd239b0319a269"
    }
}

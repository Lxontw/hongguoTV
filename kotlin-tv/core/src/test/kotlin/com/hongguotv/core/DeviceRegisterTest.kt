package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class DeviceRegisterTest {
    @Test
    fun payloadEncryptionAndHeaderGeneration() {
        val device = linkedMapOf("device_type" to "25053RT47C", "device_brand" to "Redmi")
        val payload = DeviceRegister.payload(device, 1729600000000L)
        assertNotNull(payload)
        assertTrue(payload.size > 100)
        // Verify MAGIC header
        assertEquals(0x74.toByte(), payload[0])
        assertEquals(0x63.toByte(), payload[1])
        assertEquals(0x05.toByte(), payload[2])
        assertEquals(0x10.toByte(), payload[3])
        assertEquals(0x00.toByte(), payload[4])
        assertEquals(0x00.toByte(), payload[5])

        val headers = DeviceRegister.headers(payload, device, 1729600000000L)
        assertEquals("application/octet-stream;tt-data=a", headers["Content-Type"])
        assertEquals("gzip", headers["log-encode-type"])
        assertEquals("8662", headers["x-ss-dp"])
        assertEquals(digest("MD5", payload).hex().uppercase(), headers["x-ss-stub"])
    }

    @Test
    fun parseSuccessResponse() {
        val sampleJson = """{"server_time":1729600000,"device_id":4175314086266699,"install_id":4175314086270795,"device_token":"TOKEN123","new_user":1}"""
        val cookies = listOf("store-region=cn-gd; Path=/", "install_id=4175314086270795; Path=/")
        val registered = DeviceRegister.parse(sampleJson, cookies)
        assertNotNull(registered)
        assertEquals("4175314086266699", registered!!.deviceId)
        assertEquals("4175314086270795", registered.installId)
        assertEquals("TOKEN123", registered.deviceToken)
        assertTrue(registered.newUser)
        assertEquals("cn-gd", registered.cookies["store-region"])
        assertEquals("4175314086270795", registered.cookies["install_id"])
    }

    @Test
    fun parseRejectedResponse() {
        val rejectedJson = """{"device_id":0,"install_id":0}"""
        val registered = DeviceRegister.parse(rejectedJson)
        assertNull(registered)
    }
}

package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class PlayerSignerTest {
    private data class GoldenRow(val query: String, val ts: Long, val randField: Long, val want: String)

    private val golden = listOf(
        GoldenRow(
            query = "aid=1967&device_id=dev_aaaaaaaa&iid=111",
            ts = 1700000000L,
            randField = 659662259L,
            want = "8oHyrVYEy1xDCDlx64ko40qC6JC9VYY/ZkjJzEl50iSFDw8Yfph1hupLxfKrA9GswSEz0Yi4goA7OQ4u+H8ECdOaQDL4GduQ3LULSq9Q4iSEGbS3L6G24GXChtnmPr6n46M2PLlKpXvF7uE0lhH4yD+X69FpdM41gQwx5Y5U3NdwXFaxlqYFszyp7dlUE6BEBWqwDsiAH8Jk9TGKr/jfHt4fVmMc9zRyNRdGeV0x6aP//x7aV+8Po8a/9KK3UNG5KEpmIgzRT6NUttkBP9gRNUh3"
        ),
        GoldenRow(
            query = "aid=1967&device_id=dev_bbbbbbbbbbbbbbbb&iid=222",
            ts = 1700000001L,
            randField = 1315257197L,
            want = "8oGWgilKQHD4eCdd0VB0vBSbTAkv/3xWE2YWJkxic35CgXkxrUlMTz05HJAak5b5Xx9yxohnoAAcEX//MI9t7XBBtnPwOTaJzkJgBLaYOOotfI7YuqhsEbE5BB363bhKz/Qw/+MI5gH6pgdAXfsRj68u7Qwu3gqCY1NYstqNZFFVrph7dWtXDBYM1aoVEyfkYD2haoaOr9D/gl1zdzAAzbW7we6lr9/zFApbuNsbr2dagdUrQc51QNN0HBOtIkpVBVunDwXM8qR271Ei+Pa4EgKy"
        ),
        GoldenRow(
            query = "aid=1967&device_id=X&iid=333",
            ts = 1234567890L,
            randField = 554000487L,
            want = "8oEOCRTb0LQf+M+MsliYu4M9kBzAlzXvFgL/Ub198hyoBGxYfNXLAENmH+K/10JQ+Rbhm2zRxNtNv5wVd885xcb6C6F10zp/9jVeGHGz8plz8W3UXHHNrFKjJptjQElGDbyaVLSKt56+HfwO7ENYMfjVv/5PNi1upVciiHqyOPDDAtwi/fZkwBoU1kC7RolMOuedn4lCg/s0CjnNvxFT0QpF/K0yoW3Zpu30tkadIET7WbZ3+7KT3c1yAA6RKWI4mmw="
        ),
        GoldenRow(
            query = "aid=1967&device_id=test_device_id&iid=456&version_name=6.5.1.32",
            ts = 1700000000L,
            randField = 420072733L,
            want = "8oHu8LnZm0OtJJIaSr1427D+yeH70h5aZTVjoSW7MfFi/hk1LhjTqsEEWYpwcHfK4/2SXuJxTtopRcPeHzVpeFpUD4B4yxEG7REejpCZ1OqeAaLsw/lMebwUjUHrUjnAZCKq0QyimnwdiTiYIsH3GfiK/xlPcFcARqwX7RkI1qnnWUgiJVDEacSm8yhBat6MvDVOa7+U2MgcLu7Q26pFzX8VX5F+ryP76ETs1nRGLmlhHkwyiKb6CETstvGQnJgocgZBNNvlHD2/P+4LWjFMvhNK"
        )
    )

    @Test
    fun primaryProtoBufExactBeanAndOverflowVarints() {
        val bean = PlayerSigner.ProtoBuf()
            .putVarint(1, 1077940818L)
            .putVarint(2, 2L)
            .putVarint(3, 305419896L)
            .putUtf8(4, "1967")
            .putUtf8(5, "test_device_id")
            .putUtf8(6, "1611921764")
            .putUtf8(7, "")
            .putUtf8(8, "")
            .putVarint(9, 0L)
            .putUtf8(10, "")
            .putUtf8(11, "")
            .putVarint(12, 3400000000L)
            .putBytes(13, hex("106e34a2b8c7"))
            .putBytes(14, hex("ac5095fc674e"))
            .putProtobuf(
                15,
                PlayerSigner.ProtoBuf()
                    .putVarint(1, 1L)
                    .putVarint(2, 1L)
                    .putVarint(3, 1L)
                    .putVarint(7, 0xc792ecccL)
            )
            .putUtf8(16, "")
            .putUtf8(20, "none")
            .putVarint(21, 738L)
            .putProtobuf(
                23,
                PlayerSigner.ProtoBuf()
                    .putUtf8(1, "NX551J")
                    .putVarint(2, 8196L)
                    .putVarint(4, 0x80e0d800L)
            )
            .putVarint(25, 2L)

        assertEquals(
            "08d2a4808204100218f8acd191012204313936372a0e746573745f6465766963655f6964320a313631313932313736343a004200480052005a006080c49fd50c6a06106e34a2b8c77206ac5095fc674e7a0c08011001180138ccd9cbbc0c820100a201046e6f6e65a801e205ba01110a064e583535314a1084402080b0838708c80102",
            bean.toBuf().hex()
        )
        assertEquals(
            "08ffffffff0f1005",
            PlayerSigner.ProtoBuf().putVarint(1, -1L).putVarint(2, 4294967301L).toBuf().hex()
        )
    }

    @Test
    fun primarySimonCiphertextAndLadonGolden() {
        val k = longArrayOf(1111111111111111111L, 2222222222222222222L, 3333333333333333333L, 4444444444444444444L)
        val encrypted = PlayerSigner.Simon.encrypt(1234567890123456789L, java.lang.Long.parseUnsignedLong("9876543210987654321"), k)
        assertEquals(6071150433487837324L, encrypted[0])
        assertEquals(2027321285990159935L, encrypted[1])

        assertEquals(
            "AQIDBKM7Y7bWnOIUXmTybD6/MpGbuge4sBwUJsyHVivhtMsH",
            PlayerSigner.Ladon.encrypt(1700000000L, 1611921764L, 1967L, hex("01020304"))
        )
    }

    @Test
    fun primaryArgus551ExactCiphertextVectors() {
        assertEquals("106e34a2b8c7", PlayerSigner.Argus.bodyHash("").hex())
        assertEquals("106e34a2b8c7", PlayerSigner.Argus.queryHash("").hex())
        assertEquals("925b8d86b20f", PlayerSigner.Argus.queryHash("aid=1967&device_id=123").hex())
        assertEquals("5048b6e949d0", PlayerSigner.Argus.bodyHash("ac3ef48caa08fa3ed5e025da69edc645").hex())

        for (row in golden) {
            val device = PlayerSigner.queryParam(row.query, "device_id").orEmpty()
            assertEquals(row.want, PlayerSigner.Argus.sign(row.query, "", row.ts, 1967L, 1611921764L, device, "", row.randField))
            val signed = PlayerSigner.signPlayer(
                query = row.query,
                body = null,
                ts = row.ts,
                randField = row.randField,
                ladonRandom = hex("01020304")
            )
            assertEquals(row.want, signed["x-argus"])
        }
    }

    @Test
    fun signPlayerBindsRawBodyAndOmitsStubWhenBodyIsEmpty() {
        val query = "aid=1967&device_id=test_device_id&iid=456&version_name=6.5.1.32"
        val ts = 1700000000L
        val randField = 420072733L
        val ladonRandom = hex("01020304")

        val empty = PlayerSigner.signPlayer(query, null, ts, randField, ladonRandom)
        val zero = PlayerSigner.signPlayer(query, ByteArray(0), ts, randField, ladonRandom)
        assertEquals(empty, zero)
        assertEquals(golden[3].want, empty["x-argus"])
        assertEquals("AQIDBKM7Y7bWnOIUXmTybD6/MpGbuge4sBwUJsyHVivhtMsH", empty["x-ladon"])
        assertEquals("1700000000", empty["x-khronos"])
        assertEquals("1700000000000", empty["x-ss-req-ticket"])
        assertFalse(empty.containsKey("x-ss-stub"))

        val body = """{"x":1}""".toByteArray(Charsets.UTF_8)
        val raw = PlayerSigner.signPlayer(query, body, ts, randField, ladonRandom)
        assertEquals("ac3ef48caa08fa3ed5e025da69edc645", raw["x-ss-stub"])
        assertNotEquals(empty["x-argus"], raw["x-argus"])
        assertEquals(empty["x-ladon"], raw["x-ladon"])
    }
}

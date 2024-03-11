package com.breadwallet.tools.crypto

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

const val MainnetVersion = 0x0488_ADE4
const val TestnetVersion = 0x0435_8394

class HDKey(val version: Int, val seed: ByteArray) {
    fun toExtendedKey(): String {
        val secretKeySpec = SecretKeySpec("Bitcoin seed".toByteArray(), "HmacSHA512")
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(secretKeySpec)
        val hmac = mac.doFinal(seed)
        val privateKey = hmac.take(32)
        val chainCode = hmac.takeLast(32)

        var result = byteArrayOf()
        for (i in 3 downTo 0) result += (version shr (i*8)).toByte()
        result += byteArrayOf(0)  // depth
        result += byteArrayOf(0, 0, 0, 0)  // fingerprint
        result += byteArrayOf(0, 0, 0, 0)  // child num
        result += chainCode
        result += byteArrayOf(0)
        result += privateKey
        val digest = MessageDigest.getInstance("SHA-256")
        val checksum = digest.digest(digest.digest(result)).take(4)
        result += checksum
        return Base58.encode(result)
    }
}

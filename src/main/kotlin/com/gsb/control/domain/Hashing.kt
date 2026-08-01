package com.gsb.control.domain

import java.security.MessageDigest

/** Pure, deterministic SHA-256 hex digest. Lives in the domain so [EvaluationResult.contentHash] carries no framework dependency. */
object Hashing {
    fun sha256Hex(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

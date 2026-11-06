package com.suj.zhuanluji

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Checks the complete compressed archive, including a resumed prefix, before extraction. */
internal object ModelPackageIntegrity {
    class InvalidPackage : IllegalArgumentException("Model package size or SHA-256 mismatch")

    fun verify(
        input: InputStream,
        expectedSize: Long,
        expectedSha256: String,
        copyTo: OutputStream? = null,
        progress: (Long) -> Unit = {},
    ) {
        require(expectedSize >= 0)
        require(expectedSha256.matches(Regex("[0-9a-f]{64}")))
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        var count = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            // Bound local imports as well as downloads, regardless of provider metadata.
            if (n.toLong() > expectedSize - count) throw InvalidPackage()
            count += n
            digest.update(buffer, 0, n)
            copyTo?.write(buffer, 0, n)
            progress(count)
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (count != expectedSize || actual != expectedSha256) throw InvalidPackage()
    }
}

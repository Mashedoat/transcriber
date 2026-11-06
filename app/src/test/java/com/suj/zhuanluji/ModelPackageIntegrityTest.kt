package com.suj.zhuanluji

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class ModelPackageIntegrityTest {
    private val abc = "abc".toByteArray()
    // NIST SHA-256 test vector, independent of the implementation.
    private val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test fun acceptsKnownVectorAndCopiesExactBytes() {
        val copy = ByteArrayOutputStream()
        var progress = 0L
        ModelPackageIntegrity.verify(abc.inputStream(), 3, sha, copy) { progress = it }
        assertArrayEquals(abc, copy.toByteArray())
        assertEquals(3L, progress)
    }

    @Test fun rejectsSameSizeCorruption() {
        assertThrows(ModelPackageIntegrity.InvalidPackage::class.java) {
            ModelPackageIntegrity.verify("abd".byteInputStream(), 3, sha)
        }
    }

    @Test fun rejectsTruncatedArchive() {
        assertThrows(ModelPackageIntegrity.InvalidPackage::class.java) {
            ModelPackageIntegrity.verify("ab".byteInputStream(), 3, sha)
        }
    }

    @Test fun rejectsTrailingBytesWithoutCopyingPastLimit() {
        val copy = ByteArrayOutputStream()
        assertThrows(ModelPackageIntegrity.InvalidPackage::class.java) {
            ModelPackageIntegrity.verify("abcd".byteInputStream(), 3, sha, copy)
        }
        assertTrue(copy.size() <= 3)
    }

    @Test fun hashesEntireResumedArchiveIncludingCorruptPrefix() {
        assertThrows(ModelPackageIntegrity.InvalidPackage::class.java) {
            ModelPackageIntegrity.verify("xbc".byteInputStream(), 3, sha)
        }
    }

    @Test fun acceptsShortReads() {
        val input = object : ByteArrayInputStream(abc) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
        }
        ModelPackageIntegrity.verify(input, 3, sha)
    }

    @Test fun propagatesReadFailureInsteadOfAcceptingPartialCopy() {
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("disconnected")
        }
        assertThrows(IOException::class.java) {
            ModelPackageIntegrity.verify(input, 3, sha, ByteArrayOutputStream())
        }
    }

    @Test fun acceptsEmptyKnownVector() {
        ModelPackageIntegrity.verify(byteArrayOf().inputStream(), 0,
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }
}

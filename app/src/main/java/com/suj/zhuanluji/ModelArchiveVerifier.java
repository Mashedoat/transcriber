package com.suj.zhuanluji;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Verifies the complete compressed archive before any of its contents are extracted. */
final class ModelArchiveVerifier {
    private ModelArchiveVerifier() {}

    /** Copies a provider stream into a private file, bounded by the official archive size.
     * The caller owns the input and must delete the temporary file on every exit path.
     */
    static boolean copyAndVerify(InputStream input, File archive, long expectedSize,
                                 String expectedSha256) throws IOException {
        long total = 0;
        try (FileOutputStream out = new FileOutputStream(archive)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > expectedSize - total) return false;
                out.write(buffer, 0, count);
                total += count;
            }
        }
        return matches(archive, expectedSize, expectedSha256);
    }

    static boolean matches(File archive, long expectedSize, String expectedSha256) throws IOException {
        if (archive.length() != expectedSize) return false;
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 unavailable", e);
        }
        long bytesRead = 0;
        try (InputStream in = new FileInputStream(archive)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
                bytesRead += count;
            }
        }
        StringBuilder actual = new StringBuilder(64);
        for (byte b : digest.digest()) {
            actual.append(Character.forDigit((b & 0xff) >>> 4, 16));
            actual.append(Character.forDigit(b & 0x0f, 16));
        }
        return bytesRead == expectedSize && actual.toString().equals(expectedSha256);
    }
}

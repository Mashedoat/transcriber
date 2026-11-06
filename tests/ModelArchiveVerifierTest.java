package com.suj.zhuanluji;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Dependency-free test: run scripts/test_model_archive_verifier.sh. */
public final class ModelArchiveVerifierTest {
    public static void main(String[] args) throws Exception {
        File f = File.createTempFile("model-verifier-", ".tmp");
        try {
            Files.write(f.toPath(), "abc".getBytes(StandardCharsets.UTF_8));
            String digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
            check(ModelArchiveVerifier.matches(f, 3, digest), "valid archive");
            check(!ModelArchiveVerifier.matches(f, 4, digest), "wrong length");
            Files.write(f.toPath(), "abd".getBytes(StandardCharsets.UTF_8));
            check(!ModelArchiveVerifier.matches(f, 3, digest), "same-length corruption");
            check(ModelArchiveVerifier.copyAndVerify(new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)),
                f, 3, digest), "valid local import");
            check(!ModelArchiveVerifier.copyAndVerify(new ByteArrayInputStream("abd".getBytes(StandardCharsets.UTF_8)),
                f, 3, digest), "same-length corrupt local import");
            check(!ModelArchiveVerifier.copyAndVerify(new ByteArrayInputStream("ab".getBytes(StandardCharsets.UTF_8)),
                f, 3, digest), "truncated local import");
            check(!ModelArchiveVerifier.copyAndVerify(new ByteArrayInputStream("abcd".getBytes(StandardCharsets.UTF_8)),
                f, 3, digest), "oversized local import");
            check(f.length() <= 3, "local import cannot write beyond size limit");
            InputStream shortReads = new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)) {
                @Override public synchronized int read(byte[] b, int off, int len) {
                    return super.read(b, off, Math.min(1, len));
                }
            };
            check(ModelArchiveVerifier.copyAndVerify(shortReads, f, 3, digest), "short reads");
            byte[] large = new byte[1_000_000];
            java.util.Arrays.fill(large, (byte) 'a');
            // NIST SHA-256 million-'a' vector exercises multiple buffers.
            String largeDigest = "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0";
            check(ModelArchiveVerifier.copyAndVerify(new ByteArrayInputStream(large), f, large.length, largeDigest),
                "multi-buffer local import");
            large[0] = 'b';
            Files.write(f.toPath(), large);
            check(!ModelArchiveVerifier.matches(f, large.length, largeDigest), "corrupt resumed prefix");
            InputStream broken = new InputStream() {
                @Override public int read() throws IOException { throw new IOException("disconnected"); }
            };
            try {
                ModelArchiveVerifier.copyAndVerify(broken, f, 3, digest);
                throw new AssertionError("read failure should propagate");
            } catch (IOException expected) { }
            check(f.delete(), "delete test file");
            try {
                ModelArchiveVerifier.matches(f, 0, digest);
                throw new AssertionError("unreadable file should throw");
            } catch (IOException expected) { }
            System.out.println("ModelArchiveVerifier tests passed");
        } finally { f.delete(); }
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
